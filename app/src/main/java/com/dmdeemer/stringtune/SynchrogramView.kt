package com.dmdeemer.stringtune

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

class SynchrogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var audioSamples: ShortArray = ShortArray(0)
    private var frequency: Double = 440.0
    private val sampleRate: Int = 44100

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun updateAudio(samples: ShortArray, freq: Double) {
        audioSamples = samples.copyOf()
        frequency = freq
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = minOf(w, h)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f

        canvas.drawColor(Color.BLACK)

        val samples = audioSamples
        if (samples.isEmpty()) return

        val n = computeSampleCount(frequency, sampleRate, samples.size)
        if (n <= 0) return

        val offset = samples.size - n
        val twoPiFreqOverRate = 2.0 * Math.PI * frequency / sampleRate
        var dotX = 0.0
        var dotY = 0.0
        for (i in 0 until n) {
            val s = samples[offset + i].toDouble()
            val angle = twoPiFreqOverRate * i
            dotX += s * cos(angle)
            dotY += s * sin(angle)
        }

        // Normalize: max value for a pure full-amplitude sine is ~32767 * n / 2
        val maxVal = Short.MAX_VALUE.toDouble() * n / 2.0
        val normX = (dotX / maxVal).coerceIn(-1.0, 1.0)
        val normY = (dotY / maxVal).coerceIn(-1.0, 1.0)

        val scale = minOf(w, h) / 2f * 0.9f
        val endX = cx + (normX * scale).toFloat()
        val endY = cy - (normY * scale).toFloat()  // screen Y is inverted

        canvas.drawLine(cx, cy, endX, endY, linePaint)
    }

    private fun computeSampleCount(frequency: Double, sampleRate: Int, available: Int): Int {
        val samplesPerPeriod = sampleRate.toDouble() / frequency
        val kMin = maxOf(1, ceil(50.0 / samplesPerPeriod).toInt())
        val kMax = floor(1000.0 / samplesPerPeriod).toInt()
        val k = if (kMax >= kMin) {
            // Target ~525 samples (midpoint of the 50–1000 preferred range)
            (525.0 / samplesPerPeriod).roundToInt().coerceIn(kMin, kMax)
        } else {
            kMin
        }
        return minOf((k * samplesPerPeriod).roundToInt(), available)
    }
}
