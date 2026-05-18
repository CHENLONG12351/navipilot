package com.example.navipilot.ui.components

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.navipilot.AmapProjectionManager

/**
 * 高德地图车机版画面投射视图
 *
 * 使用 Android MediaProjection API 实时显示高德车机版导航画面。
 * 仅显示，不可交互。交互操作通过高德自身 UI 或广播通道完成。
 *
 * 处理了两个关键时序问题：
 * 1. Surface 就绪但未授权 → 缓存 Surface，授权后自动绑定
 * 2. 授权后 Activity 重建/恢复 → 通过 isAuthorized 状态变化触发重新绑定
 *
 * @param projectionManager 投射管理器实例
 * @param startAmap          启动高德车机版的回调
 * @param modifier           Compose 修饰符
 */
@androidx.compose.runtime.Composable
fun AmapProjectionView(
    projectionManager: AmapProjectionManager?,
    startAmap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // 追踪授权状态变化（isAuthorized 是 Volatile 非 Compose State，需要自行追踪）
    var lastAuthState by remember { mutableStateOf(projectionManager?.isAuthorized ?: false) }

    // 保存 SurfaceView 引用，用于授权后重新绑定
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }

    // 轮询授权状态变化（因为 isAuthorized 不是 Compose State）
    LaunchedEffect(projectionManager) {
        while (true) {
            val currentAuth = projectionManager?.isAuthorized ?: false
            if (currentAuth != lastAuthState) {
                lastAuthState = currentAuth
                if (currentAuth) {
                    // 授权状态变为 true → 触发重新绑定
                    Log.i("AmapProjectionView", "🔑 授权状态变为已授权，尝试绑定 Surface")
                    val sv = surfaceViewRef
                    if (sv != null && sv.holder.surface.isValid) {
                        val metrics = sv.resources.displayMetrics
                        projectionManager?.bindSurface(
                            sv.holder.surface,
                            sv.width.coerceAtLeast(640),
                            sv.height.coerceAtLeast(480),
                            metrics.densityDpi
                        )
                    } else {
                        Log.d("AmapProjectionView", "⏳ Surface 不可用，由 surfaceCreated 接管")
                    }
                }
            }
            kotlinx.coroutines.delay(500) // 每 500ms 检查一次
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also { sv ->
                surfaceViewRef = sv
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        Log.i("AmapProjectionView", "Surface 已创建")
                        if (projectionManager?.isAuthorized == true) {
                            val metrics = sv.resources.displayMetrics
                            projectionManager?.bindSurface(
                                holder.surface,
                                sv.width.coerceAtLeast(640),
                                sv.height.coerceAtLeast(480),
                                metrics.densityDpi
                            )
                        } else {
                            Log.d("AmapProjectionView", "⏳ Surface 已创建但未授权，等待授权")
                        }
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        w: Int,
                        h: Int
                    ) {
                        Log.i("AmapProjectionView", "Surface 尺寸变化: ${w}x${h}")
                        if (projectionManager?.isProjecting == true) {
                            val metrics = sv.resources.displayMetrics
                            projectionManager?.resizeSurface(
                                holder.surface, w, h, metrics.densityDpi
                            )
                        }
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        Log.i("AmapProjectionView", "Surface 已销毁")
                    }
                })
            }
        },
        modifier = modifier
    )
}
