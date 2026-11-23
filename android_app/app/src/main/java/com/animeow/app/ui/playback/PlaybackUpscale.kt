@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.animeow.app.ui.playback

import android.content.Context
import android.opengl.GLES20
import android.os.Handler
import android.os.Looper
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.SeparableConvolutionShaderProgram
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.sin

internal fun playbackLanczosWeight(position: Float): Float {
    val x = abs(position)
    if (x < 0.00001f) return 1f
    if (x >= 3f) return 0f
    val radians = Math.PI * x
    return (sin(radians) / radians * sin(radians / 3) / (radians / 3)).toFloat()
}

/** Configure from the first frame itself, so enabling never depends on polling player metadata. */
internal class SpatialUpscale(private val report: (String) -> Unit) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SeparableConvolutionShaderProgram(context, useHdr, object : ConvolutionFunction1D.Provider {
            override fun configure(inputSize: Size): Size {
                val (width, height) = playbackUpscaleSize(inputSize.width, inputSize.height)
                val status = if (width == inputSize.width && height == inputSize.height) "无需放大：当前画面已达到 4K 尺寸边界，保留原画"
                    else "空间放大已启用 · ${inputSize.width}×${inputSize.height} → ${width}×$height"
                android.util.Log.i("AniMeowUpscale", status)
                Handler(Looper.getMainLooper()).post { report(status) }
                return Size(width, height)
            }
            override fun getConvolution(presentationTimeUs: Long) = object : ConvolutionFunction1D {
                override fun domainStart() = -3f
                override fun domainEnd() = 3f
                override fun value(samplePosition: Float) = playbackLanczosWeight(samplePosition)
            }
        })
}

/** Original Anime4K trained Restore/Upscale graphs, including M-model skip connections. */
internal class Anime4kModel(private val model: String, private val label: String, private val report: (String) -> Unit) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = try { CnnProgram(context, useHdr, model, label, report) }
    catch (error: Exception) { throw VideoFrameProcessingException(error) }
}

internal fun playbackUpscaleSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = min(2.0, min(3840.0 / max(width, height), 2160.0 / min(width, height))).coerceAtLeast(1.0)
    return (width * scale).toInt() to (height * scale).toInt()
}

private class CnnProgram(private val context: Context, private val hdr: Boolean, private val model: String,
    private val label: String, private val report: (String) -> Unit) : BaseGlShaderProgram(hdr, 1) {
    private val vertex = """
        attribute vec4 aFramePosition;
        varying vec2 vTexCoord;
        void main() { gl_Position = aFramePosition; vTexCoord = aFramePosition.xy * 0.5 + 0.5; }
    """.trimIndent()
    private data class Input(val source: Int, val unit: Int, val size: Boolean)
    private data class Pass(val program: GlProgram, val inputs: List<Input>)
    private val manifest = org.json.JSONObject(context.assets.open("playback-upscale/$model.json").bufferedReader().use { it.readText() })
    private val scale = manifest.getInt("scale")
    private val programs = mutableListOf<Pass>()
    private val passthrough = program("""
        precision highp float;
        uniform sampler2D uInput;
        varying vec2 vTexCoord;
        void main() { gl_FragColor = texture2D(uInput, vTexCoord); }
    """.trimIndent())
    private var textures = intArrayOf()
    private var framebuffers = intArrayOf()
    private var width = 0
    private var height = 0
    private var enabled = false
    private var frames = 0
    private var firstFrameAt = 0L
    private var activeStatus = ""

    private fun program(fragment: String) = GlProgram(vertex, fragment).apply {
        setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseBuffers()
        width = inputWidth; height = inputHeight
        frames = 0
        val reason = when {
            hdr -> "HDR 视频暂不支持 AI 超分，已保留原画"
            max(width, height) > 1920 || min(width, height) > 1080 -> "视频已超过 1080p，无需放大，已保留原画"
            GlUtil.getContextMajorVersion() < 3 -> "当前设备图形硬件不支持画质增强，已保留原画"
            else -> null
        }
        enabled = reason == null
        if (enabled) {
            if (programs.isEmpty()) {
                val passes = manifest.getJSONArray("passes")
                repeat(passes.length()) { index ->
                    val pass = passes.getJSONObject(index)
                    val shader = context.assets.open("playback-upscale/${pass.getString("shader")}").bufferedReader().use { it.readText() }
                    val inputs = pass.getJSONArray("inputs")
                    programs += Pass(program(shader), List(inputs.length()) { unit ->
                        val input = inputs.getJSONObject(unit)
                        Input(input.getInt("source"), input.getInt("unit"), input.getBoolean("size"))
                    })
                }
            }
            // M graphs merge earlier features; retain each tensor until the final skip-connection pass.
            // Signed half-float storage is necessary: normalized 8-bit textures corrupt CNN features.
            repeat(programs.size - 1) {
                textures += GlUtil.createTexture(width, height, true)
                framebuffers += GlUtil.createFboForTexture(textures.last())
            }
        }
        val output = if (enabled) Size(width * scale, height * scale) else Size(width, height)
        activeStatus = "$label 已启用 · ${width}×$height → ${output.width}×${output.height}"
        val status = if (enabled) "正在处理${label}首帧 · ${width}×$height → ${output.width}×${output.height}"
            else "AI 超分未启用：$reason"
        android.util.Log.i("AniMeowUpscale", "$model $status")
        Handler(Looper.getMainLooper()).post { report(status) }
        return output
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            if (!enabled) {
                passthrough.use()
                passthrough.setSamplerTexIdUniform("uInput", inputTexId, 0)
                draw(passthrough)
                return
            }
            val output = IntArray(1)
            val viewport = IntArray(4)
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, output, 0)
            GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
            try {
                programs.forEachIndexed { index, pass ->
                    val final = index == programs.lastIndex
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, if (final) output[0] else framebuffers[index])
                    GLES20.glViewport(0, 0, width * if (final) scale else 1, height * if (final) scale else 1)
                    pass.program.use()
                    pass.inputs.forEach { input ->
                        pass.program.setSamplerTexIdUniform("uTexture${input.unit}", if (input.source == -1) inputTexId else textures[input.source], input.unit)
                        if (input.size) pass.program.setFloatsUniform("uSize${input.unit}", floatArrayOf(width.toFloat(), height.toFloat()))
                    }
                    draw(pass.program)
                }
                GlUtil.checkGlError()
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, output[0])
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            }
            if (frames++ == 0) {
                firstFrameAt = android.os.SystemClock.elapsedRealtime()
                Handler(Looper.getMainLooper()).post { report(activeStatus) }
            }
            if (frames % 120 == 0) android.util.Log.i("AniMeowUpscale", "$model frames=$frames wallMs=${android.os.SystemClock.elapsedRealtime() - firstFrameAt}")
        } catch (error: Exception) { throw VideoFrameProcessingException(error, presentationTimeUs) }
    }

    private fun draw(program: GlProgram) {
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun releaseBuffers() {
        framebuffers.forEach { GlUtil.deleteFbo(it) }
        textures.forEach { GlUtil.deleteTexture(it) }
        textures = intArrayOf(); framebuffers = intArrayOf()
    }

    override fun release() {
        releaseBuffers(); programs.forEach { it.program.delete() }; passthrough.delete(); super.release()
    }
}
