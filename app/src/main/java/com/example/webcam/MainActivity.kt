package com.example.webcam

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.os.Bundle
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
    private lateinit var prefs: SharedPreferences
    private val remoteViews = mutableMapOf<String, ImageView>()
    @Volatile private var isSendingFrame = false
    @Volatile private var isStreaming = false 
    @Volatile private var isConnecting = false
    @Volatile private var hasSentThumbnail = false
    private var frameCount = 0
    private var lastFpsTimestamp = 0L
    private var currentFps = 0
    private val clientStates = mutableMapOf<String, Boolean>() 
    private var fullScreenIp: String? = null
    private var scaleFactor = 1.0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val REQUEST_CODE_PERMISSIONS = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        networkManager = NetworkManager(this)
        cameraManager = CameraManager(this)
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
            resetToSelection()
            startCameraMode()
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
            networkManager.startServer(onFrameReceived = { clientIp, deviceName, frameBytes ->
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
                    }
                }
            }, onClientDisconnected = { clientIp ->
                runOnUiThread {
                    remoteViews.remove(clientIp)
                    // Also clear grid container
                    binding.gridVideoContainer.removeAllViews()
                }
            })
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

        container.addView(imageView)
        container.addView(label)
        container.addView(fullScreenIcon)
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
                binding.txtClientStatus.text = "Viewer found! Connecting to $host..."
            }
            lifecycleScope.launch {
                val deviceName = android.os.Build.MODEL
                networkManager.connectToServer(host, port, deviceName)
                runOnUiThread {
                    binding.txtClientStatus.text = "Connected! Starting stream..."
                }
                
                hasSentThumbnail = false
                isStreaming = true // Change to true by default if you want continuous stream
                
                // Listen for commands in a SEPARATE coroutine
                lifecycleScope.launch {
                    networkManager.listenForCommands { command ->
                        android.util.Log.d("LyonWebCam", "Received command: $command")
                        runOnUiThread {
                            isStreaming = (command == 1)
                            binding.txtClientStatus.text = if (isStreaming) "Streaming..." else "Paused by Viewer"
                        }
                    }
                }

                // Small delay to let camera stabilize
                kotlinx.coroutines.delay(1000)
                cameraManager.startCamera(this@MainActivity, binding.previewView) { frameBytes ->
                    if (isSendingFrame) return@startCamera
                    
                    // Always send if connected to keep stream alive (as per user request)
                    isSendingFrame = true
                    lifecycleScope.launch {
                        networkManager.sendFrame(frameBytes)
                        
                        // Calculate FPS
                        frameCount++
                        val now = System.currentTimeMillis()
                        if (now - lastFpsTimestamp >= 1000) {
                            currentFps = frameCount
                            frameCount = 0
                            lastFpsTimestamp = now
                            runOnUiThread {
                                binding.txtClientStatus.text = "Connected! Stream: ${currentFps} FPS"
                            }
                        }
                        
                        isSendingFrame = false
                    }
                }
            }
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

    override fun onDestroy() {
        super.onDestroy()
        networkManager.stop()
        cameraManager.shutdown()
    }
}
