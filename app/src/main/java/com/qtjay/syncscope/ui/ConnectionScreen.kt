package com.qtjay.syncscope.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.RestChannel
import com.qtjay.syncscope.channel.ShareChannel
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.channel.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首屏：选择连接方式。
 *   - REST API：功能完整，能看到远端未下载的文件，可主动触发重新扫描（但删不了文件）
 *   - 本地共享目录：只能看到已存在的文件，却能真正写 .stignore 并删除文件
 *
 * 连接结果一定会显示出来：成功给出设备 ID / 目录名，失败给出可读原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(onConnected: (SyncChannel) -> Unit) {
    val context = LocalContext.current
    var useShare by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8384") }
    var apiKey by remember { mutableStateOf("") }
    var useHttps by remember { mutableStateOf(false) }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    val ready = if (useShare) shareUri != null else address.isNotBlank()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            shareUri = uri
            status = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SyncScope") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (useShare) "本地共享目录" else "REST API")
                Spacer(Modifier.weight(1f))
                Switch(checked = useShare, onCheckedChange = {
                    useShare = it
                    status = null
                })
            }

            if (useShare) {
                Text(
                    "选择目标文件夹在本机的挂载点（SMB / FTP 通道开发中，届时可直接填服务器地址）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { launcher.launch(null) }) { Text("选择共享目录") }
                Text(
                    shareUri?.toString() ?: "尚未选择",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("地址") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用 HTTPS")
                    Spacer(Modifier.weight(1f))
                    Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                }
                Text(
                    "需在目标客户端开启 Web UI 远程访问，并填入 API key。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                onClick = {
                    status = null
                    testing = true
                    scope.launch {
                        val target: SyncChannel? = if (useShare) {
                            shareUri?.let { ShareChannel(context.applicationContext, it) }
                        } else {
                            RestChannel(
                                scheme = if (useHttps) "https" else "http",
                                host = address.trim(),
                                port = port.trim().toIntOrNull() ?: 8384,
                                apiKey = apiKey.trim(),
                            )
                        }
                        if (target == null) {
                            status = TestResult.fail(
                                if (useShare) "请先选择一个目录" else "请填写地址",
                            )
                            testing = false
                            return@launch
                        }
                        val result = withContext(Dispatchers.IO) {
                            runCatching { target.test() }
                                .getOrElse { e ->
                                    TestResult.fail(
                                        e.message ?: "连接失败：${e.javaClass.simpleName}",
                                    )
                                }
                        }
                        testing = false
                        status = result
                        if (result.ok) {
                            onConnected(target)
                        }
                    }
                },
                enabled = !testing && ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "测试中…" else "连接")
            }

            // 结果区：成功与失败都必须让用户看见
            val shown = status
            if (testing) {
                Text(
                    "正在连接…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (shown != null) {
                val okNow = shown.ok
                Text(
                    text = if (okNow) "✓ ${shown.summary}" else "✗ ${shown.error}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (okNow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!okNow) {
                    Text(
                        "提示：REST 模式下目标客户端必须开启「Web UI 远程访问」，" +
                            "且手机与它处于同一网络。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
