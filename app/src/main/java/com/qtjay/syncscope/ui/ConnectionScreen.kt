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
import androidx.compose.material3.TextButton
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
import com.qtjay.syncscope.channel.SmbChannel
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.channel.TestResult
import com.qtjay.syncscope.data.ClientProfile
import com.qtjay.syncscope.data.ProfileKind
import com.qtjay.syncscope.data.ProfileRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Mode { REST, FTP, SMB, LOCAL }

/**
 * 首屏：选择连接方式。
 *
 * 两类通道各有长短：
 *   - 信息面（REST）：能看到远端未下载的文件、可触发重扫，但 API 没有删除接口
 *   - 动作面（FTP / SMB / 本地目录）：能写 .stignore 并真正删除文件，
 *     但看不到未下载的内容、也不能触发重扫
 *
 * 连接结果一定会显示：成功给出摘要，失败给出可读原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    title: String = "SyncScope",
    onCancel: (() -> Unit)? = null,
    onConnected: (SyncChannel, ClientProfile) -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Mode.REST) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8384") }
    var apiKey by remember { mutableStateOf("") }
    var useHttps by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var shareName by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var remotePath by remember { mutableStateOf("") }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    val ready = when (mode) {
        Mode.REST -> address.isNotBlank()
        Mode.FTP -> address.isNotBlank()
        Mode.SMB -> address.isNotBlank() && shareName.isNotBlank()
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

    fun switchTo(next: Mode, defaultPort: String) {
        mode = next
        port = defaultPort
        status = null
    }

    Scaffold(
        topBar = {
            if (onCancel != null) {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        TextButton(onClick = onCancel) { Text("返回") }
                    },
                )
            } else {
                TopAppBar(title = { Text(title) })
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeButton("REST", mode == Mode.REST, Modifier.weight(1f)) {
                    switchTo(Mode.REST, "8384")
                }
                ModeButton("FTP", mode == Mode.FTP, Modifier.weight(1f)) {
                    switchTo(Mode.FTP, "21")
                }
                ModeButton("SMB", mode == Mode.SMB, Modifier.weight(1f)) {
                    switchTo(Mode.SMB, "445")
                }
                ModeButton("本地", mode == Mode.LOCAL, Modifier.weight(1f)) {
                    switchTo(Mode.LOCAL, port)
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称（可选，便于区分多个设备）") },
                modifier = Modifier.fillMaxWidth(),
            )

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
                        "填目标共享文件夹的绝对路径。匿名登录请把用户名填 anonymous。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Mode.SMB -> {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("SMB 地址") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("端口") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = shareName,
                        onValueChange = { shareName = it },
                        label = { Text("共享名") },
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
                        value = domain,
                        onValueChange = { domain = it },
                        label = { Text("域（可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = remotePath,
                        onValueChange = { remotePath = it },
                        label = { Text("子路径（可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "仅支持 SMB2/SMB3。老设备只开 SMB1 的话连不上，请改用 FTP 或在服务端开启 SMB2。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Mode.LOCAL -> {
                    Text(
                        "选择目标文件夹在本机的挂载点（由你自行挂载后在此选取）。",
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
                    status = null
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

                            Mode.SMB -> SmbChannel(
                                host = address.trim(),
                                shareName = shareName.trim(),
                                user = user.trim(),
                                password = password,
                                domain = domain.trim(),
                                path = remotePath.trim(),
                                port = port.trim().toIntOrNull() ?: 445,
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
                            val kind = when (mode) {
                                Mode.REST -> ProfileKind.REST
                                Mode.FTP -> ProfileKind.FTP
                                Mode.SMB -> ProfileKind.SMB
                                Mode.LOCAL -> ProfileKind.LOCAL
                            }
                            val autoName = when (mode) {
                                Mode.REST -> address.trim()
                                Mode.FTP -> "FTP ${address.trim()}"
                                Mode.SMB -> "SMB ${address.trim()}\\${shareName.trim()}"
                                Mode.LOCAL -> "本地目录"
                            }
                            onConnected(
                                target,
                                ClientProfile(
                                    name = name.trim().ifBlank { autoName },
                                    kind = kind,
                                    role = if (mode == Mode.REST) {
                                        ProfileRole.INFO
                                    } else {
                                        ProfileRole.ACTION
                                    },
                                    host = address.trim(),
                                    port = port.trim(),
                                    user = user.trim(),
                                    secret = if (mode == Mode.REST) apiKey.trim() else password,
                                    shareName = shareName.trim(),
                                    domain = domain.trim(),
                                    path = remotePath.trim(),
                                    useHttps = useHttps,
                                    treeUri = shareUri?.toString().orEmpty(),
                                ),
                            )
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
                    val hint = when (mode) {
                        Mode.REST ->
                            "提示：REST 模式下目标客户端必须开启「Web UI 远程访问」，" +
                                "且手机与它处于同一网络。"
                        Mode.FTP ->
                            "提示：请确认 FTP 服务已开启、端口正确，路径用绝对路径。"
                        Mode.SMB ->
                            "提示：请确认共享名正确、账号有权限，" +
                                "且服务端支持 SMB2 以上。"
                        Mode.LOCAL ->
                            "提示：请重新选择目录，系统可能已收回授权。"
                    }
                    Text(
                        hint,
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
