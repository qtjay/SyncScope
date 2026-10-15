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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import com.qtjay.syncscope.core.model.FolderStatus
import com.qtjay.syncscope.core.pattern.SelectiveSyncPatterns
import com.qtjay.syncscope.data.CachedRow
import com.qtjay.syncscope.data.TreeCache
import com.qtjay.syncscope.data.TreeSnapshot
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

/**
 * 勾选状态，分三桶保存 —— 对应 .stignore 里的两种放行写法：
 *  - [fullDirs]：整棵子树都同步；
 *  - [partialDirs]：只放行了其中一部分子项（目录本身要放行，子项各自算）；
 *  - [files]：单个文件被放行。
 */
private data class SelectionSets(
    val fullDirs: Set<String> = emptySet(),
    val partialDirs: Set<String> = emptySet(),
    val files: Set<String> = emptySet(),
) {
    fun all(): Set<String> = fullDirs + partialDirs + files
}

enum class FolderView { TREE, CHANNEL, RULES }

/** 只保留顶层路径：a 与 a/b 同时被取消时，删掉 a 就够了。 */
private fun topLevelPaths(paths: Set<String>): List<String> {
    return paths.filter { path ->
        paths.none { other -> other != path && path.startsWith("$other/") }
    }.sortedBy { it.length }
}

/** 按当前展开层级递归取回所有可见行。 */
private suspend fun buildVisibleRows(
    fetch: suspend (String) -> List<TreeRow>,
    expanded: Set<String>,
): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    suspend fun walk(prefix: String) {
        for (row in fetch(prefix)) {
            row.expanded = row.dir && expanded.contains(row.path)
            out.add(row)
            if (row.expanded) {
                walk(row.path)
            }
        }
    }
    walk("")
    return out
}

/**
 * 文件夹详情：目录树（选择性同步）、文件通道、忽略规则原文。
 *
 * <p>进入时的加载顺序是刻意的：
 * <ol>
 *   <li>先把上次缓存的行结构与勾选填进界面，避免白屏干等；</li>
 *   <li>紧接着读取目标端的 `.stignore`，并按当前展开层级重新拉取远端目录树；</li>
 *   <li>最后用 `.stignore` 反解出的勾选<b>覆盖</b>缓存值 —— 目标端的规则才是准的。</li>
 * </ol>
 *
 * <p>保存分两步：先把目录树状态编译成 `.stignore` 写进去，
 * 再拿"本次取消勾选"的路径逐个去远端核对，确实存在才通过文件通道删除；
 * 查不到的一律拒绝删除、恢复成同步状态并提醒用户。
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
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<TreeRow>() }
    val fullDirs = remember { mutableStateMapOf<String, Boolean>() }
    val partialDirs = remember { mutableStateMapOf<String, Boolean>() }
    val selectedFiles = remember { mutableStateMapOf<String, Boolean>() }

    /** 打开时读到的基准勾选，用于计算"本次取消了哪些"。 */
    var baseline by remember { mutableStateOf(SelectionSets()) }
    /** 目录树最近一次从远端取回的时间。 */
    var treeUpdatedAt by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var view by remember(folder.id, initialView) { mutableStateOf(initialView) }
    var stignoreText by remember { mutableStateOf("") }
    var confirmPaths by remember { mutableStateOf(emptyList<String>()) }

    fun currentSelection(): SelectionSets = SelectionSets(
        fullDirs = fullDirs.keys.toSet(),
        partialDirs = partialDirs.keys.toSet(),
        files = selectedFiles.keys.toSet(),
    )

    fun isSelected(path: String): Boolean =
        fullDirs.containsKey(path) || partialDirs.containsKey(path) || selectedFiles.containsKey(path)

    /** 祖先里有"整棵子树都同步"的目录 —— 那自己也是同步的。 */
    fun hasSelectedAncestor(path: String): Boolean {
        val comps = path.split("/")
        var current = ""
        for (index in 0 until comps.size - 1) {
            current = if (current.isEmpty()) comps[index] else "$current/${comps[index]}"
            if (fullDirs.containsKey(current)) {
                return true
            }
        }
        return false
    }

    fun hasSelectedDescendant(path: String): Boolean {
        val prefix = "$path/"
        val all = fullDirs.keys + partialDirs.keys + selectedFiles.keys
        return all.any { it.startsWith(prefix) }
    }

    fun stateOf(row: TreeRow): TriState {
        if (isSelected(row.path) || hasSelectedAncestor(row.path)) {
            return TriState.ON
        }
        return if (row.dir && hasSelectedDescendant(row.path)) {
            TriState.PARTIAL
        } else {
            TriState.OFF
        }
    }

    /** 目录树更新时间的可读描述：10 分钟以内统一显示"小于10分钟"。 */
    fun treeAgeLabel(now: Long = System.currentTimeMillis()): String {
        if (treeUpdatedAt <= 0L) {
            return "尚未更新"
        }
        val minutes = (now - treeUpdatedAt) / 60000L
        return when {
            minutes <= 0 -> "刚刚"
            minutes < 10 -> "小于10分钟"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }

    /** 已加载的行里查它是目录还是文件；没加载到就返回 null。 */
    fun dirKind(path: String): Boolean? = rows.firstOrNull { it.path == path }?.dir

    /** 一整棵子树都勾上（目录）。 */
    fun markFull(path: String) {
        partialDirs.remove(path)
        fullDirs[path] = true
    }

    fun markSelected(path: String) {
        when (dirKind(path)) {
            true -> markFull(path)
            false -> selectedFiles[path] = true
            // 没展开到这一层的路径保持"只放行本身"，语义与原规则一致
            else -> partialDirs[path] = true
        }
    }

    suspend fun fetchChildren(prefix: String): List<TreeRow> {
        val entries = withContext(Dispatchers.IO) {
            runCatching { channel.tree(folder.id, prefix) }.getOrDefault(emptyList())
        }
        val depth = if (prefix.isEmpty()) 0 else prefix.split("/").size
        return entries
            .filter { !it.name.isNullOrEmpty() }
            .map { entry ->
                val name = entry.name!!
                TreeRow(
                    name = name,
                    path = if (prefix.isEmpty()) name else "$prefix/$name",
                    dir = entry.isDirectory(),
                    depth = depth,
                )
            }
            .sortedWith(compareBy({ !it.dir }, { it.name }))
    }

    /** 按当前展开状态重新拉取所有可见行，并刷新更新时间。 */
    suspend fun refreshTree() {
        val expanded = rows.filter { it.expanded }.map { it.path }.toSet()
        val built = buildVisibleRows({ fetchChildren(it) }, expanded)
        rows.clear()
        rows.addAll(built)
        treeUpdatedAt = System.currentTimeMillis()
    }

    /**
     * 把反解出来的勾选写回界面。
     *
     * <p>没有托管块 = 目标端没有忽略规则 = 全部同步 → 所有已加载的行都打勾；
     * 有托管块时，整棵子树选中的目录进 fullDirs，其余按已加载的行判断是目录还是文件。
     */
    fun applyParsed(parsed: SelectiveSyncPatterns.Selection) {
        fullDirs.clear()
        partialDirs.clear()
        selectedFiles.clear()
        if (!parsed.hasBlock) {
            for (row in rows) {
                if (row.dir) fullDirs[row.path] = true else selectedFiles[row.path] = true
            }
            return
        }
        for (path in parsed.fullDirs) {
            fullDirs[path] = true
        }
        for (path in parsed.selectedPaths) {
            if (parsed.fullDirs.contains(path)) {
                continue
            }
            when (dirKind(path)) {
                true -> partialDirs[path] = true
                false -> selectedFiles[path] = true
                else -> partialDirs[path] = true
            }
        }
    }

    /** 重新读取远端：忽略规则 + 可见目录树，再按规则反解刷新勾选。 */
    suspend fun refreshFromRemote() {
        val lines = withContext(Dispatchers.IO) {
            runCatching { channel.ignores(folder.id) }.getOrDefault(emptyList())
        }
        stignoreText = lines.joinToString("\n")
        refreshTree()
        applyParsed(SelectiveSyncPatterns.parseManagedBlock(stignoreText))
    }

    fun loadCached() {
        val cached = TreeCache.load(appContext, folder.id) ?: return
        rows.clear()
        rows.addAll(cached.rows.map { row ->
            TreeRow(
                name = row.name,
                path = row.path,
                dir = row.dir,
                depth = row.depth,
                expanded = row.expanded,
            )
        })
        fullDirs.clear()
        cached.fullDirs.forEach { fullDirs[it] = true }
        partialDirs.clear()
        cached.partialDirs.forEach { partialDirs[it] = true }
        selectedFiles.clear()
        cached.files.forEach { selectedFiles[it] = true }
    }

    fun persist() {
        val current = currentSelection()
        TreeCache.save(
            appContext,
            folder.id,
            TreeSnapshot(
                updatedAt = treeUpdatedAt,
                rows = rows.map { CachedRow(it.name, it.path, it.dir, it.depth, it.expanded) },
                fullDirs = current.fullDirs.toList(),
                partialDirs = current.partialDirs.toList(),
                files = current.files.toList(),
            ),
        )
    }

    fun buildStignoreText(): String {
        val allOn = rows.isNotEmpty() && rows.all { stateOf(it) == TriState.ON }
        if (allOn) {
            // 全部打勾 = 不需要任何忽略规则，移除本应用生成的块（用户自己的规则保留）
            return SelectiveSyncPatterns.stripManagedBlock(stignoreText)
        }
        val all = ArrayList<String>()
        all.addAll(fullDirs.keys)
        all.addAll(partialDirs.keys)
        all.addAll(selectedFiles.keys)
        return SelectiveSyncPatterns.buildStignore(stignoreText, all, fullDirs.keys.toSet()).text
    }

    /** 已经下钻过、但下面没有任何勾选的目录，不必再保留"只放行本身"。 */
    fun pruneEmptyPartialDirs() {
        val expanded = rows.filter { it.expanded }.map { it.path }.toSet()
        for (path in partialDirs.keys.toList()) {
            if (expanded.contains(path) && !hasSelectedDescendant(path)) {
                partialDirs.remove(path)
            }
        }
    }

    /**
     * 取消勾选前，先把"整棵子树被选中"的祖先拆成逐项勾选。
     * 否则那条"放行父目录整棵子树"的规则会把子项一起放行，子项单独取消是不生效的。
     */
    suspend fun materializeAncestors(path: String) {
        val comps = path.split("/")
        var current = ""
        for (index in 0 until comps.size - 1) {
            val next = if (current.isEmpty()) comps[index] else "$current/${comps[index]}"
            if (current.isNotEmpty() && fullDirs.containsKey(current)) {
                val children = fetchChildren(current)
                if (children.isNotEmpty()) {
                    for (child in children) {
                        if (child.path == next) {
                            continue
                        }
                        if (child.dir) {
                            fullDirs[child.path] = true
                        } else {
                            selectedFiles[child.path] = true
                        }
                        partialDirs.remove(child.path)
                    }
                    fullDirs.remove(current)
                    partialDirs[current] = true
                }
            }
            current = next
        }
    }

    /** 勾选/取消勾选。 */
    fun onToggle(row: TreeRow, wantSelected: Boolean) {
        if (wantSelected) {
            if (row.dir) {
                markFull(row.path)
            } else {
                selectedFiles[row.path] = true
            }
            persist()
            return
        }
        scope.launch {
            materializeAncestors(row.path)
            fullDirs.remove(row.path)
            partialDirs.remove(row.path)
            selectedFiles.remove(row.path)
            pruneEmptyPartialDirs()
            persist()
        }
    }

    /** 展开一层。 */
    fun loadChildren(parentIndex: Int, parent: TreeRow?) {
        val prefix = parent?.path.orEmpty()
        if (parent != null) {
            parent.loading = true
        }
        scope.launch {
            val created = fetchChildren(prefix)
            val parentDepth = parent?.depth ?: -1
            val insertAt = parentIndex + 1
            while (rows.size > insertAt && rows[insertAt].depth > parentDepth) {
                rows.removeAt(insertAt)
            }
            rows.addAll(insertAt, created)
            if (parent != null) {
                parent.loading = false
            }
            persist()
        }
    }

    fun collapse(index: Int, row: TreeRow) {
        row.expanded = false
        var removeAt = index + 1
        while (rows.size > removeAt && rows[removeAt].depth > row.depth) {
            rows.removeAt(removeAt)
        }
        persist()
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

    suspend fun finishSave(text: String) {
        refreshFromRemote()
        baseline = currentSelection()
        persist()
        message = text
        busy = false
    }

    /**
     * 对"本次取消勾选"的顶层路径逐个查远端：
     * 确认存在的才通过文件通道删除，查不到的拒绝删除并恢复为同步状态。
     */
    fun runDelete(paths: List<String>) {
        busy = true
        scope.launch {
            val confirmed = ArrayList<String>()
            val missing = ArrayList<String>()
            for (path in paths) {
                val info = withContext(Dispatchers.IO) {
                    runCatching { channel.remoteInfo(folder.id, path) }.getOrNull()
                }
                if (info?.exists == true) {
                    confirmed.add(path)
                } else {
                    // 查不到（不存在或判断不了）一律不删
                    missing.add(path)
                }
            }
            val notes = ArrayList<String>()
            if (confirmed.isNotEmpty()) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { channel.delete(folder, confirmed) }.getOrNull()
                }
                val ok = result?.deleted?.size ?: 0
                val bad = result?.failed?.size ?: 0
                notes.add(
                    "已通过文件通道删除 " + ok + " 项" +
                        (if (bad > 0) "，失败 " + bad + " 项" else ""),
                )
            }
            if (missing.isNotEmpty()) {
                // 远端没有 → 拒绝删除，同时把 .stignore 里这几项改回同步状态
                for (path in missing) {
                    markSelected(path)
                }
                val rewritten = buildStignoreText()
                withContext(Dispatchers.IO) {
                    channel.writeIgnores(folder.id, rewritten.split("\n"))
            }
                withContext(Dispatchers.IO) { channel.rescan(folder.id) }
                notes.add("${missing.size} 项在远端不存在，已拒绝删除并恢复为同步状态")
            }
            finishSave(notes.joinToString("；").ifBlank { "已保存" })
        }
    }

    fun save() {
        if (rows.isEmpty()) {
            message = "目录树还没有内容，先点刷新取一次再保存"
            return
        }
        busy = true
        message = null
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                channel.writeIgnores(folder.id, buildStignoreText().split("\n"))
        }
            if (!written) {
                message = "写入 .stignore 失败"
                busy = false
                return@launch
            }
            withContext(Dispatchers.IO) { channel.rescan(folder.id) }
            // 本次取消勾选的：拿打开时读到的规则做差集
            val removed = topLevelPaths(baseline.all() - currentSelection().all())
            if (removed.isEmpty()) {
                finishSave("已保存")
                return@launch
            }
            if (!channel.capabilities.canDeleteTargetFiles) {
                finishSave(
                    "已写入 .stignore；但未配置文件通道，远端文件未删除（" +
                        removed.size + " 项）",
                )
                return@launch
            }
            confirmPaths = removed
            busy = false
        }
    }

    LaunchedEffect(folder.id) {
        loading = true
        loadCached()
        refreshFromRemote()
        baseline = currentSelection()
        persist()
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
                    val allRowsOn = rows.isNotEmpty() && rows.all { stateOf(it) == TriState.ON }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "目录树更新：${treeAgeLabel()}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    refreshing = true
                                    message = null
                                    refreshFromRemote()
                                    baseline = currentSelection()
                                    persist()
                                    refreshing = false
                                }
                            },
                            enabled = !refreshing,
                        ) {
                            Text(if (refreshing) "刷新中…" else "刷新")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Text("目录树", modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                if (allRowsOn) {
                                    fullDirs.clear()
                                    partialDirs.clear()
                                    selectedFiles.clear()
                                } else {
                                    for (row in rows) {
                                        if (row.dir) {
                                            markFull(row.path)
                                        } else {
                                            selectedFiles[row.path] = true
                                        }
                                    }
                                }
                                persist()
                            },
                            enabled = rows.isNotEmpty(),
                        ) {
                            Text(if (allRowsOn) "全取消" else "全选")
                        }
                    }

                    Text(
                        "勾选＝同步；取消勾选＝写入忽略规则并（若已配文件通道）删除远端文件。" +
                            "刷新会以目标端 .stignore 为准重新加载，未保存的勾选会丢失。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (!channel.capabilities.canSeeRemoteOnlyFiles) {
                        Text(
                            "共享目录模式：只能看到本机已有的文件，看不到远端未下载的内容。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    when {
                        loading && rows.isEmpty() -> {
                            Text("加载中…", modifier = Modifier.padding(16.dp))
                        }

                        rows.isEmpty() -> {
                            Text(
                                "没有条目。请确认该文件夹已完成首次扫描。",
                                modifier = Modifier.padding(16.dp),
                            )
                        }

                        else -> {
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
                                            onToggle = { onToggle(row, it) },
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
                    }

                    Button(
                        onClick = { save() },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(if (busy) "保存中…" else "保存")
                    }
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
                            "当前没有 .stignore，等同于没有任何忽略规则（全部同步）。"
                        } else {
                            stignoreText
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { scope.launch { refreshFromRemote() } },
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

    if (confirmPaths.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { confirmPaths = emptyList() },
            title = { Text("确认删除远端文件") },
            text = {
                Text(
                    "本次取消勾选了 " + confirmPaths.size + " 项，将通过文件通道从目标设备删除：\n" +
                        confirmPaths.joinToString("\n") { "· " + it } +
                        "\n\n删除前会再次确认它们在远端确实存在；查不到的会自动恢复为同步状态。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val paths = confirmPaths
                        confirmPaths = emptyList()
                        runDelete(paths)
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmPaths = emptyList()
                        scope.launch { finishSave("已写入 .stignore；未删除远端文件") }
                    },
                ) {
                    Text("只写规则，不删除")
                }
            },
        )
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
