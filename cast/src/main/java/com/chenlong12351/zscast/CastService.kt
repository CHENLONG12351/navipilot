package com.chenlong12351.zscast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 专属投屏:手机屏幕 → H.264 → C3 carrot_navi 接收服务(ws :7714)
 * 协议: CarrotNavi WebSocket v2(CNV2 二进制帧)
 */
class CastService : Service() {

    companion object {
        const val ACTION_START = "com.chenlong12351.zscast.START"
        const val ACTION_STOP = "com.chenlong12351.zscast.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_RESULT_CODE = "resultCode"

        val state = MutableStateFlow("idle")

        var pendingHost: String = ""
        var pendingResultCode: Int = 0
        var pendingData: Intent? = null

        fun start(ctx: Context, host: String, resultCode: Int, data: Intent) {
            pendingHost = host
            pendingResultCode = resultCode
            pendingData = data
            ctx.startForegroundService(
                Intent(ctx, CastService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CastService::class.java).setAction(ACTION_STOP))
        }

        // carrot_navi.py 的 28 项目录(negotiate 强校验)
        private val JSON_NAMES = listOf(
            "vehicle", "guidance_current", "guidance_next", "lane_current",
            "lane_ahead", "speed", "traffic_signal", "crossroad", "route",
            "navigation_status", "app_status", "camera_state", "composition_state",
        )
        private val IMAGE_NAMES = listOf(
            "tbt_current_compact", "tbt_current_full", "tbt_next",
            "traffic_signal", "lane_top", "lane_bottom",
            "safety_primary", "safety_secondary", "safety_section",
            "crossroad_minimized", "crossroad_expanded",
            "center_tbt_icon", "center_tbt_text", "center_tbt_fee",
        )

        private fun requirementsQuery(): String {
            val streams = JSONArray()
            for (n in JSON_NAMES) streams.put(streamEntry("json", n))
            for (n in IMAGE_NAMES) streams.put(streamEntry("image", n))
            streams.put(streamEntry("render", "map_main"))
            return JSONObject()
                .put("type", "requirements_query")
                .put("protocol_version", 2)
                .put("catalog_revision", 1)
                .put("streams", streams)
                .toString()
        }

        private fun streamEntry(kind: String, name: String): JSONObject =
            JSONObject().put("kind", kind).put("name", name).put("schema_version", 1)

        /** CNV2 固定 40 字节头 + Annex-B 载荷 */
        fun cnv2Frame(
            messageType: Int, format: Int, flags: Int,
            handle: Int, revision: Int, seq: Long, tsMs: Long,
            payload: ByteArray, width: Int, height: Int,
        ): ByteArray {
            val buf = ByteBuffer.allocate(40 + payload.size).order(ByteOrder.BIG_ENDIAN)
            buf.put("CNV2".toByteArray(Charsets.US_ASCII))
            buf.put(2)              // protocol_version
            buf.put(messageType)    // 2=keyframe 3=delta
            buf.put(format)         // 3=Annex-B
            buf.put(flags)          // 1=keyframe
            buf.putInt(handle)
            buf.putInt(revision)
            buf.putLong(seq)
            buf.putLong(tsMs)
            buf.putInt(payload.size)
            buf.putShort(width)
            buf.putShort(height)
            buf.put(payload)
            return buf.array()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var castJob: Job? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var controlWs: WebSocket? = null
    private var renderWs: WebSocket? = null
    private val seq = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); stopSelf() }
            ACTION_START -> {
                val host = pendingHost
                val code = pendingResultCode
                val data = pendingData
                if (host.isBlank() || data == null) {
                    state.value = "参数缺失"
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground()
                castJob?.cancel()
                castJob = scope.launch { runCast(host, code, data) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel("cast") == null) {
            nm.createNotificationChannel(
                NotificationChannel("cast", "专属投屏", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = Notification.Builder(this, "cast")
            .setContentTitle("专属投屏")
            .setContentText("正在投屏到 C3")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private suspend fun runCast(host: String, resultCode: Int, data: Intent) {
        val client = OkHttpClient.Builder()
            .pingInterval(10, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
        try {
            state.value = "连接 $host:7714 …"
            // 1) control 通道 + 会话协商
            val manifest = CompletableDeferred<JSONObject>()
            controlWs = client.newWebSocket(
                Request.Builder().url("ws://$host:7714/api/navi/ws/v2/control/zscast-1").build(),
                object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        ws.send(requirementsQuery())
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        val obj = JSONObject(text)
                        if (obj.optString("type") == "subscription_manifest") {
                            manifest.complete(obj)
                        }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        manifest.completeExceptionally(t)
                    }
                })
            val mf = withTimeoutOrNull(10_000) { manifest.await() }
                ?: throw RuntimeException("连接 C3 超时,检查 IP 和 carrot_navi 服务")
            val sessionId = mf.getString("session_id")
            val revision = mf.getInt("revision")

            // manifest_applied 回执(record_control 要求带 protocol_version)
            controlWs?.send(
                JSONObject().put("type", "manifest_applied")
                    .put("protocol_version", 2)
                    .put("session_id", sessionId)
                    .put("revision", revision)
                    .toString()
            )

            // 2) 找 render/map_main 流
            val streams = mf.getJSONArray("streams")
            var handle = 0
            var W = 960; var H = 540; var fps = 10; var bitrate = 3_000_000; var ifi = 2.0
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                if (s.getString("kind") == "render" && s.getString("name") == "map_main") {
                    handle = s.getInt("stream_handle")
                    val p = s.getJSONObject("params")
                    W = p.getInt("width"); H = p.getInt("height")
                    fps = p.optInt("fps", 10)
                    bitrate = p.optInt("h264_bitrate_kbps", 3000) * 1000
                    ifi = p.optDouble("h264_keyframe_interval_sec", 2.0)
                    break
                }
            }
            if (handle <= 0) throw RuntimeException("manifest 中没有 render/map_main 流")

            // 3) render 二进制通道
            state.value = "打开视频通道…"
            val renderReady = CompletableDeferred<Unit>()
            renderWs = client.newWebSocket(
                Request.Builder().url("ws://$host:7714/api/navi/ws/v2/render/$sessionId/map_main").build(),
                object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) { renderReady.complete(Unit) }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        renderReady.completeExceptionally(t)
                    }
                })
            withTimeoutOrNull(10_000) { renderReady.await() }
                ?: throw RuntimeException("视频通道打开失败")

            // 4) MediaProjection + 硬编码推流
            state.value = "启动编码器…"
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data).also { proj ->
                proj.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { state.value = "投屏已停止"; scope.launch { shutdown() } }
                }, Handler(Looper.getMainLooper()))
            }

            val c = MediaCodec.createEncoderByType("video/avc")
            val fmt = MediaFormat.createVideoFormat("video/avc", W, H).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ifi.toInt())
            }
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = c.createInputSurface()
            var csd = ByteArray(0)
            c.setCallback(object : MediaCodec.Callback() {
                override fun onOutputBufferAvailable(
                    codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo,
                ) {
                    try {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size).also { buf.get(it) }
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                csd = chunk // SPS/PPS
                            } else {
                                val key = info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
                                val payload = if (key && csd.isNotEmpty()) csd + chunk else chunk
                                val frame = cnv2Frame(
                                    if (key) 2 else 3, 3, if (key) 1 else 0,
                                    handle, revision, seq.incrementAndGet(),
                                    System.currentTimeMillis(), payload, W, H,
                                )
                                renderWs?.send(frame.toByteString())
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e("zscast", "encode out", e)
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    state.value = "编码错误: ${e.message}"
                }
            }, Handler(Looper.getMainLooper()))
            display = projection?.createVirtualDisplay(
                "zscast", W, H, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            )
            c.start()
            codec = c

            state.value = "投屏中 ${W}x${H}@${fps}fps"
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("zscast", "cast failed", e)
            state.value = "失败: ${e.message}"
            withContext(NonCancellable) { shutdown() }
            stopSelf()
        }
    }

    private suspend fun shutdown() {
        withContext(NonCancellable) {
            try { display?.release() } catch (_: Exception) {}
            display = null
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
            try { projection?.stop() } catch (_: Exception) {}
            projection = null
            try { renderWs?.close(1000, "bye") } catch (_: Exception) {}
            renderWs = null
            try { controlWs?.close(1000, "bye") } catch (_: Exception) {}
            controlWs = null
            if (state.value.startsWith("投屏中") || state.value.startsWith("失败")) {
                state.value = "idle"
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        castJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
