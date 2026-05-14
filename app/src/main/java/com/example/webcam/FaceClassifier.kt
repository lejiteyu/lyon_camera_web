package com.example.webcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FaceClassifier(context: Context) {
    private val TAG = "FaceClassifier"
    private val MODEL_FILE = "mobile_facenet.tflite"
    private val INPUT_SIZE = 160 // FaceNet standard
    private var outputSize = 128 // Default, will be updated dynamically

    private var interpreter: Interpreter? = null

    init {
        try {
            val model = FileUtil.loadMappedFile(context, MODEL_FILE)
            val options = Interpreter.Options()
            interpreter = Interpreter(model, options)
            
            // Dynamically detect output size
            val outputShape = interpreter?.getOutputTensor(0)?.shape()
            if (outputShape != null && outputShape.size > 1) {
                outputSize = outputShape[1]
                Log.d(TAG, "Detected model output size: $outputSize")
            }
            
            Log.d(TAG, "TFLite model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TFLite model: ${e.message}")
        }
    }

    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(INPUT_SIZE, INPUT_SIZE, ResizeOp.Method.BILINEAR))
        .add(NormalizeOp(127.5f, 127.5f)) // Normalize to [-1, 1]
        .build()

    fun getFaceEmbedding(bitmap: Bitmap, faceRect: Rect): FloatArray? {
        if (interpreter == null) return null

        try {
            // 1. Crop face from bitmap
            val croppedBitmap = Bitmap.createBitmap(
                bitmap,
                faceRect.left.coerceAtLeast(0),
                faceRect.top.coerceAtLeast(0),
                faceRect.width().coerceAtMost(bitmap.width - faceRect.left),
                faceRect.height().coerceAtMost(bitmap.height - faceRect.top)
            )

            // 2. Preprocess image
            var tensorImage = TensorImage.fromBitmap(croppedBitmap)
            tensorImage = imageProcessor.process(tensorImage)

            // 3. Run inference
            val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4)
            outputBuffer.order(ByteOrder.nativeOrder())
            interpreter?.run(tensorImage.buffer, outputBuffer)

            // 4. Convert output to FloatArray
            outputBuffer.rewind()
            val embedding = FloatArray(outputSize)
            outputBuffer.asFloatBuffer().get(embedding)
            
            return embedding
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting embedding: ${e.message}")
            return null
        }
    }

    fun close() {
        interpreter?.close()
    }
}
