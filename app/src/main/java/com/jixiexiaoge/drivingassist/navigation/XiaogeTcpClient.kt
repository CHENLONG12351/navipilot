package com.jixiexiaoge.drivingassist.navigation

import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.json.JSONObject
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer

/**
 * XiaogeTcpClient — 连接到 CarrotPilot 7711 TCP 端口接收 xiaoge 车辆数据
 *
 * 对应服务端 xiaoge_data.py 的 TCP 广播协议：
 * 1. 先接收 4 字节长度头 (big-endian uint32)
 * 2. 再接收 N 字节 JSON 数据
 *
 * 数据格式：
 * { "version":1, "sequence":N, "timestamp":..., "ip":"...",
 *   "data": { "carState":{...}, "modelV2":{...}, "systemState":{...} } }
 */
class XiaogeTcpClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        private const val TAG = "XiaogeTcp"
        private const val TCP_PORT = 7711
        private const val RECONNECT_DELAY_MS = 3000L
    }

    private var socket: Socket? = null
    private var receiveJob: Job? = null
    private var connectJob: Job? = null
    private var targetHost: String = ""

    /** 最新收到的完整数据包 JSON */
    @Volatile
    var latestPacket: JSONObject? = null
        private set

    /** 最新收到的 data 字段 JSON */
    @Volatile
    var latestData: JSONObject? = null
        private set

    /** 是否已连接 */
    @Volatile
    var isConnected: Boolean = false
        private set

    /** 连接统计 */
    @Volatile
    var packetsReceived: Long = 0
        private set

    /** 最后收到数据的时间戳 */
    @Volatile
    var lastReceiveTimeMs: Long = 0L
        private set

    /**
     * 设置目标 IP 并开始连接
     */
    fun start(host: String) {
        targetHost = host
        connectJob?.cancel()
        connectJob = scope.launch {
            connect()
        }
    }

    /**
     * 停止连接
     */
    fun stop() {
        connectJob?.cancel()
        receiveJob?.cancel()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        isConnected = false
    }

    private suspend fun connect() {
        while (coroutineContext.isActive) {
            try {
                Log.i(TAG, "连接 TCP $targetHost:$TCP_PORT")
                val sock = Socket(targetHost, TCP_PORT)
                sock.soTimeout = 10000
                socket = sock
                isConnected = true
                Log.i(TAG, "TCP 已连接 $targetHost:$TCP_PORT")
                receiveLoop(sock)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "TCP 连接失败: ${e.message}，${RECONNECT_DELAY_MS}ms 后重试")
                isConnected = false
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun receiveLoop(sock: Socket) {
        receiveJob = scope.launch {
            try {
                val input: InputStream = sock.getInputStream()
                val lenBuf = ByteArray(4)
                while (isActive && !sock.isClosed) {
                    // 读取 4 字节长度头
                    readExact(input, lenBuf)
                    val dataLen = ByteBuffer.wrap(lenBuf).getInt()
                    if (dataLen <= 0 || dataLen > 5 * 1024 * 1024) continue

                    // 读取 JSON 数据
                    val dataBuf = ByteArray(dataLen)
                    readExact(input, dataBuf)
                    val jsonStr = String(dataBuf, Charsets.UTF_8)

                    // 解析
                    val packet = JSONObject(jsonStr)
                    latestPacket = packet
                    latestData = packet.optJSONObject("data")
                    packetsReceived++
                    lastReceiveTimeMs = System.currentTimeMillis()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "接收循环结束: ${e.message}")
            } finally {
                isConnected = false
                try { sock.close() } catch (_: Exception) {}
            }
        }
        receiveJob?.join()
    }

    private fun readExact(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n == -1) throw java.io.EOFException("连接已关闭")
            offset += n
        }
    }
}
