package com.chenlong12351.zscast

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var host: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = getSharedPreferences("zscast", MODE_PRIVATE).getString("host", "") ?: ""

        setContent {
            MaterialTheme {
                Screen()
            }
        }
    }

    @Composable
    private fun Screen() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        var input by remember { mutableStateOf(host) }
        val state by CastService.state.collectAsState()
        val casting = state.startsWith("投屏中") || state.startsWith("启动") || state.startsWith("打开")

        val projectionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                CastService.start(this@MainActivity, input.trim(), res.resultCode, res.data!!)
            } else {
                CastService.state.value = "未授权屏幕录制"
            }
        }

        val notifLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { } // 拒绝也不影响投屏,只是没有常驻通知

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Text("专属投屏", fontSize = 28.sp)
            Text(
                "手机屏幕 → C3(carrot_navi :7714)",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("C3 地址(IP)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Text("状态:$state", fontSize = 14.sp)

            Button(
                onClick = {
                    if (input.isBlank()) {
                        CastService.state.value = "先填 C3 的 IP"
                        return@Button
                    }
                    getSharedPreferences("zscast", MODE_PRIVATE)
                        .edit().putString("host", input.trim()).apply()
                    host = input.trim()
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                },
                enabled = !casting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (casting) "投屏运行中" else "开始投屏", fontSize = 18.sp) }

            OutlinedButton(
                onClick = { CastService.stop(this@MainActivity) },
                enabled = casting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("停止投屏", fontSize = 18.sp) }

            Spacer(Modifier.height(8.dp))
            Text(
                "用法:先打开高德导航进入导航界面,\n再点「开始投屏」并允许屏幕录制。\n停止可直接点按钮或下拉状态栏停止。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
