package com.dmdeemer.stringtune

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.SeekBar
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

class MainActivity : AppCompatActivity() {

    private lateinit var synchrogramView: SynchrogramView
    private lateinit var noteSlider: SeekBar
    private lateinit var noteLabel: TextView

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // Ring buffer large enough for the lowest note (A0 ≈ 1604 samples/period)
    private val maxBufferSamples = 2048
    private val audioRingBuffer = ShortArray(maxBufferSamples)
    private var ringWritePos = 0

    // Default to A4 (index 48 out of 0–87)
    private var selectedKeyIndex = 48

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var recordingJob: Job? = null

    // Note names using flat (♭) notation, cycling A through G♭
    private val noteNames = arrayOf(
        "A", "B\u266D", "B", "C", "D\u266D", "D", "E\u266D", "E", "F", "G\u266D", "G", "A\u266D"
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                startContinuousRecording()
            } else {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        synchrogramView = findViewById(R.id.synchrogramView)
        noteSlider = findViewById(R.id.noteSlider)
        noteLabel = findViewById(R.id.noteLabel)

        noteSlider.max = 87  // 88 keys: index 0 (A0) through 87 (C8)
        noteSlider.progress = selectedKeyIndex
        updateNoteLabel(selectedKeyIndex)

        noteSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                selectedKeyIndex = progress
                updateNoteLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startContinuousRecording()
        }
    }

    override fun onPause() {
        super.onPause()
        recordingJob?.cancel()
        recordingJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun startContinuousRecording() {
        recordingJob?.cancel()
        recordingJob = activityScope.launch {
            withContext(Dispatchers.IO) {
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBufferSize <= 0) return@withContext

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    maxOf(minBufferSize, 4096)
                )
                recorder.startRecording()

                val readChunk = ShortArray(512)
                try {
                    while (isActive) {
                        val read = recorder.read(readChunk, 0, readChunk.size)
                        if (read > 0) {
                            for (i in 0 until read) {
                                audioRingBuffer[ringWritePos] = readChunk[i]
                                ringWritePos = (ringWritePos + 1) % maxBufferSamples
                            }
                            val wp = ringWritePos
                            val snapshot = ShortArray(maxBufferSamples) { i ->
                                audioRingBuffer[(wp + i) % maxBufferSamples]
                            }
                            val freq = keyFrequency(selectedKeyIndex)
                            withContext(Dispatchers.Main) {
                                synchrogramView.updateAudio(snapshot, freq)
                            }
                        }
                    }
                } finally {
                    recorder.stop()
                    recorder.release()
                }
            }
        }
    }

    /** Returns the frequency in Hz for the given piano key index (0 = A0, 48 = A4 = 440 Hz). */
    private fun keyFrequency(keyIndex: Int): Double =
        440.0 * 2.0.pow((keyIndex - 48) / 12.0)

    /**
     * Returns the note name for the given piano key index using flat (♭) notation.
     * Key 0 = A0, key 2 = B0, key 3 = C1, ..., key 87 = C8.
     */
    private fun noteName(keyIndex: Int): String {
        val noteIndex = keyIndex % 12
        // Octave increments at C (noteIndex == 3); A and B stay in the lower octave number
        val octave = if (noteIndex < 3) keyIndex / 12 else keyIndex / 12 + 1
        return "${noteNames[noteIndex]}$octave"
    }

    private fun updateNoteLabel(keyIndex: Int) {
        noteLabel.text = noteName(keyIndex)
    }
}
