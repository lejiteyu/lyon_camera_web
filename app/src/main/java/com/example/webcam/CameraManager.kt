package com.example.webcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraManager(private val context: Context) {
    private val TAG = "CameraManager"
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFrameCaptured: (ByteArray) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val bitmap = imageProxy.toBitmap()
                    
                    // Manually apply rotation if it's not 0
                    val finalBitmap = if (rotationDegrees != 0) {
                        val matrix = android.graphics.Matrix()
                        matrix.postRotate(rotationDegrees.toFloat())
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }

                    val outputStream = java.io.ByteArrayOutputStream()
                    finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 50, outputStream)
                    val bytes = outputStream.toByteArray()
                    if (bytes.isNotEmpty()) {
                        onFrameCaptured(bytes)
                    }
                } catch (e: Exception) {
                    Log.e("CameraManager", "Analysis error: ${e.message}")
                } finally {
                    imageProxy.close()
                }
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray? {
        try {
            // Using the more reliable toBitmap() extension (requires CameraX 1.3+)
            val bitmap = image.toBitmap()
            
            // Optional: Resize if needed to reduce network load
            // val scaledBitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
            return out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Image conversion failed: ${e.message}")
            return null
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }
}
