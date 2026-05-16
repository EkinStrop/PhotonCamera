package com.hinnka.mycamera.raw

import com.hinnka.mycamera.color.TransferCurve
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 评价测光与场景分析系统
 */
object MeteringSystem {
    private const val TAG = "MeteringSystem"
    private const val DISPLAY_TARGET_LUMA = 0.46f


    data class MeteringResult(
        val meteredEv: Float,
        val dynamicRangeGap: Float,
        val avgLuma: Float,
        val p998: Float
    )

    fun analyzeRenderedExposureEv(
        byteBuffer: ByteBuffer,
        width: Int,
        height: Int,
        weightBuffer: ByteBuffer? = null, // Optional weight mask (e.g. depth map)
        droMode: RawProcessingPreferences.DROMode = RawProcessingPreferences.DROMode.OFF
    ): MeteringResult {
        val pixelCount = width * height
        if (pixelCount == 0) return MeteringResult(0f, 0f, 0f, 0f)

        val lumas = FloatArray(pixelCount)
        var weightedLumaSum = 0f
        var totalWeight = 0f

        byteBuffer.position(0)
        weightBuffer?.position(0)

        for (y in 0 until height) {
            val ny = (y.toFloat() / height) - 0.5f
            for (x in 0 until width) {
                val nx = (x.toFloat() / width) - 0.5f

                // 1. Spatial weight (Center-Weighted)
                // Default fallback as subjects are often in the center
                val distSq = nx * nx + ny * ny
                val spatialWeight = lerp(1.0f, 0.5f, distSq * 2f)

                // 2. Depth weight (Subject-Priority)
                var depthWeight = 1.0f
                if (weightBuffer != null && weightBuffer.hasRemaining()) {
                    // Read depth value (usually Gray8 or RGBA8).
                    // In DepthEstimator output (Grayscale ARGB), all R/G/B channels are the same.
                    val wValue = (weightBuffer.get().toInt() and 0xFF) / 255f
                    // Higher depth value (closer) gets higher weight (up to 5x)
                    depthWeight = lerp(1.0f, 10.0f, wValue)
                    // If it's 4-channel ARGB/RGBA, skip the other 3 bytes
                    if (weightBuffer.capacity() >= pixelCount * 4 && weightBuffer.remaining() >= 3) {
                        weightBuffer.get(); weightBuffer.get(); weightBuffer.get()
                    }
                }

                val r = (byteBuffer.get().toInt() and 0xFF) / 255f
                val g = (byteBuffer.get().toInt() and 0xFF) / 255f
                val b = (byteBuffer.get().toInt() and 0xFF) / 255f
                byteBuffer.get() // skip alpha

                val luma = r * 0.2126f + g * 0.7152f + b * 0.0722f
                val highlightWeight = calculateHighlightWeight(luma, droMode)
                val finalWeight = spatialWeight * depthWeight * highlightWeight

                val idx = y * width + x
                lumas[idx] = luma

                weightedLumaSum += luma * finalWeight
                totalWeight += finalWeight
            }
        }

        // 1. Statistical analysis: Sort to find percentiles
        lumas.sort()
        val p998 = lumas[(pixelCount * 0.998f).toInt().coerceIn(0, pixelCount - 1)]
        
        val highlightAnchorGain = 1f / p998.coerceAtLeast(0.01f)

        // 3. Midtone Balance Logic (Spatial Weighted Average)
        val avgLuma = weightedLumaSum / totalWeight.coerceAtLeast(0.001f)
        val midToneGain = DISPLAY_TARGET_LUMA / avgLuma.coerceAtLeast(0.001f)
        val dynamicRangeGap = midToneGain / highlightAnchorGain

        val extra = 1f - smoothStep(0.66f, 2.22f, dynamicRangeGap)

        val adaptiveGain = midToneGain * lerp(0.9f, 1.2f, extra)

//        val maxAllowedGain = highlightAnchorGain * 1.1f

//        val adaptiveGain = minOf(baseGain, maxAllowedGain)

        val meteredEv = log2(adaptiveGain.coerceIn(0.25f, 4.0f))
        
        PLog.d("MeteringSystem", "Smart AE: dro=$droMode p998=$p998 avg=$avgLuma midToneGain=$midToneGain highlightAnchorGain=$highlightAnchorGain gain=$adaptiveGain ev=$meteredEv gap=$dynamicRangeGap")
        
        return MeteringResult(
            meteredEv = meteredEv.coerceIn(-2f, 2f),
            dynamicRangeGap = dynamicRangeGap,
            avgLuma = avgLuma,
            p998 = p998
        )
    }

    private fun percentile(sortedValues: FloatArray, percentile: Float): Float {
        if (sortedValues.isEmpty()) {
            return 0f
        }

        val index = ((sortedValues.size - 1) * percentile.coerceIn(0f, 1f)).toInt()
        return sortedValues[index]
    }

    private fun log2(value: Float): Float {
        return (ln(value.toDouble()) / ln(2.0)).toFloat()
    }

    private fun calculateHighlightWeight(
        luma: Float,
        droMode: RawProcessingPreferences.DROMode
    ): Float {
        if (!droMode.isEnabled) {
            return 1f
        }

        val minWeight = when (droMode) {
            RawProcessingPreferences.DROMode.OFF -> 1f
            RawProcessingPreferences.DROMode.DR100 -> 0.5f
            RawProcessingPreferences.DROMode.DR200 -> 0.25f
            RawProcessingPreferences.DROMode.DR400 -> 0.12f
        }
        val highlightFraction = smoothStep(0.65f, 0.95f, luma)
        return lerp(1f, minWeight, highlightFraction)
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
