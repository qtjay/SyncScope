package com.qtjay.syncscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 列出目标客户端上的共享文件夹，选择其中一个进行配置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickScreen(channel: SyncChannel, onPick: (Folder) -> Unit, onBack: () -> Unit) {
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching { channel.folders() }.getOrNull()
        }
        loading = false
        if (result == null) {
            error = "读取文件夹列表失败，请检查连接与权限"
        } else {
            folders = result
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("选择文件夹") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                Text("加载中…", modifier = Modifier.padding(16.dp))
            }
            error?.let {
                Text(it, modifier = Modifier.padding(16.dp))
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
}
