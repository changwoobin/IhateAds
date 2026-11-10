package com.example.ihateadsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ihateadsapp.ui.theme.IhateAdsAppTheme

private data class ListedApp(val packageName: String, val name: String)

class MainActivity : ComponentActivity() {
    private var apps by mutableStateOf<List<ListedApp>>(emptyList())
    private var selected by mutableStateOf<Set<String>>(emptySet())
    private var active by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var requesting by mutableStateOf(false)

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        requesting = false
        if (it.resultCode == RESULT_OK) startBlocking()
        else message = "VPN 연결 권한이 필요합니다."
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            active = BlockSettings.active(this@MainActivity)
            if (BlockSettings.wanted(this@MainActivity) && !active && !requesting)
                message = "차단이 중단되었습니다. VPN 연결을 확인해 주세요."
            else if (active) message = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = installedUserApps()
        selected = BlockSettings.packages(this).intersect(apps.map { it.packageName }.toSet())
        BlockSettings.savePackages(this, selected)
        active = BlockSettings.active(this)
        setContent {
            IhateAdsAppTheme {
                AppScreen(apps, selected, active, requesting, message, ::toggleApp,
                    ::requestVpn, ::stopBlocking, packageManager)
            }
        }
        if (BlockSettings.wanted(this) && selected.isNotEmpty() && !active) requestVpn()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(BlockSettings.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        active = BlockSettings.active(this)
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun installedUserApps(): List<ListedApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33)
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return activities.mapNotNull { activity ->
            val info = activity.activityInfo.applicationInfo
            if (info.packageName == packageName || info.flags and ApplicationInfo.FLAG_SYSTEM != 0) null
            else ListedApp(info.packageName, packageManager.getApplicationLabel(info).toString())
        }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }

    private fun toggleApp(packageName: String) {
        selected = if (packageName in selected) selected - packageName else selected + packageName
        BlockSettings.savePackages(this, selected)
        if (active) {
            if (selected.isEmpty()) stopBlocking()
            else startService(Intent(this, BlockingVpnService::class.java))
        }
    }

    private fun requestVpn() {
        if (selected.isEmpty()) return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent == null) startBlocking()
        else {
            requesting = true
            vpnPermission.launch(permissionIntent)
        }
    }

    private fun startBlocking() {
        if (selected.isEmpty()) return
        BlockSettings.setWanted(this, true)
        message = null
        try {
            startForegroundService(Intent(this, BlockingVpnService::class.java))
        } catch (_: Exception) {
            BlockSettings.setActive(this, false)
            message = "차단 서비스를 시작할 수 없습니다."
        }
    }

    private fun stopBlocking() {
        BlockSettings.setWanted(this, false)
        startService(Intent(this, BlockingVpnService::class.java).setAction(BlockingVpnService.ACTION_STOP))
        active = false
        message = null
    }
}

@Composable
private fun AppScreen(
    apps: List<ListedApp>,
    selected: Set<String>,
    active: Boolean,
    requesting: Boolean,
    message: String?,
    onToggle: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    packageManager: PackageManager
) {
    Scaffold(containerColor = Color(0xFFF8FAF9)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(30.dp))
            Text("네트워크 차단", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("연결을 막을 앱을 선택하세요", color = Color(0xFF586460))
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(18.dp)) {
                Text(
                    if (requesting) "권한 요청 중" else if (active) "차단 중"
                    else if (message != null) "오류" else "차단 안 함",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (active) Color(0xFF176B54) else Color(0xFF20312B)
                )
                Spacer(Modifier.height(5.dp))
                Text(message ?: "선택한 앱 ${selected.size}개", color = Color(0xFF586460))
                Spacer(Modifier.height(16.dp))
                if (active) OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                    Text("차단 해제")
                } else Button(onClick = onStart, enabled = selected.isNotEmpty() && !requesting,
                    modifier = Modifier.fillMaxWidth()) { Text("차단 시작") }
            }
            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("앱 선택", style = MaterialTheme.typography.titleMedium)
                Text("${selected.size}개 선택", color = Color(0xFF586460))
            }
            Spacer(Modifier.height(10.dp))
            if (apps.isEmpty()) Text("표시할 사용자 설치 앱이 없습니다.", color = Color(0xFF586460))
            else LazyColumn {
                items(apps, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().clickable { onToggle(app.packageName) }
                        .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        AndroidView(
                            factory = { context -> ImageView(context) },
                            update = { image -> image.setImageDrawable(
                                runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                            ) },
                            modifier = Modifier.size(40.dp)
                        )
                        Text(app.name, Modifier.weight(1f).padding(start = 14.dp),
                            style = MaterialTheme.typography.bodyLarge)
                        Checkbox(checked = app.packageName in selected,
                            onCheckedChange = { onToggle(app.packageName) })
                    }
                    HorizontalDivider(color = Color(0xFFE8EEEA))
                }
            }
        }
    }
}
