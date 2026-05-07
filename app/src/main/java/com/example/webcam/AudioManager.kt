package com.example.webcam

import android.annotation.SuppressLint
import android.media.*
import android.util.Log
import kotlinx.coroutines.*

class AudioManager {
    private val TAG = "AudioManager"
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false

    @SuppressLint("MissingPermission")
    fun startRecording(onAudioData: (ByteArray) -> Unit) {
        if (isRecording) return
        isRecording = true

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT,
            BUFFER_SIZE
        )

        audioRecord?.startRecording()

        GlobalScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    onAudioData(buffer.copyOf(read))
                }
            }
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }
    }

    fun stopRecording() {
        isRecording = false
    }

    fun startPlayback() {
        if (isPlaying) return
        isPlaying = true

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AUDIO_FORMAT)
            .setChannelMask(CHANNEL_CONFIG_OUT)
            .build()

        audioTrack = AudioTrack(
            attributes,
            format,
            BUFFER_SIZE,
            AudioTrack.MODE_STREAM,
            java.util.concurrent.Executors.newSingleThreadExecutor().hashCode() // sessionId
        )

        audioTrack?.play()
    }

    fun playAudio(data: ByteArray) {
        if (!isPlaying) startPlayback()
        audioTrack?.write(data, 0, data.size)
    }

    fun stopPlayback() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
