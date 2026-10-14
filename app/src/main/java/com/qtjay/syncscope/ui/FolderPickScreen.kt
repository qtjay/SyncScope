package com.qtjay.syncscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.Folder

/**
 * 列出目标客户端上的共享文件夹。
 *
 * folders 由外壳在连接成功后立刻取回来再传进来 ——
 * 用户切到这一屏时信息已经在了，不用再等一次网络请求。
 * 顶栏由外壳（抽屉）提供，这里不再套一层 Scaffold，避免两个标题栏叠在一起。
 */
@Composable
fun FolderPickScreen(
    channel: SyncChannel,
    folders: List<Folder>,
    onPick: (Folder) -> Unit,
    onAddFileChannel: () -> Unit = {},
    onReload: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "共享文件夹",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // 只有信息面通道时提醒补一个文件通道，否则删不了文件
        if (!channel.capabilities.canDeleteTargetFiles) {
            Text(
                "还没配文件通道：Syncthing 的 REST 接口没有删除文件的端点，" +
                    "取消勾选只会写入忽略规则，对方磁盘上的文件仍然保留。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            OutlinedButton(
                onClick = onAddFileChannel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("添加 SMB / FTP / 本地文件通道")
            }
        }

        if (folders.isEmpty()) {
            Text(
                "没有读到共享文件夹。请确认目标客户端在运行、地址与密钥正确。",
                modifier = Modifier.padding(16.dp),
            )
            OutlinedButton(
                onClick = onReload,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("重新获取")
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(folders) { folder ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(folder) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(folder.displayName, style = MaterialTheme.typography.bodyLarge)
                    if (folder.path.isNotBlank()) {
                        Text(folder.path, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
