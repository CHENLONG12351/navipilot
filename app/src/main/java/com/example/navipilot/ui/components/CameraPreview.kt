package com.example.navipilot.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * H264 实时摄像头预览组件（性能优化版）
 *
 * @param frameBytes     最新 H264 帧数据（降级路径，仅当 wsClient 为 null 时使用）
 * @param width          视频宽度（仅在首次或分辨率变化时使用）
 * @param height         视频高度
 * @param isKeyFrame     是否为关键帧（用于初始化编解码器）
 * @param modifier       Compose 修饰符
 * @param wsClient       可选的 WebSocket 客户端。提供时通过 onCameraFrame 回调直连解码器，
 *                       完全绕过 Compose StateFlow 管道，消除延迟。
 */
@androidx.compose.runtime.Composable
fun CameraPreview(
    frameBytes: ByteArray?,
    width: Int,
    height: Int,
    isKeyFrame: Boolean,
    modifier: Modifier = Modifier,
    wsClient: com.example.navipilot.data.CarrotWsClient? = null
) {
    // ---- 初始化解码引擎（只创建一次）----
    val engine = remember {
        CameraDecodeEngine().also { Log.i("CameraPreview", "📹 CameraDecodeEngine 创建") }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            engine.release()
            Log.i("CameraPreview", "📹 CameraDecodeEngine 释放")
        }
    }

    // ---- 直连解码器：通过 WebSocket 回调直接喂帧，绕过 Compose StateFlow ----
    DisposableEffect(wsClient) {
        val client = wsClient
        if (client != null) {
            Log.i("CameraPreview", "📹 注册直连回调，绕过 StateFlow")
            client.onCameraFrame = { frame ->
                engine.feedFrame(frame.payload)
            }
        }
        onDispose {
            if (client != null) {
                client.onCameraFrame = null
            }
        }
    }

    // ---- TextureView + 绑定解码器 ----
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surf: SurfaceTexture, w: Int, h: Int) {
                        engine.onSurfaceAvailable(Surface(surf), w, h)
                    }

                    override fun onSurfaceTextureSizeChanged(surf: SurfaceTexture, w: Int, h: Int) {
                        engine.onSurfaceAvailable(Surface(surf), w, h)
                    }

                    override fun onSurfaceTextureDestroyed(surf: SurfaceTexture): Boolean {
                        engine.release()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surf: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier
    )

    // ---- 分辨率更新 ----
    LaunchedEffect(width, height) {
        if (width > 0 && height > 0) {
            engine.updateResolution(width, height)
        }
    }

    // ---- 关键帧触发编解码器初始化 ----
    LaunchedEffect(isKeyFrame, width, height) {
        if (isKeyFrame && width > 0 && height > 0) {
            engine.ensureCodec(width, height)
        }
    }

    // ---- 帧数据送入解码器（频率控制）----
    LaunchedEffect(frameBytes) {
        val data = frameBytes ?: return@LaunchedEffect
        engine.feedFrame(data)
    }
}

/**
 * 相机解码引擎
 *
 * 在专用后台线程上运行 MediaCodec，帧率控制在 20fps，
 * 输出缓冲由 Handler 定时 drain（每 30ms），与输入解耦。
 */
private class CameraDecodeEngine {
    companion object {
        private const val TAG = "CameraDecodeEngine"
        /** 目标帧率上限（超过的帧丢掉） */
        private const val MAX_FPS = 20
        /** 帧间隔纳秒 */
        private val FRAME_INTERVAL_NS = 1_000_000_000L / MAX_FPS
        /** drain 输出缓冲的间隔 ms */
        private const val DRAIN_INTERVAL_MS = 30L
        /** 输入队列超时 μs */
        private const val DEQUEUE_TIMEOUT_US = 2000L
    }

    // ---- 线程模型 ----
    private val decoderThread = HandlerThread("CameraDecoder").apply { start() }
    private val decoderHandler = Handler(decoderThread.looper)

    // ---- 解码器状态 ----
    private var mediaCodec: MediaCodec? = null
    private var surface: Surface? = null
    private var codecReady = AtomicBoolean(false)
    private var videoWidth = AtomicInteger(640)
    private var videoHeight = AtomicInteger(480)

    // ---- 帧率控制 ----
    private var lastFrameTimeNs = 0L

    // ---- 定时 drain 任务 ----
    private val drainRunnable = object : Runnable {
        override fun run() {
            drainOutputBuffers()
            decoderHandler.postDelayed(this, DRAIN_INTERVAL_MS)
        }
    }

    init {
        decoderHandler.post(drainRunnable)
    }

    /** Surface 可用时记录 */
    fun onSurfaceAvailable(surf: Surface, w: Int, h: Int) {
        decoderHandler.post {
            surface = surf
            if (w > 0 && h > 0) {
                videoWidth.set(w)
                videoHeight.set(h)
            }
            // 如果已有 codec，重新绑定
            mediaCodec?.let { releaseCodec() }
        }
    }

    /** 更新分辨率 */
    fun updateResolution(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            videoWidth.set(w)
            videoHeight.set(h)
        }
    }

    /** 确保编解码器已初始化（在关键帧时调用） */
    fun ensureCodec(w: Int, h: Int) {
        if (w > 0 && h > 0) {
            videoWidth.set(w)
            videoHeight.set(h)
        }
        decoderHandler.post {
            if (codecReady.get()) return@post
            initCodecInternal()
        }
    }

    /** 在后台线程初始化编解码器 */
    private fun initCodecInternal() {
        val surf = surface ?: run {
            Log.w(TAG, "Surface 不可用，延迟初始化")
            return
        }
        try {
            releaseCodec()
            val w = videoWidth.get()
            val h = videoHeight.get()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, MAX_FPS)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // 降低延迟的关键参数
            format.setInteger("priority", 0)  // 实时优先级
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surf, null, 0)
            codec.start()
            mediaCodec = codec
            codecReady.set(true)
            Log.i(TAG, "✅ MediaCodec 初始化完成: ${w}x${h}")
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec 初始化失败: ${e.message}")
            codecReady.set(false)
        }
    }

    /**
     * 送入一帧 H264 数据
     *
     * 帧率控制：如果距离上一帧不足 FRAME_INTERVAL_NS，直接丢弃。
     */
    fun feedFrame(data: ByteArray) {
        // 帧率控制
        val now = System.nanoTime()
        if (now - lastFrameTimeNs < FRAME_INTERVAL_NS) {
            return  // 丢帧：超过帧率上限
        }
        lastFrameTimeNs = now

        if (!codecReady.get()) {
            // 尝试在关键帧时初始化
            return
        }
        decoderHandler.post {
            doFeedFrame(data)
        }
    }

    /** 实际喂帧（在 decoder 线程执行） */
    private fun doFeedFrame(data: ByteArray) {
        val codec = mediaCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val buf = codec.getInputBuffer(inputIndex) ?: return
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(inputIndex, 0, data.size, System.nanoTime() / 1000, 0)
            } else {
                Log.w(TAG, "输入缓冲已满，丢帧")
            }
        } catch (e: Exception) {
            Log.w(TAG, "喂帧失败: ${e.message}")
        }
    }

    /**
     * Drain 输出缓冲（由定时器独立调用）
     *
     * 与 feedFrame 解耦，避免互相阻塞。
     */
    private fun drainOutputBuffers() {
        val codec = mediaCodec ?: return
        if (!codecReady.get()) return
        try {
            var count = 0
            val info = MediaCodec.BufferInfo()
            var index = codec.dequeueOutputBuffer(info, 0)
            while (index >= 0 && count < 5) {  // 每轮最多 drain 5 帧
                codec.releaseOutputBuffer(index, true)
                index = codec.dequeueOutputBuffer(info, 0)
                count++
            }
        } catch (e: Exception) {
            // MediaCodec 在未 start 时可能抛 IllegalStateException，忽略
        }
    }

    /** 释放编解码器 */
    private fun releaseCodec() {
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null
        codecReady.set(false)
    }

    /** 释放所有资源 */
    fun release() {
        decoderHandler.removeCallbacks(drainRunnable)
        decoderHandler.post {
            releaseCodec()
            surface?.release()
            surface = null
        }
        decoderThread.quitSafely()
    }
}
