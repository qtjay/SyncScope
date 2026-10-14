package com.qtjay.syncscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.Folder
import com.qtjay.syncscope.core.model.FolderStatus

/**
 * 主界面：显示从目标客户端取回来的同步文件夹及其状态。
 *
 * 每行右侧的编辑按钮进入该文件夹的配置（文件通道 / 选择性同步 / .stignore），
 * 点行本身直接进入选择性同步目录。
 */
@Composable
fun FolderPickScreen(
    channel: SyncChannel,
    folders: List<Folder>,
    statuses: Map<String, FolderStatus>,
    onOpen: (Folder) -> Unit,
    onEdit: (Folder) -> Unit,
    onReload: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "同步文件夹",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        if (folders.isEmpty()) {
            Text(
                "没有读到共享文件夹。请确认目标客户端在运行、地址与密钥正确。",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedButton(
                onClick = onReload,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("重新获取")
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(folders) { folder ->
                val status = statuses[folder.id]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onOpen(folder) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        Text(folder.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (status != null) {
                            Text(
                                "${status.stateLabel()} · ${status.fileSummary()} · " +
                                    "${status.completionPercent()}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else if (folder.path.isNotBlank()) {
                            Text(folder.path, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // 右侧编辑：进入该文件夹的文件通道 / 选择性同步 / .stignore 配置
                    IconButton(onClick = { onEdit(folder) }) {
                        Icon(Icons.Default.Edit, contentDescription = "配置该文件夹")
                    }
                }
            }
        }
    }
}
