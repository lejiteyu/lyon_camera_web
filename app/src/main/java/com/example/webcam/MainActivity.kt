package com.example.webcam

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
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
    private var isSendingFrame = false

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

        lifecycleScope.launch {
            networkManager.startServer { clientIp, frameBytes ->
                runOnUiThread {
                    val imageView = remoteViews.getOrPut(clientIp) {
                        createVideoView(clientIp)
                    }
                    val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                    imageView.setImageBitmap(bitmap)
                    binding.txtServerStatus.text = "Connected - Receiving from ${remoteViews.size} device(s)"
                }
            }
        }
    }

    private fun createVideoView(clientIp: String): ImageView {
        val imageView = ImageView(this).apply {
            layoutParams = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = 400
                columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                setMargins(8, 8, 8, 8)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF333333.toInt())
        }
        binding.gridVideoContainer.addView(imageView)
        
        // Add a label for the IP
        Toast.makeText(this, "New connection: $clientIp", Toast.LENGTH_SHORT).show()
        
        return imageView
    }

    private fun startCameraMode() {
        binding.selectionLayout.visibility = View.GONE
        binding.cameraLayout.visibility = View.VISIBLE

        networkManager.discoverService { host, port ->
            runOnUiThread {
                binding.txtClientStatus.text = "Server found at $host:$port. Connecting..."
            }
            lifecycleScope.launch {
                networkManager.connectToServer(host, port)
                runOnUiThread {
                    binding.txtClientStatus.text = "Connected - Streaming..."
                }
                
                cameraManager.startCamera(this@MainActivity, binding.previewView) { frameBytes ->
                    if (isSendingFrame) return@startCamera // Drop frame if busy
                    
                    isSendingFrame = true
                    lifecycleScope.launch {
                        networkManager.sendFrame(frameBytes)
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
