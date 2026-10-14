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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.FtpChannel
import com.qtjay.syncscope.channel.RestChannel
import com.qtjay.syncscope.channel.ShareChannel
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.channel.TestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Mode { REST, FTP, LOCAL }

/**
 * 首屏：选择连接方式。三类通道各有长短，后续会支持同时连接、互补短板。
 *   - REST：能看到远端未下载的文件、可触发重扫，但删不了文件
 *   - FTP：能写 .stignore 并删除文件，但看不到未下载的内容、不能触发重扫
 *   - 本地目录：通过系统文件选择器访问已挂载的目录，能力同 FTP
 *
 * 连接结果一定会显示：成功给出摘要，失败给出可读原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(onConnected: (SyncChannel) -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Mode.REST) }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8384") }
    var apiKey by remember { mutableStateOf("") }
    var useHttps by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remotePath by remember { mutableStateOf("") }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    val ready = when (mode) {
        Mode.REST -> address.isNotBlank()
        Mode.FTP -> address.isNotBlank()
        Mode.LOCAL -> shareUri != null
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            shareUri = uri
            status = null
        }
    }

    fun reset() {
        status = null
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("REST", mode == Mode.REST, Modifier.weight(1f)) {
                    mode = Mode.REST
                    port = "8384"
                    reset()
                }
                ModeButton("FTP", mode == Mode.FTP, Modifier.weight(1f)) {
                    mode = Mode.FTP
                    port = "21"
                    reset()
                }
                ModeButton("本地目录", mode == Mode.LOCAL, Modifier.weight(1f)) {
                    mode = Mode.LOCAL
                    reset()
                }
            }

            when (mode) {
                Mode.REST -> {
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
                        visualTransformation = PasswordVisualTransformation(),
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

                Mode.FTP -> {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("FTP 地址") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("用户名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { remotePath = it },
                        label = { Text("文件夹路径（如 /volume1/sync）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "填目标共享文件夹所在的绝对路径。匿名登录请把用户名填 anonymous。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Mode.LOCAL -> {
                    Text(
                        "选择目标文件夹在本机的挂载点（由你自行挂载 SMB/FTP 后在此选取）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { launcher.launch(null) }) { Text("选择共享目录") }
                    Text(
                        shareUri?.toString() ?: "尚未选择",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = {
                    reset()
                    testing = true
                    scope.launch {
                        val target: SyncChannel? = when (mode) {
                            Mode.REST -> RestChannel(
                                scheme = if (useHttps) "https" else "http",
                                host = address.trim(),
                                port = port.trim().toIntOrNull() ?: 8384,
                                apiKey = apiKey.trim(),
                            )

                            Mode.FTP -> FtpChannel(
                                host = address.trim(),
                                port = port.trim().toIntOrNull() ?: 21,
                                user = user.trim(),
                                password = password,
                                remotePath = remotePath.trim(),
                            )

                            Mode.LOCAL -> shareUri?.let {
                                ShareChannel(context.applicationContext, it)
                            }
                        }
                        if (target == null) {
                            status = TestResult.fail("请先填写必要信息或选择目录")
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
                if (!okNow && mode == Mode.REST) {
                    Text(
                        "提示：REST 模式下目标客户端必须开启「Web UI 远程访问」，" +
                            "且手机与它处于同一网络。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!okNow && mode == Mode.FTP) {
                    Text(
                        "提示：请确认 FTP 服务已开启、端口正确，且路径用的是绝对路径。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}
