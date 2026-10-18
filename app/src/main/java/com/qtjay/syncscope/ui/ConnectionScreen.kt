package com.qtjay.syncscope.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.qtjay.syncscope.channel.ShareChannel
import com.qtjay.syncscope.channel.SmbChannel
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.channel.TestResult
import com.qtjay.syncscope.channel.WebdavChannel
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.data.ClientProfile
import com.qtjay.syncscope.data.ProfileKind
import com.qtjay.syncscope.data.ProfileRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Mode { FTP, SMB, WEBDAV, LOCAL }

/**
 * 给某个同步文件夹配「文件通道」的表单。
 *
 * 文件通道是 REST API 的补充，只做两件事：浏览真实文件系统上的目录结构、删除文件。
 * REST 客户端在左侧抽屉里另外添加，不在这里。
 *
 * 连接结果一定会显示：成功给出摘要，失败给出可读原因。
 *
 * 「浏览目录」是给用户省事用的：填好地址账号后可以直接在服务端上点进去选目录，
 * 不用自己手敲路径 —— 路径写错了，后面删文件会全部落空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    title: String = "SyncScope",
    /** 传入已有配置即进入编辑模式：字段预填，保存时沿用原 id。 */
    initial: ClientProfile? = null,
    /**
     * 上次为别的文件夹填过的同类配置，新建时拿来预填。
     *
     * 只提供连接信息（地址、端口、账号、共享名、域、HTTPS），
     * 不填路径 —— 每个文件夹的路径本来就不同，填了反而是误导。
     */
    template: ClientProfile? = null,
    onCancel: (() -> Unit)? = null,
    onConnected: (SyncChannel, ClientProfile) -> Unit,
) {
    val context = LocalContext.current
    // 预填来源：编辑时用它自己，新建时用模板
    val source: ClientProfile? = initial ?: template
    val startMode = when (source?.kind) {
        ProfileKind.FTP -> Mode.FTP
        ProfileKind.SMB -> Mode.SMB
        ProfileKind.WEBDAV -> Mode.WEBDAV
        ProfileKind.LOCAL -> Mode.LOCAL
        // 默认落在 SMB：Windows 共享最常见
        else -> Mode.SMB
    }
    val defaultPort = remember(startMode, source) {
        source?.port.orEmpty().ifBlank {
            when (startMode) {
                Mode.FTP -> "21"
                Mode.SMB -> "445"
                Mode.WEBDAV -> "5005"
                Mode.LOCAL -> "445"
            }
        }
    }
    val key = initial?.id
    // key 变化（换了一个配置来编辑）时重新预填，而不是沿用上一次的输入
    var mode by remember(key) { mutableStateOf(startMode) }
    // 名称不套用模板：模板的名字属于另一个文件夹，照搬只会混淆
    var name by remember(key) { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember(key) { mutableStateOf(source?.host.orEmpty()) }
    var port by remember(key) { mutableStateOf(defaultPort) }
    val startSecret = source?.secret.orEmpty()
    var password by remember(key) { mutableStateOf(startSecret) }
    var user by remember(key) { mutableStateOf(source?.user.orEmpty()) }
    var shareName by remember(key) { mutableStateOf(source?.shareName.orEmpty()) }
    var domain by remember(key) { mutableStateOf(source?.domain.orEmpty()) }
    // 路径不套用模板：每个文件夹的目录不同
    var remotePath by remember(key) { mutableStateOf(initial?.path.orEmpty()) }
    var useHttps by remember(key) { mutableStateOf(source?.useHttps ?: false) }
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
        Mode.WEBDAV -> address.isNotBlank()
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

    fun switchTo(next: Mode, nextPort: String) {
        mode = next
        port = nextPort
        status = null
    }

    /** 用当前表单里填的内容临时拼一条通道，用来连测试或浏览目录。 */
    fun buildTarget(): SyncChannel? = when (mode) {
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

        Mode.WEBDAV -> WebdavChannel(
            host = address.trim(),
            port = port.trim().toIntOrNull() ?: 5005,
            path = remotePath.trim(),
            user = user.trim(),
            password = password,
            useHttps = useHttps,
        )

        Mode.LOCAL -> shareUri?.let { ShareChannel(context.applicationContext, it) }
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeButton("WebDAV", mode == Mode.WEBDAV, Modifier.weight(1f)) {
                    switchTo(Mode.WEBDAV, "5005")
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

            // 新建且拿到了模板时说一声，免得用户以为是自己填的内容
            if (initial == null && source != null && source.kind != ProfileKind.LOCAL) {
                val label = source.name.ifBlank { source.subtitle() }
                Text(
                    "已按上次「$label」填过的连接信息预填，只需改下面的路径。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

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

                Mode.WEBDAV -> {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("WebDAV 地址") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("端口（群晖默认 5005 / 5006）") },
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
                        label = { Text("基础路径（如 /sync，可留空）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("使用 HTTPS", modifier = Modifier.weight(1f))
                        Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                    }
                    Text(
                        "WebDAV 用 PROPFIND 列目录、DELETE 删文件。" +
                            "填好地址账号后可以先点下面的「浏览目录」逐级点选，不用手敲路径。",
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

            // SMB 共享名探测：省得用户去猜机器上到底开了哪些共享
            if (mode == Mode.SMB) {
                ShareProbe(
                    ready = address.isNotBlank(),
                    onProbe = {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                SmbChannel.probeShares(
                                    host = address.trim(),
                                    port = port.trim().toIntOrNull() ?: 445,
                                    user = user.trim(),
                                    password = password,
                                    domain = domain.trim(),
                                )
                            }.getOrDefault(emptyList())
                        }
                    },
                    onPick = { shareName = it },
                )
            }

            // 目录浏览：直接读服务端上的目录树，点进去选中即回填路径
            if (mode != Mode.LOCAL) {
                DirectoryPicker(
                    heading = when (mode) {
                        Mode.SMB -> "浏览共享「${shareName.trim()}」里的目录"
                        Mode.FTP -> "浏览 FTP 上的目录"
                        else -> "浏览 WebDAV 上的目录"
                    },
                    build = { buildTarget() },
                    onPick = { remotePath = it },
                )
            }

            Button(
                onClick = {
                    status = null
                    testing = true
                    scope.launch {
                        val target = buildTarget()
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
                                Mode.WEBDAV -> ProfileKind.WEBDAV
                                Mode.LOCAL -> ProfileKind.LOCAL
                            }
                            val autoName = when (mode) {
                                Mode.FTP -> "FTP ${address.trim()}"
                                Mode.SMB -> "SMB ${address.trim()}\\${shareName.trim()}"
                                Mode.WEBDAV -> "WebDAV ${address.trim()}"
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
                        Mode.WEBDAV ->
                            "提示：请确认 WebDAV 已开启、端口正确；" +
                                "群晖是 5005(HTTP)/5006(HTTPS)，基础路径要带前导斜杠。"
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

/**
 * SMB 共享名探测。
 *
 * 共享名只能靠试：smbj 没有枚举共享的接口（那要走 srvsvc 的 DCE/RPC），
 * 所以对 Windows 默认开启的磁盘共享逐个试着连，连得上就列出来，点一下回填。
 */
@Composable
private fun ShareProbe(
    ready: Boolean,
    onProbe: suspend () -> List<String>,
    onPick: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    loading = true
                    note = null
                    scope.launch {
                        val result = runCatching { onProbe() }.getOrDefault(emptyList())
                        found = result
                        loading = false
                        note = if (result.isEmpty()) {
                            "没探测到可访问的共享。请确认账号有权限，或在下方手动填共享名。"
                        } else {
                            null
                        }
                    }
                },
                enabled = ready && !loading,
            ) {
                Text(if (loading) "探测中…" else "探测共享名")
            }
            Text(
                "自动发现这台机器上有哪些共享",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        val problem = note
        if (problem != null) {
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (found.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (share in found) {
                    TextButton(onClick = { onPick(share) }) {
                        Text(share)
                    }
                }
            }
            Text(
                "点一个填到上面的「共享名」里。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 目录选择器：拿表单里填的连接信息去服务端上逐级列目录，选中后回填路径。
 *
 * 手敲路径很容易写错（大小写、前导斜杠、中文编码），写错了后面删除会全部落空，
 * 所以这里直接把服务端上的真实目录列出来让人点。
 */
@Composable
private fun DirectoryPicker(
    heading: String,
    build: () -> SyncChannel?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<BrowseEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(target: String) {
        val channel = build()
        if (channel == null) {
            error = "先把地址、账号这些必填项填好"
            return
        }
        loading = true
        error = null
        scope.launch {
            val pair: Pair<List<BrowseEntry>, String?> = withContext(Dispatchers.IO) {
                try {
                    channel.tree("", target) to null
                } catch (e: Exception) {
                    emptyList<BrowseEntry>() to (e.message ?: e.javaClass.simpleName)
                }
            }
            entries = pair.first
            error = pair.second
            current = target
            loading = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    open = !open
                    if (open && entries.isEmpty()) {
                        load(current)
                    }
                },
            ) {
                Text(if (open) "收起目录" else "浏览目录…")
            }
            Text(
                heading,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (open) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (current.isEmpty()) "（根）" else "/$current",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { load(current.substringBeforeLast('/', "")) },
                    enabled = current.isNotEmpty() && !loading,
                ) {
                    Text("上级")
                }
                TextButton(onClick = { load(current) }, enabled = !loading) {
                    Text("刷新")
                }
            }

            val problem = error
            if (problem != null) {
                Text(
                    "列不出来：$problem",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (loading && entries.isEmpty()) {
                Text("加载中…", style = MaterialTheme.typography.bodySmall)
            } else if (entries.isEmpty()) {
                Text(
                    "这一层是空的，或路径不对。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Column(modifier = Modifier.heightIn(max = 240.dp)) {
                for (entry in entries) {
                    val entryName = entry.name ?: continue
                    if (!entry.isDirectory()) {
                        continue
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !loading) {
                                load(if (current.isEmpty()) entryName else "$current/$entryName")
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            "$entryName/",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { onPick(current) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (current.isEmpty()) "用根目录" else "用这个目录：/$current")
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
