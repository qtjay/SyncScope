package com.qtjay.syncscope.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.ToggleableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录树中的一行。 */
private class TreeRow(
    val name: String,
    val path: String,
    val dir: Boolean,
    val depth: Int,
    var expanded: Boolean = false,
    var childrenLoaded: Boolean = false,
)

/**
 * 文件夹选择：以可展开的树勾选需要同步的内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(channel: SyncChannel, folder: Folder, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<TreeRow>() }
    val selectedDirs = remember { mutableStateMapOf<String, Boolean>() }
    val selectedFiles = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    fun loadChildren(parentIndex: Int, parent: TreeRow?) {
        val prefix = parent?.path.orEmpty()
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { channel.tree(folder.id, prefix) }.getOrDefault(emptyList())
            }
            if (entries.isEmpty()) {
                return@launch
            }
            val parentDepth = parent?.depth ?: -1
            val insertAt = parentIndex + 1
            while (rows.size > insertAt && rows[insertAt].depth > parentDepth) {
                rows.removeAt(insertAt)
            }
            val created = entries
                .mapNotNull { entry ->
                    val name = entry.name
                    if (name.isNullOrEmpty()) null
                    else TreeRow(
                        name = name,
                        path = if (prefix.isEmpty()) name else "$prefix/$name",
                        dir = entry.isDirectory(),
                    depth = parentDepth + 1,
                    )
                }
                .sortedWith(compareBy({ !it.dir }, { it.name }))
            rows.addAll(insertAt, created)
            if (parent != null) {
                parent.childrenLoaded = true
            }
        }
    }

    LaunchedEffect(folder.id) {
        loadChildren(-1, null)
        loading = false
    }

    fun toggle(row: TreeRow, wantSelected: Boolean) {
        if (wantSelected) {
            if (row.dir) selectedDirs[row.path] = true else selectedFiles[row.path] = true
        } else {
            selectedDirs.remove(row.path)
            selectedFiles.remove(row.path)
        }
    }

    fun isSelected(row: TreeRow): Boolean {
        return if (row.dir) selectedDirs.containsKey(row.path)
        else selectedFiles.containsKey(row.path)
    }

    fun hasSelectedDescendant(path: String): Boolean {
        val prefix = "$path/"
        return selectedDirs.keys.any { it.startsWith(prefix) }
                || selectedFiles.keys.any { it.startsWith(prefix) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(folder.displayName) }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (!channel.capabilities.canSeeRemoteOnlyFiles) {
                Text(
                    text = "共享目录模式：只能看到本机已有的文件，看不到远端未下载的内容。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (loading && rows.isEmpty()) {
                Text("加载中…", modifier = Modifier.padding(16.dp))
            } else if (rows.isEmpty()) {
                Text(
                    text = "没有条目。请确认目标文件夹已完成首次扫描，或该通道是否有访问权。",
                    modifier = Modifier.padding(16.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows) { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp + row.depth * 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val selected = isSelected(row)
                        val partial = row.dir && !selected && hasSelectedDescendant(row.path)
                        val state = when {
                            selected -> ToggleableState.On
                            partial -> ToggleableState.Indeterminate
                            else -> ToggleableState.Off
                        }
                        TriStateCheckbox(
                            state = state,
                            onClick = { toggle(row, state != ToggleableState.On) },
                        )
                        Text(
                            text = row.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        val dirs = selectedDirs.keys.toSet()
                        val files = selectedFiles.keys.toSet()
                        val all = ArrayList<String>(dirs)
                        all.addAll(files)
                        val existing = withContext(Dispatchers.IO) {
                            runCatching { channel.ignores(folder.id) }
                                .getOrDefault(emptyList())
                        }
                        val result = com.qtjay.syncscope.core.pattern.SelectiveSyncPatterns
                            .buildStignore(existing.joinToString("\n"), all, dirs)
                        val ok = withContext(Dispatchers.IO) {
                            channel.writeIgnores(folder.id, result.text.split("\n"))
                        }
                        withContext(Dispatchers.IO) { channel.rescan(folder.id) }
                        message = if (ok) "已保存" else "保存失败"
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("保存")
            }

            message?.let { Text(it, modifier = Modifier.padding(16.dp)) }
        }
    }
}
