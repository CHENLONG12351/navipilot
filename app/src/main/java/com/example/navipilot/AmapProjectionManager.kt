package com.example.navipilot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 管理高德地图车机版画面投射的完整生命周期。
 *
 * 流程：
 *   requestCapture()
 *     → 系统弹出"选择要投射的应用"授权框
 *     → 用户在框中选择高德地图车机版
 *     → onCaptureAuthorized(resultCode, data)
 *       → startForegroundService()
 *       → getMediaProjection()
 *       → (等待 SurfaceView 就绪) → bindSurface() → createVirtualDisplay()
 *
 * 注意：Android 14+ 引入的"单 APP 画面共享"可以让用户只选择高德，
 * 不会投射系统状态栏/通知栏，画面干净。
 */
class AmapProjectionManager(private val activity: ComponentActivity) {

    companion object {
        private const val TAG = "AmapProjection"
        private const val VIRTUAL_DISPLAY_NAME = "NavipilotAmapCapture"
    }

    private val mpm: MediaProjectionManager =
        activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    /** 缓存的 Surface，授权完成后自动绑定 */
    private var pendingSurface: Surface? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0
    private var pendingDensityDpi: Int = 160

    /** 是否正在投射 */
    @Volatile
    var isProjecting: Boolean = false
        private set

    /** 授权回调锁 — 防止重复启动授权流程 */
    private var authorizationLaunched = false

    // ===================== 回调接口 =====================

    /** Surface 可用时通知 Compose 层绑定 */
    var onSurfaceReady: ((Surface) -> Unit)? = null

    /** 投射停止时通知 Compose 层清理 */
    var onProjectionStopped: (() -> Unit)? = null

    /** 授权状态变化回调 */
    var onAuthorizationResult: ((Boolean) -> Unit)? = null

    // ===================== 授权 Launcher（构造函数中注册一次）=====================

    /**
     * MediaProjection 授权结果 Launcher。
     *
     * 重要：registerForActivityResult 必须在 Activity 的 CREATED 状态之前注册，
     * 因此在构造时创建，后续仅调用 launch()。
     */
    private val captureLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authorizationLaunched = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "✅ 用户已授权屏幕捕获")
            onCaptureAuthorized(result.resultCode, result.data!!)
            onAuthorizationResult?.invoke(true)
        } else {
            Log.w(TAG, "❌ 用户拒绝或取消屏幕捕获授权")
            onAuthorizationResult?.invoke(false)
        }
    }

    // ===================== 公共 API =====================

    /** 是否已获得授权 */
    val isAuthorized: Boolean
        get() = mediaProjection != null

    /**
     * 发起捕获请求（弹出系统授权框）。
     *
     * Android 14+ 会在授权框中让用户选择"仅投射一个应用"还是"投射整个屏幕"。
     * 用户选择"单个应用"后，选高德地图即可。
     */
    fun requestCapture() {
        if (authorizationLaunched) {
            Log.d(TAG, "⏭️ 授权流程已在进行中")
            return
        }
        authorizationLaunched = true

        // 提前启动前台服务，确保 getMediaProjection() 能检测到
        // Android 14+ 要求：getMediaProjection() 调用时，
        // 必须已有一个 foreground service type=mediaProjection 在运行
        val svcIntent = Intent(activity, AmapProjectionService::class.java)
        ContextCompat.startForegroundService(activity, svcIntent)

        val intent = mpm.createScreenCaptureIntent()
        captureLauncher.launch(intent)
    }

    /**
     * 绑定 VirtualDisplay 到 Surface（由 SurfaceView 的 surfaceCreated 回调调用）。
     *
     * 如果尚未授权，缓存 Surface，授权后自动绑定。
     *
     * @param surface     SurfaceView 的 Surface
     * @param width       显示宽度（px）
     * @param height      显示高度（px）
     * @param densityDpi  屏幕密度
     */
    fun bindSurface(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        if (mediaProjection == null) {
            // 缓存 Surface，授权后自动绑定
            pendingSurface = surface
            pendingWidth = width.coerceAtLeast(640)
            pendingHeight = height.coerceAtLeast(480)
            pendingDensityDpi = densityDpi
            Log.d(TAG, "⏳ 尚未授权，已缓存 Surface: ${pendingWidth}x${pendingHeight}")
            return
        }

        // 释放旧的 VirtualDisplay（如果有）
        if (virtualDisplay != null) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        val effectiveW = width.coerceAtLeast(640)
        val effectiveH = height.coerceAtLeast(480)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            effectiveW,
            effectiveH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,  // callback
            null   // handler
        )

        isProjecting = true
        pendingSurface = null  // 清除缓存
        Log.i(TAG, "✅ VirtualDisplay 已创建: ${effectiveW}x${effectiveH} @ ${densityDpi}dpi")
    }

    /**
     * 更新 VirtualDisplay 尺寸（Surface 尺寸变化时调用）。
     */
    fun resizeSurface(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        if (!isProjecting) return
        bindSurface(surface, width, height, densityDpi)
    }

    /** 释放所有资源 */
    fun release() {
        Log.i(TAG, "🧹 释放 MediaProjection 资源")
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {}
        virtualDisplay = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        mediaProjection = null

        isProjecting = false
        onProjectionStopped?.invoke()
    }

    // ===================== 内部方法 =====================

    private fun onCaptureAuthorized(resultCode: Int, data: Intent) {
        // 前台服务已在 requestCapture() 中提前启动，这里不再重复启动

        // 1. 获取 MediaProjection token
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        // 3. 注册回调（监听投射状态）
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection 已停止（系统回调）")
                // 由外部调用 release() 清理
            }
        }, null)

        Log.i(TAG, "✅ MediaProjection 授权成功，等待 Surface 绑定")

        // 4. 如果有缓存的 Surface，立即绑定
        val surf = pendingSurface
        if (surf != null) {
            Log.i(TAG, "📦 检测到缓存的 Surface，立即绑定: ${pendingWidth}x${pendingHeight}")
            bindSurface(surf, pendingWidth, pendingHeight, pendingDensityDpi)
        }
    }
}
