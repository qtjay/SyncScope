package com.qtjay.syncscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.qtjay.syncscope.core.model.FolderStatus
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
    var loading: Boolean = false,
)

enum class FolderView { TREE, CHANNEL, RULES }

/**
 * 文件夹详情：选择性同步（目录树）、文件通道、忽略规则原文。
 *
 * 打开时会先把现有的 .stignore 读回来并反解，
 * 让目录树上直接呈现"当前哪些是勾上的"，而不是一张空白的树。
 */
@Composable
fun FolderScreen(
    channel: SyncChannel,
    folder: Folder,
    folderStatus: FolderStatus? = null,
    /** 从主界面右侧"编辑"进来时直接落在文件通道页。 */
    initialView: FolderView = FolderView.TREE,
    onBack: () -> Unit = {},
    onAttachFileChannel: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<TreeRow>() }
    val selectedDirs = remember { mutableStateMapOf<String, Boolean>() }
    val selectedFiles = remember { mutableStateMapOf<String, Boolean>() }
    // 由现有规则反解出来的勾选，行加载时消费一次，之后以用户操作为准
    val pendingFullDirs = remember { mutableSetOf<String>() }
    val pendingOthers = remember { mutableSetOf<String>() }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var view by remember(folder.id, initialView) { mutableStateOf(initialView) }
    var selectiveOn by remember { mutableStateOf(false) }
    var stignoreText by remember { mutableStateOf("") }

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

    /** 读取现有忽略规则并回填勾选集合。 */
    suspend fun reloadRules() {
        val lines = withContext(Dispatchers.IO) {
            runCatching { channel.ignores(folder.id) }.getOrDefault(emptyList())
        }
        stignoreText = lines.joinToString("\n")
        val parsed = com.qtjay.syncscope.core.pattern.SelectiveSyncPatterns
            .parseManagedBlock(stignoreText)
        selectiveOn = parsed.hasBlock
        pendingFullDirs.clear()
        pendingFullDirs.addAll(parsed.fullDirs)
        pendingOthers.clear()
        pendingOthers.addAll(parsed.selectedPaths.filter { !parsed.fullDirs.contains(it) })
    }

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
            // 用反解出来的规则回填勾选：整棵子树被选中的目录直接勾上，
            // 只放行了部分子项的目录不勾（让它的子项各自呈现，父项自然显示 "-"）
            for (row in created) {
                if (row.dir) {
                    if (pendingFullDirs.remove(row.path)) {
                        setSelected(row, true)
                    }
                } else if (pendingOthers.remove(row.path)) {
                    setSelected(row, true)
                }
            }
            rows.addAll(insertAt, created)
            if (parent != null) {
                parent.loading = false
            }
        }
    }

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
        // 连上就先把规则读回来，再展开根目录，这样树一出来就是当前真实状态
        reloadRules()
        loadChildren(-1, null)
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(folder.displayName, style = MaterialTheme.typography.titleMedium)
        }

        val status = folderStatus
        if (status != null) {
            Text(
                "${status.stateLabel()} · ${status.fileSummary()} · ${status.completionPercent()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            ViewButton("选择性同步", view == FolderView.TREE, Modifier.weight(1f)) {
                view = FolderView.TREE
            }
            ViewButton("文件通道", view == FolderView.CHANNEL, Modifier.weight(1f)) {
                view = FolderView.CHANNEL
            }
            ViewButton("忽略规则", view == FolderView.RULES, Modifier.weight(1f)) {
                view = FolderView.RULES
            }
        }

        when (view) {
            FolderView.TREE -> {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text("选择性同步", modifier = Modifier.weight(1f))
                        Switch(checked = selectiveOn, onCheckedChange = { selectiveOn = it })
                    }
                    if (!selectiveOn) {
                        Text(
                            "关闭时该文件夹全部同步，保存会移除本应用生成的规则块（你自己写的规则保留）。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (!channel.capabilities.canSeeRemoteOnlyFiles) {
                        Text(
                            "共享目录模式：只能看到本机已有的文件，看不到远端未下载的内容。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (loading && rows.isEmpty()) {
                        Text("加载中…", modifier = Modifier.padding(16.dp))
                    } else if (rows.isEmpty()) {
                        Text(
                            "没有条目。请确认该文件夹已完成首次扫描。",
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(rows) { index, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onRowClick(index, row) }
                                    .padding(
                                        horizontal = (8 + row.depth * 16).dp,
                                        vertical = 2.dp,
                                    ),
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
                }

                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            val newText = if (!selectiveOn) {
                                com.qtjay.syncscope.core.pattern.SelectiveSyncPatterns
                                    .stripManagedBlock(stignoreText)
                            } else {
                                val dirs = selectedDirs.keys.toSet()
                                val files = selectedFiles.keys.toSet()
                                val all = ArrayList<String>(dirs)
                                all.addAll(files)
                                com.qtjay.syncscope.core.pattern.SelectiveSyncPatterns
                                    .buildStignore(stignoreText, all, dirs).text
                            }
                            val ok = withContext(Dispatchers.IO) {
                                channel.writeIgnores(folder.id, newText.split("\n"))
                            }
                            withContext(Dispatchers.IO) { channel.rescan(folder.id) }
                            reloadRules()
                            message = if (ok) "已保存" else "保存失败"
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
            }

            FolderView.CHANNEL -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    if (channel.capabilities.canDeleteTargetFiles) {
                        Text("已连接文件通道：取消勾选后可以真正删除对方磁盘上的文件。")
                    } else {
                        Text(
                            "还没配文件通道：Syncthing 的 REST 接口没有删除文件的端点，" +
                                "取消勾选只会写入忽略规则，对方磁盘上的文件仍然保留。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = onAttachFileChannel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            Text("添加 SMB / FTP / 本地文件通道")
                        }
                    }
                }
            }

            FolderView.RULES -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        if (stignoreText.isBlank()) {
                            "当前没有 .stignore，等同于没有任何忽略规则。"
                        } else {
                            stignoreText
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { reloadRules() } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Text("重新读取")
                    }
                }
            }
        }

        message?.let { Text(it, modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun ViewButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}
