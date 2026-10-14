package com.qtjay.syncscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
    var loading: Boolean = false,
)

/**
 * 文件夹选择：以可展开的树勾选需要同步的内容。
 *
 * 勾选含义：
 *  - 选中目录意味着「除被排除项外的全部内容同步」；
 *  - 父目录在下级只有部分被选中时显示 "-"（PARTIAL）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(
    channel: SyncChannel,
    folder: Folder,
    onBack: () -> Unit,
    onAttachFileChannel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<TreeRow>() }
    val selectedDirs = remember { mutableStateMapOf<String, Boolean>() }
    val selectedFiles = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun loadChildren(parentIndex: Int, parent: TreeRow?) {
        val prefix = parent?.path.orEmpty()
        if (parent != null) {
            parent.loading = true
        }
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { channel.tree(folder.id, prefix) }.getOrDefault(emptyList())
            }
            val parentDepth = parent?.depth ?: -1
            val insertAt = parentIndex + 1
            while (rows.size > insertAt && rows[insertAt].depth > parentDepth) {
                rows.removeAt(insertAt)
            }
            val created = entries
                .filter { !it.name.isNullOrEmpty() }
                .map { entry ->
                    val name = entry.name!!
                    TreeRow(
                        name = name,
                        path = if (prefix.isEmpty()) name else "$prefix/$name",
                        dir = entry.isDirectory(),
                        depth = parentDepth + 1,
                    )
                }
                .sortedWith(compareBy({ !it.dir }, { it.name }))
            rows.addAll(insertAt, created)
            if (parent != null) {
                parent.loading = false
                parent.childrenLoaded = true
            }
            loading = false
        }
    }

    /** 收起并移除自身所有子孙行。 */
    fun collapse(index: Int, row: TreeRow) {
        row.expanded = false
        var removeAt = index + 1
        while (rows.size > removeAt && rows[removeAt].depth > row.depth) {
            rows.removeAt(removeAt)
        }
    }

    fun onRowClick(index: Int, row: TreeRow) {
        if (!row.dir) {
            return
        }
        if (row.expanded) {
            collapse(index, row)
        } else {
            row.expanded = true
            loadChildren(index, row)
        }
    }

    LaunchedEffect(folder.id) {
        loadChildren(-1, null)
    }

    fun setSelected(row: TreeRow, wantSelected: Boolean) {
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

    fun stateOf(row: TreeRow): TriState {
        if (isSelected(row)) {
            return TriState.ON
        }
        if (row.dir && hasSelectedDescendant(row.path)) {
            return TriState.PARTIAL
        }
        return TriState.OFF
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
            if (!channel.capabilities.canDeleteTargetFiles) {
                Text(
                    text = "当前只有 REST 通道：Syncthing 官方 API 没有删除文件的接口，" +
                        "所以取消勾选只会写入忽略规则，对方磁盘上已有的文件仍然保留。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                OutlinedButton(
                    onClick = onAttachFileChannel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text("添加文件通道（FTP / SMB / 本地）")
                }
            }

            if (channel.capabilities.canDeleteTargetFiles &&
                channel.capabilities.canSeeRemoteOnlyFiles
            ) {
                Text(
                    text = "混合模式已启用：目录与规则走 REST，删除走文件通道。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
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
                itemsIndexed(rows) { index, row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onRowClick(index, row) }
                            .padding(horizontal = (8 + row.depth * 16).dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriCheckbox(
                            state = stateOf(row),
                            onToggle = { setSelected(row, it) },
                        )
                        val label = when {
                            row.dir && row.loading -> "${row.name}/ …"
                            row.dir -> "${row.name}/"
                            else -> row.name
                        }
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Button(
                onClick = {
                    busy = true
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
                        val extra = if (result.warnings.isEmpty()) {
                            ""
                        } else {
                            "\n" + result.warnings.joinToString("\n")
                        }
                        message = if (ok) "已保存$extra" else "保存失败$extra"
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (busy) "保存中…" else "保存")
            }

            message?.let { Text(it, modifier = Modifier.padding(16.dp)) }
        }
    }
}
