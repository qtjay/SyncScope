package com.qtjay.syncscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

import com.qtjay.syncscope.channel.TestResult
import com.qtjay.syncscope.data.ClientProfile
import com.qtjay.syncscope.data.ProfileKind
import com.qtjay.syncscope.data.ProfileRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun defaultPort(kind: ProfileKind): String = when (kind) {
    ProfileKind.REST -> "8384"
    ProfileKind.FTP -> "21"
    ProfileKind.SMB -> "445"
    ProfileKind.LOCAL -> "8384"
}

/**
 * 抽屉里内嵌的配置表单：新增或编辑一条客户端配置。
 *
 * 只保留最常用的几项 —— 名称、地址、端口、用户名、密码或 API key；
 * SMB 会多显示一个"共享名"（没有它连不上共享），其余类型不显示。
 * 带"测试"按钮：不写入任何东西，只连一下并把结果原样显示出来，方便诊断。
 */
@Composable
fun ProfileEditor(
    initial: ClientProfile?,
    onCancel: () -> Unit,
    onSaved: (ClientProfile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = initial?.id

    var kind by remember(key) { mutableStateOf(initial?.kind ?: ProfileKind.REST) }
    var name by remember(key) { mutableStateOf(initial?.name.orEmpty()) }
    var address by remember(key) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(key) {
        mutableStateOf(initial?.port.orEmpty().ifBlank { defaultPort(initial?.kind ?: ProfileKind.REST) })
    }
    var user by remember(key) { mutableStateOf(initial?.user.orEmpty()) }
    var secret by remember(key) { mutableStateOf(initial?.secret.orEmpty()) }
    var shareName by remember(key) { mutableStateOf(initial?.shareName.orEmpty()) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TestResult?>(null) }

    fun build(): ClientProfile {
        val autoName = when (kind) {
            ProfileKind.REST -> address.trim()
            ProfileKind.FTP -> "FTP ${address.trim()}"
            ProfileKind.SMB -> "SMB ${address.trim()}\\${shareName.trim()}"
            ProfileKind.LOCAL -> "本地目录"
        }
        val base = ClientProfile(
            name = name.trim().ifBlank { autoName },
            kind = kind,
            role = initial?.role ?: ProfileRole.INFO,
            host = address.trim(),
            port = port.trim(),
            user = user.trim(),
            secret = secret,
            shareName = shareName.trim(),
            linkedTo = initial?.linkedTo.orEmpty(),
            folderId = initial?.folderId.orEmpty(),
        )
        return if (initial != null) base.copy(id = initial.id) else base
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (initial == null) "添加配置" else "编辑：${initial.name}",
            style = MaterialTheme.typography.titleMedium,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChoice("REST", kind == ProfileKind.REST, Modifier.weight(1f)) {
                kind = ProfileKind.REST
                port = defaultPort(ProfileKind.REST)
            }
            SmallChoice("FTP", kind == ProfileKind.FTP, Modifier.weight(1f)) {
                kind = ProfileKind.FTP
                port = defaultPort(ProfileKind.FTP)
            }
            SmallChoice("SMB", kind == ProfileKind.SMB, Modifier.weight(1f)) {
                kind = ProfileKind.SMB
                port = defaultPort(ProfileKind.SMB)
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("名称（可选）") },
            modifier = Modifier.fillMaxWidth(),
        )
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
        if (kind == ProfileKind.SMB) {
            OutlinedTextField(
                value = shareName,
                onValueChange = { shareName = it },
                label = { Text("共享名") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("密码或 API key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val channel = build().build(context.applicationContext)
                        val r = withContext(Dispatchers.IO) {
                            runCatching { channel.test() }.getOrNull()
                        }
                        result = r ?: TestResult.fail("连接失败，未拿到任何响应")
                        testing = false
                    }
                },
            ) {
                Text(if (testing) "测试中…" else "测试")
            }
            Button(onClick = { onSaved(build()) }) { Text("保存") }
            TextButton(onClick = onCancel) { Text("取消") }
        }

        val shown = result
        if (shown != null) {
            Text(
                text = if (shown.ok) "✓ ${shown.summary}" else "✗ ${shown.error}",
                style = MaterialTheme.typography.bodySmall,
                color = if (shown.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun SmallChoice(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}
