package com.example.webcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.webcam.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var networkManager: NetworkManager
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private lateinit var faceAnalyzer: FaceAnalyzer
    private lateinit var faceClassifier: FaceClassifier
    private lateinit var faceDatabase: FaceDatabase
    private lateinit var prefs: SharedPreferences
    private val remoteViews = mutableMapOf<String, ImageView>()
    private val clientAudioStates = mutableMapOf<String, Boolean>() // Store mute/unmute state
    @Volatile private var isSendingFrame = false
    @Volatile private var isStreaming = false 
    @Volatile private var isConnecting = false
    
    private var streamingService: StreamingService? = null
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            val binder = service as StreamingService.ServiceBinder
            streamingService = binder.getService()
            // Give the preview view to the service
            streamingService?.setPreviewView(binding.previewView)
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            streamingService = null
        }
    }
    @Volatile private var hasSentThumbnail = false
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var currentFps = 0
    private val clientStates = mutableMapOf<String, Boolean>() 
    private var fullScreenIp: String? = null
    private var scaleFactor = 1.0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var lastAnalysisTimestamp = 0L
    private val ANALYSIS_INTERVAL_MS = 300L
    private var lastFaceEmbedding: FloatArray? = null
    private var lastFaceRect: android.graphics.Rect? = null
    private var lastBitmap: Bitmap? = null

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )
    private val REQUEST_CODE_PERMISSIONS = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkManager = NetworkManager(this)
        cameraManager = CameraManager(this)
        audioManager = AudioManager()
        faceAnalyzer = FaceAnalyzer()
        faceClassifier = FaceClassifier(this)
        faceDatabase = FaceDatabase(this)
        prefs = getSharedPreferences("LyonWebCamPrefs", MODE_PRIVATE)

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(1.0f, 5.0f) // Limit zoom from 1x to 5x
                binding.imgFullScreen.scaleX = scaleFactor
                binding.imgFullScreen.scaleY = scaleFactor
                return true
            }
        })

        binding.imgFullScreen.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        if (prefs.getBoolean("isFirstRun", true)) {
            showOnboardingGuide()
        }

        binding.btnStartServer.setOnClickListener {
            startViewerMode()
        }

        binding.btnJoinAsCamera.setOnClickListener {
            startCameraMode()
        }

        binding.btnCloseFullScreen.setOnClickListener {
            hideFullScreen()
        }

        binding.btnReconnect.setOnClickListener {
            // Only reconnect network, don't reset camera
            val reconnectIntent = Intent(this, StreamingService::class.java).apply {
                action = "RECONNECT"
            }
            startService(reconnectIntent)
            Toast.makeText(this, "Reconnecting...", Toast.LENGTH_SHORT).show()
        }

        binding.btnSwitchCamera.setOnClickListener {
            // Tell background service to switch camera (It will handle both stream and preview)
            val switchIntent = Intent(this, StreamingService::class.java).apply {
                action = "SWITCH_CAMERA"
            }
            startService(switchIntent)
        }

        binding.btnRegisterFace.setOnClickListener {
            showRegisterDialog()
        }
    }

    private fun showOnboardingGuide() {
        AlertDialog.Builder(this)
            .setTitle("🚀 快速使用導覽")
            .setMessage(
                "歡迎使用 Lyon WebCam！請依照以下步驟完成連線：\n\n" +
                "1️⃣ 確保兩台手機連接到【同一個 Wi-Fi】或是其中一台開啟【熱點】供另一台連線。\n" +
                "2️⃣【手機 A】：點擊「Start Viewer」。\n" +
                "3️⃣【手機 B】：點擊「Join as Camera」。\n\n" +
                "App 會自動在區域網路中尋找彼此並開始串流！"
            )
            .setPositiveButton("開始使用") { dialog, _ ->
                prefs.edit().putBoolean("isFirstRun", false).apply()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun startViewerMode() {
        binding.selectionLayout.visibility = View.GONE
        binding.viewerLayout.visibility = View.VISIBLE
        binding.layoutLiveIndicator.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            networkManager.startServer(
                onFrameReceived = { clientIp, deviceName, frameBytes ->
                    runOnUiThread {
                        // Flash the LIVE dot
                        binding.viewLiveDot.alpha = 0.3f
                        binding.viewLiveDot.animate().alpha(1.0f).setDuration(200).start()
                        
                        val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size) ?: return@runOnUiThread
                        
                        val imageView = remoteViews[clientIp] ?: createVideoView(clientIp, deviceName).also {
                            remoteViews[clientIp] = it
                        }
                        imageView.setImageBitmap(bitmap)
                        
                        if (fullScreenIp == clientIp) {
                            binding.imgFullScreen.setImageBitmap(bitmap)
                            
                            // Perform Face Detection only in full screen
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastAnalysisTimestamp > ANALYSIS_INTERVAL_MS) {
                                lastAnalysisTimestamp = currentTime
                                lastBitmap = bitmap
                                faceAnalyzer.analyze(bitmap) { result ->
                                    lastFaceRect = result.boundingBox
                                    // Extract embedding for recognition
                                    if (result.boundingBox != null) {
                                        lastFaceEmbedding = faceClassifier.getFaceEmbedding(bitmap, result.boundingBox)
                                    } else {
                                        lastFaceEmbedding = null
                                    }
                                    updateFaceStatusUI(result, lastFaceEmbedding)
                                }
                            }
                        }
                    }
                },
                onAudioReceived = { clientIp, audioData ->
                    if (clientAudioStates[clientIp] == true) {
                        audioManager.playAudio(audioData)
                    }
                },
                onClientDisconnected = { clientIp ->
                    runOnUiThread {
                        remoteViews.remove(clientIp)
                        // Also clear grid container
                        binding.gridVideoContainer.removeAllViews()
                    }
                }
            )
        }
    }

    private fun createVideoView(clientIp: String, deviceName: String): ImageView {
        val container = FrameLayout(this).apply {
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = 500 // Increased height for better visibility
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(8, 8, 8, 8)
            }
            setBackgroundColor(0xFF222222.toInt())
            setOnClickListener { togglePlayPause(clientIp) }
            setOnLongClickListener { showFullScreen(clientIp); true }
        }

        val imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER // Changed to FIT_CENTER for orientation support
            setBackgroundColor(0xFF000000.toInt())
        }

        val label = TextView(this).apply {
            text = deviceName
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x80000000.toInt())
            setPadding(12, 4, 12, 4)
            textSize = 10f
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            }
        }
        
        val fullScreenIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_zoom)
            layoutParams = FrameLayout.LayoutParams(48, 48).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, 8, 8)
            }
            alpha = 0.7f
            setOnClickListener { showFullScreen(clientIp) }
        }

        val refreshIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            layoutParams = FrameLayout.LayoutParams(48, 48).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(8, 0, 0, 8)
            }
            alpha = 0.7f
            setOnClickListener { 
                networkManager.sendCommandToClient(clientIp, 2)
                Toast.makeText(this@MainActivity, "Requesting refresh...", Toast.LENGTH_SHORT).show()
            }
        }

        val audioIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode) // Default muted icon
            layoutParams = FrameLayout.LayoutParams(48, 48).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 8, 8, 0)
            }
            alpha = 0.7f
            setOnClickListener {
                val isEnabled = clientAudioStates[clientIp] ?: false
                val newState = !isEnabled
                clientAudioStates[clientIp] = newState
                setImageResource(if (newState) android.R.drawable.ic_lock_silent_mode_off else android.R.drawable.ic_lock_silent_mode)
                Toast.makeText(this@MainActivity, if (newState) "Audio Enabled" else "Audio Muted", Toast.LENGTH_SHORT).show()
            }
        }

        container.addView(imageView)
        container.addView(label)
        container.addView(fullScreenIcon)
        container.addView(refreshIcon)
        container.addView(audioIcon)
        binding.gridVideoContainer.addView(container)
        
        return imageView
    }

    private fun togglePlayPause(clientIp: String) {
        val isPlaying = clientStates[clientIp] ?: false
        val newState = !isPlaying
        clientStates[clientIp] = newState
        
        networkManager.sendCommandToClient(clientIp, if (newState) 1 else 0)
        Toast.makeText(this, if (newState) "Playing $clientIp" else "Paused $clientIp", Toast.LENGTH_SHORT).show()
    }

    private fun showFullScreen(clientIp: String) {
        fullScreenIp = clientIp
        scaleFactor = 1.0f // Reset zoom when entering
        binding.imgFullScreen.scaleX = 1.0f
        binding.imgFullScreen.scaleY = 1.0f
        binding.imgFullScreen.visibility = View.VISIBLE
        binding.btnCloseFullScreen.visibility = View.VISIBLE
        binding.txtFaceStatus.visibility = View.VISIBLE
        binding.btnRegisterFace.visibility = View.VISIBLE
        binding.txtFaceStatus.text = "正在初始化人臉辨識..."
        
        // Switch to landscape
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        
        // Enter immersive mode
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        // If it was paused, start playing automatically for full screen
        if (clientStates[clientIp] == false) {
            togglePlayPause(clientIp)
        }
    }

    private fun hideFullScreen() {
        fullScreenIp = null
        binding.imgFullScreen.visibility = View.GONE
        binding.btnCloseFullScreen.visibility = View.GONE
        binding.txtFaceStatus.visibility = View.GONE
        binding.btnRegisterFace.visibility = View.GONE
        
        // Restore orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        
        // Exit immersive mode
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    override fun onBackPressed() {
        if (binding.imgFullScreen.visibility == View.VISIBLE) {
            hideFullScreen()
            return
        }

        if (binding.selectionLayout.visibility == View.GONE) {
            resetToSelection()
            return
        }

        super.onBackPressed()
    }

    private fun resetToSelection() {
        // Unbind service first
        try { unbindService(serviceConnection) } catch (e: Exception) {}
        streamingService = null

        // Stop background service
        val stopIntent = Intent(this, StreamingService::class.java).apply {
            action = "STOP_STREAMING"
        }
        stopService(stopIntent)

        audioManager.stopRecording()
        audioManager.stopPlayback()
        networkManager.stop()
        cameraManager.shutdown()
        
        // Clear UI states
        binding.viewerLayout.visibility = View.GONE
        binding.cameraLayout.visibility = View.GONE
        binding.selectionLayout.visibility = View.VISIBLE
        
        binding.gridVideoContainer.removeAllViews()
        remoteViews.clear()
        clientStates.clear()
        fullScreenIp = null
        hasSentThumbnail = false
        isStreaming = false
        isConnecting = false
        
        // Re-initialize for next use
        networkManager = NetworkManager(this)
        cameraManager = CameraManager(this)
        
        Toast.makeText(this, "Stopped and returned to menu", Toast.LENGTH_SHORT).show()
    }

    private fun startCameraMode() {
        binding.selectionLayout.visibility = View.GONE
        binding.cameraLayout.visibility = View.VISIBLE
        binding.txtClientStatus.text = "Searching for Viewer..."

        networkManager.discoverService { host, port ->
            if (isConnecting) return@discoverService
            isConnecting = true
            
            runOnUiThread {
                binding.txtClientStatus.text = "Viewer found! Starting background service..."
            }
            
            // Start Foreground Service for EVERYTHING (Connection, Camera, Audio)
            val serviceIntent = Intent(this@MainActivity, StreamingService::class.java).apply {
                action = "START_STREAMING"
                putExtra("HOST", host)
                putExtra("PORT", port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            // Bind to the service to share the preview view
            bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-attach preview if service is running
        streamingService?.setPreviewView(binding.previewView)
    }

    override fun onStop() {
        // Detach preview when going to background to keep camera alive in service
        streamingService?.setPreviewView(null)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkManager.stop()
        cameraManager.shutdown()
        faceAnalyzer.stop()
        faceClassifier.close()
    }

    private fun updateFaceStatusUI(result: FaceAnalyzer.AnalysisResult, embedding: FloatArray?) {
        runOnUiThread {
            if (result.faceCount == 0) {
                binding.txtFaceStatus.text = "未偵測到人臉"
                binding.txtFaceStatus.setTextColor(0xFFFFFFFF.toInt())
                return@runOnUiThread
            }

            val sb = StringBuilder()
            
            // 1. Identity Recognition
            if (embedding != null) {
                val match = faceDatabase.findNearest(embedding)
                if (match != null && match.second < 1.0f) { // Threshold 1.0 for MobileFaceNet
                    sb.append("辨識身分: ${match.first}\n")
                    sb.append("置信度: ${String.format("%.2f", 1.0f - match.second/2.0f)}\n")
                } else {
                    sb.append("辨識身分: 未知路人\n")
                }
            }

            sb.append("人臉數量: ${result.faceCount}\n")
            
            val isClosed = (result.isLeftEyeOpen == false || result.isRightEyeOpen == false)
            if (isClosed) {
                sb.append("⚠️ 偵測到閉眼！")
                binding.txtFaceStatus.setTextColor(0xFFFF5252.toInt()) // Red
            } else {
                sb.append("眼睛狀態: 開啟")
                binding.txtFaceStatus.setTextColor(0xFF4CAF50.toInt()) // Green
            }
            
            binding.txtFaceStatus.text = sb.toString()
        }
    }

    private fun showRegisterDialog() {
        val embedding = lastFaceEmbedding
        if (embedding == null) {
            Toast.makeText(this, "未偵測到人臉，無法註冊！", Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this)
        AlertDialog.Builder(this)
            .setTitle("註冊新臉孔")
            .setMessage("請輸入此人的姓名：")
            .setView(input)
            .setPositiveButton("完成註冊") { _, _ ->
                val name = input.text.toString()
                if (name.isNotEmpty()) {
                    faceDatabase.registerFace(name, embedding)
                    Toast.makeText(this, "已成功註冊：$name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
