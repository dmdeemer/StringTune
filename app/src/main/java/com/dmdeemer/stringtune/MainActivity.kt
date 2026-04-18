package com.dmdeemer.stringtune

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var recordButton: Button
    private lateinit var playButton: Button
    private lateinit var statusText: TextView

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val recordingDurationMs = 5000L

    private var audioData: ShortArray? = null
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var activeJob: Job? = null

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startRecording()
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recordButton = findViewById(R.id.recordButton)
        playButton = findViewById(R.id.playButton)
        statusText = findViewById(R.id.statusText)

        recordButton.setOnClickListener { onRecordClicked() }
        playButton.setOnClickListener { onPlayClicked() }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun onRecordClicked() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        recordButton.isEnabled = false
        playButton.isEnabled = false
        statusText.setText(R.string.status_recording)

        val totalSamples = (sampleRate * recordingDurationMs / 1000).toInt()
        val buffer = ShortArray(totalSamples)

        activeJob?.cancel()
        activeJob = activityScope.launch {
            withContext(Dispatchers.IO) {
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBufferSize <= 0) return@withContext
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    maxOf(minBufferSize, totalSamples * 2)
                )
                recorder.startRecording()

                var samplesRead = 0
                while (samplesRead < totalSamples) {
                    val chunk = recorder.read(buffer, samplesRead, totalSamples - samplesRead)
                    if (chunk <= 0) break
                    samplesRead += chunk
                }

                recorder.stop()
                recorder.release()
            }

            audioData = buffer
            statusText.setText(R.string.status_recorded)
            recordButton.isEnabled = true
            playButton.isEnabled = true
        }
    }

    private fun onPlayClicked() {
        val data = audioData ?: return
        playButton.isEnabled = false
        recordButton.isEnabled = false
        statusText.setText(R.string.status_playing)

        activeJob?.cancel()
        activeJob = activityScope.launch {
            withContext(Dispatchers.IO) {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    audioFormat
                )
                if (minBufferSize <= 0) return@withContext

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(audioFormat)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBufferSize, data.size * 2))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(data, 0, data.size)
                audioTrack.play()

                val durationMs = data.size.toLong() * 1000L / sampleRate
                delay(durationMs + 200)

                audioTrack.stop()
                audioTrack.release()
            }

            statusText.setText(R.string.status_recorded)
            recordButton.isEnabled = true
            playButton.isEnabled = true
        }
    }
}
