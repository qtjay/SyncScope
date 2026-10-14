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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首屏：选择连接方式。
 *   - REST API：功能完整，能看到远端未下载的文件，可主动触发重新扫描
 *   - 共享目录：SMB/FTP 由用户自行准备，只能看到本机已存在的文件
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(onConnected: (SyncChannel) -> Unit) {
    val context = LocalContext.current
    var useShare by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8384") }
    var apiKey by remember { mutableStateOf("") }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    var testing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            shareUri = uri
            error = null
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
                Text(if (useShare) "共享目录 (SMB/FTP)" else "REST API")
                Spacer(Modifier.weight(1f))
                Switch(checked = useShare, onCheckedChange = { useShare = it })
            }

            if (useShare) {
                Text("由你在目标设备上开启 SMB/FTP 服务，再在此选择对应目录。")
                Button(onClick = { launcher.launch(null) }) { Text("选择共享目录") }
                Text(shareUri?.toString() ?: "尚未选择",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(value = address, onValueChange = { address = it },
                    label = { Text("地址") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it },
                    label = { Text("端口") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
                Text("需在目标客户端开启 Web UI 远程访问，并填入 API key。",
                    style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    testing = true
                    scope.launch {
                        val created: SyncChannel? = if (useShare) {
                            shareUri?.let { ShareChannel(context.applicationContext, it) }
                        } else {
                            RestChannel(
                                scheme = "http",
                                host = address.trim(),
                                port = port.trim().toIntOrNull() ?: 8384,
                                apiKey = apiKey.trim()
                            )
                        }
                        val ok = created != null && withContext(
                            kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { created.test() != null }.getOrDefault(false)
                        }
                        testing = false
                        if (ok && created != null) {
                            onConnected(created)
                        } else {
                            error = "连接失败，请检查地址与 API key"
                    }
                }
                },
                enabled = !testing && (if (useShare) shareUri != null else address.isNotBlank()),
            ) {
                Text(if (testing) "测试中…" else "连接")
            }
        }
    }
}
