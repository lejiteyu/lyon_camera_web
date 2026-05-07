package com.example.webcam

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class StreamingService : LifecycleService() {
    private val TAG = "StreamingService"
    private val CHANNEL_ID = "StreamingChannel"
    private val NOTIFICATION_ID = 1

    private lateinit var networkManager: NetworkManager
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentPreviewView: androidx.camera.view.PreviewView? = null
    private var lastHost: String = ""
    private var lastPort: Int = 0

    private var isStreaming = true
    private var isSendingFrame = false
    private var forceSendNextFrame = false

    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen OFF - Re-binding camera without preview")
                    startCameraWithPreview(true) // Force no preview
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen ON - Re-binding camera with preview if available")
                    startCameraWithPreview(false)
                }
            }
        }
    }

    inner class ServiceBinder : Binder() {
        fun getService(): StreamingService = this@StreamingService
    }
    private val binder = ServiceBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun setPreviewView(previewView: androidx.camera.view.PreviewView?) {
        this.currentPreviewView = previewView
        if (isStreaming) {
            startCameraWithPreview()
        }
    }

    override fun onCreate() {
        super.onCreate()
        networkManager = NetworkManager(this)
        cameraManager = CameraManager(this)
        audioManager = AudioManager()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LyonWebCam:StreamingWakeLock")
        
        createNotificationChannel()

        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val action = intent?.action
        if (action == "START_STREAMING") {
            val host = intent.getStringExtra("HOST") ?: ""
            val port = intent.getIntExtra("PORT", 0)
            startForegroundService(host, port)
        } else if (action == "STOP_STREAMING") {
            stopStreaming()
        } else if (action == "SWITCH_CAMERA") {
            cameraManager.switchCamera(this, currentPreviewView) { frameBytes ->
                handleCapturedFrame(frameBytes)
            }
        } else if (action == "RECONNECT") {
            reconnect()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService(host: String, port: Int) {
        lastHost = host
        lastPort = port
        val notification = createNotification("Searching for Viewer...")
        startForeground(NOTIFICATION_ID, notification)
        
        wakeLock?.acquire()

        lifecycleScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Connecting to $host:$port...")
            networkManager.connectToServer(host, port, Build.MODEL)
            Log.d(TAG, "Connected successfully, starting stream...")
            
            isStreaming = true
            updateNotification("Streaming to $host")
            setupStreamingLoops()
            
            // Start Camera
            startCameraWithPreview()
        }
    }

    private fun reconnect() {
        lifecycleScope.launch(Dispatchers.IO) {
            networkManager.stop()
            Log.d(TAG, "Reconnecting to $lastHost:$lastPort...")
            networkManager.connectToServer(lastHost, lastPort, Build.MODEL)
            Log.d(TAG, "Reconnected successfully")
            
            isStreaming = true
            updateNotification("Reconnected to $lastHost")
            setupStreamingLoops()
        }
    }

    private fun setupStreamingLoops() {
        // Listen for commands (Pause/Play/Refresh from server)
        lifecycleScope.launch(Dispatchers.IO) {
            networkManager.listenForCommands { command ->
                Log.d(TAG, "Received command: $command")
                when (command) {
                    0 -> isStreaming = false
                    1 -> isStreaming = true
                    2 -> forceSendNextFrame = true
                }
                updateNotification(if (isStreaming) "Streaming..." else "Paused by Viewer")
            }
        }

        // Start Audio
        audioManager.stopRecording() // Stop if already running
        audioManager.startRecording { audioData ->
            lifecycleScope.launch(Dispatchers.IO) {
                networkManager.sendAudio(audioData)
            }
        }
    }

    private fun startCameraWithPreview(forceNoPreview: Boolean = false) {
        lifecycleScope.launch(Dispatchers.Main) {
            val previewToUse = if (forceNoPreview) null else currentPreviewView
            cameraManager.startCamera(this@StreamingService, previewToUse) { frameBytes ->
                handleCapturedFrame(frameBytes)
            }
        }
    }

    private fun handleCapturedFrame(frameBytes: ByteArray) {
        if ((isStreaming || forceSendNextFrame) && !isSendingFrame) {
            isSendingFrame = true
            forceSendNextFrame = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    networkManager.sendFrame(frameBytes)
                    Log.d(TAG, "Frame sent: ${frameBytes.size} bytes")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send frame: ${e.message}")
                } finally {
                    isSendingFrame = false
                }
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        wakeLock?.let { if (it.isHeld) it.release() }
        networkManager.stop()
        cameraManager.shutdown()
        audioManager.stopRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Streaming Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Lyon WebCam Active")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) {}
        stopStreaming()
        super.onDestroy()
    }
}
