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

private enum class Mode { FTP, SMB, LOCAL }

/**
 * 给某个同步文件夹配「文件通道」的表单。
 *
 * 文件通道是 REST API 的补充，只做两件事：浏览真实文件系统上的目录结构、删除文件。
 * REST 客户端在左侧抽屉里另外添加，不在这里。
 *
 * 连接结果一定会显示：成功给出摘要，失败给出可读原因。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    title: String = "SyncScope",
    /** 传入已有配置即进入编辑模式：字段预填，保存时沿用原 id。 */
    initial: ClientProfile? = null,
    onCancel: (() -> Unit)? = null,
    onConnected: (SyncChannel, ClientProfile) -> Unit,
) {
    val context = LocalContext.current
    val startMode = when (initial?.kind) {
        ProfileKind.FTP -> Mode.FTP
        ProfileKind.SMB -> Mode.SMB
        ProfileKind.LOCAL -> Mode.LOCAL
        // 默认落在 SMB：Windows 共享最常见
        else -> Mode.SMB
    }
    val key = initial?.id
    // key 变化（换了一个配置来编辑）时重新预填，而不是沿用上一次的输入
    var mode by remember(key) { mutableStateOf(startMode) }
    var name by remember(key) { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember(key) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(key) {
        mutableStateOf(
            initial?.port.orEmpty().ifBlank {
                when (startMode) {
                    Mode.FTP -> "21"
                    Mode.SMB -> "445"
                    Mode.LOCAL -> "445"
                }
            },
        )
    }
    val startSecret = initial?.secret.orEmpty()
    var password by remember(key) { mutableStateOf(startSecret) }
    var user by remember(key) { mutableStateOf(initial?.user.orEmpty()) }
    var shareName by remember(key) { mutableStateOf(initial?.shareName.orEmpty()) }
    var domain by remember(key) { mutableStateOf(initial?.domain.orEmpty()) }
    var remotePath by remember(key) { mutableStateOf(initial?.path.orEmpty()) }
    var shareUri by remember(key) {
        mutableStateOf<Uri?>(
            initial?.treeUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
        )
    }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    val ready = when (mode) {
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
                                Mode.FTP -> ProfileKind.FTP
                                Mode.SMB -> ProfileKind.SMB
                                Mode.LOCAL -> ProfileKind.LOCAL
                            }
                            val autoName = when (mode) {
                                Mode.FTP -> "FTP ${address.trim()}"
                                Mode.SMB -> "SMB ${address.trim()}\\${shareName.trim()}"
                                Mode.LOCAL -> "本地目录"
                            }
                            onConnected(
                                target,
                                ClientProfile(
                                    name = name.trim().ifBlank { autoName },
                                    kind = kind,
                                    role = ProfileRole.ACTION,
                                    host = address.trim(),
                                    port = port.trim(),
                                    user = user.trim(),
                                    secret = password,
                                    shareName = shareName.trim(),
                                    domain = domain.trim(),
                                    path = remotePath.trim(),
                                    treeUri = shareUri?.toString().orEmpty(),
                                ),
                            )
                        }
                    }
                },
                enabled = !testing && ready,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        testing -> "测试中…"
                        initial != null -> "测试并保存"
                        else -> "连接"
                    },
                )
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
