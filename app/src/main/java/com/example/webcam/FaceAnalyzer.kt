package com.example.webcam

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceAnalyzer {
    private val TAG = "FaceAnalyzer"
    
    // High-accuracy landmark detection and classification
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setMinFaceSize(0.15f)
        .enableTracking()
        .build()

    private val detector = FaceDetection.getClient(options)

    data class AnalysisResult(
        val faceCount: Int,
        val isLeftEyeOpen: Boolean?,
        val isRightEyeOpen: Boolean?,
        val leftEyeProb: Float?,
        val rightEyeProb: Float?,
        val boundingBox: android.graphics.Rect? = null
    )

    fun analyze(bitmap: Bitmap, onResult: (AnalysisResult) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        
        detector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    onResult(AnalysisResult(0, null, null, null, null, null))
                    return@addOnSuccessListener
                }

                // Focus on the first face for eye closure detection
                val face = faces[0]
                val leftEyeProb = face.leftEyeOpenProbability
                val rightEyeProb = face.rightEyeOpenProbability
                val bounds = face.boundingBox
                
                // Threshold for "open" eye is typically around 0.4
                val leftOpen = leftEyeProb?.let { it > 0.4f }
                val rightOpen = rightEyeProb?.let { it > 0.4f }

                onResult(AnalysisResult(
                    faces.size,
                    leftOpen,
                    rightOpen,
                    leftEyeProb,
                    rightEyeProb,
                    bounds
                ))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed: ${e.message}")
            }
    }

    fun stop() {
        detector.close()
    }
}
