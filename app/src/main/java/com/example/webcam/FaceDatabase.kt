package com.example.webcam

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.sqrt

class FaceDatabase(context: Context) {
    private val prefs = context.getSharedPreferences("FaceDatabase", Context.MODE_PRIVATE)
    private val gson = Gson()

    data class FaceRecord(val name: String, val embedding: FloatArray)

    fun registerFace(name: String, embedding: FloatArray) {
        val records = getAllRecords().toMutableList()
        records.add(FaceRecord(name, embedding))
        saveRecords(records)
    }

    fun getAllRecords(): List<FaceRecord> {
        val json = prefs.getString("records", "[]")
        val type = object : TypeToken<List<FaceRecord>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    private fun saveRecords(records: List<FaceRecord>) {
        val json = gson.toJson(records)
        prefs.edit().putString("records", json).apply()
    }

    fun findNearest(embedding: FloatArray): Pair<String, Float>? {
        val records = getAllRecords()
        if (records.isEmpty()) return null

        var bestMatch = ""
        var minDistance = Float.MAX_VALUE

        for (record in records) {
            val dist = l2Distance(embedding, record.embedding)
            if (dist < minDistance) {
                minDistance = dist
                bestMatch = record.name
            }
        }

        return Pair(bestMatch, minDistance)
    }

    private fun l2Distance(v1: FloatArray, v2: FloatArray): Float {
        var sum = 0f
        for (i in v1.indices) {
            val diff = v1[i] - v2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }
    
    fun removeRecord(name: String) {
        val records = getAllRecords().filter { it.name != name }
        saveRecords(records)
    }
}
