package com.qtjay.syncscope.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.RemoteInfo
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import com.qtjay.syncscope.core.model.FolderStatus
import com.qtjay.syncscope.core.pattern.SelectiveSyncPatterns
import com.qtjay.syncscope.data.CachedRow
import com.qtjay.syncscope.data.TreeCache
import com.qtjay.syncscope.data.TreeSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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

enum class FolderView { TREE, CHANNEL, RULES, CLEANUP }

/** 后台任务进度；total<=0 表示总数未知，走不确定态。 */
private data class TaskProgress(
    val label: String,
    val current: Int,
    val total: Int,
) {
    val fraction: Float
        get() = if (total > 0) {
            (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * 清理时永不触碰的 Syncthing 元数据。
 * .stignore 删了等于把选择性同步规则清空，.stfolder 删了文件夹会报错，
 * .stversions 是别人的版本历史，动它代价太大。
 */
private val PROTECTED_NAMES = setOf(".stignore", ".stfolder", ".stversions")

/** 列目录时的并发上限：再高容易把 SMB 服务端或手机网络栈压垮。 */
private const val SCAN_PARALLELISM = 6

/** 目录树还空着时的重试间隔：目标端首次扫描通常要几十秒到几分钟。 */
private const val SCAN_RETRY_MS = 10_000L

/** 重试多久就停下（10 分钟）。再扫不出来基本不是扫描慢，而是配置有问题。 */
private const val SCAN_WAIT_MS = 600_000L

/**
 * 扫描"按当前目录树状态应当忽略、却仍留在目标设备上的文件"。
 *
 * <p>判定完全走三桶勾选状态（fullDirs / partialDirs / files），与生成 .stignore
 * 的是同一份数据，所以不会出现"规则说忽略、界面说同步"的口径打架。
 * 命中整棵子树的目录不再往下钻 —— 整棵都是多余的，直接整棵删。
 */
/**
 * 多余项的判定分类，决定默认勾不勾、以及要提示用户什么。
 *
 * 顺序即展示顺序：ONLY_LOCAL 最危险，必须排在最前面先提醒用户。
 */
enum class ExcessKind {
    /** 只有本机这一份，删了就永久丢失 —— 必须由用户显式确认。 */
    ONLY_LOCAL,

    /** 其他设备有副本，但两边状态对不上 —— 该重新同步，而不是直接删。 */
    RESYNC,

    /** 其他设备有相同的副本 —— 删掉本机这一份不会丢数据。 */
    SAFE,

    /** 查不到足够信息，无法判断 —— 一律不自动删。 */
    UNKNOWN,
}

/** 一个"按当前勾选本不该存在"的条目，连同它的判定结果。 */
data class ExcessItem(
    val path: String,
    val dir: Boolean,
    /** 文件通道看到的大小；目录为 -1。 */
    val size: Long,
    val kind: ExcessKind,
    val note: String,
)

/**
 * 给一个多余项分类。
 *
 * <p>判定顺序很关键：
 * <ol>
 *   <li>查不到 / 不在全局模型 → 只有本机有，绝不能自动删；</li>
 *   <li>扣掉本机后没人持有 → 同样只有本机有；</li>
 *   <li>有人持有 → 再比对状态，对不上就提示重新同步而不是删。</li>
 * </ol>
 */
private fun classifyExcess(
    path: String,
    dir: Boolean,
    localSize: Long,
    info: RemoteInfo?,
    localDeviceId: String?,
): ExcessItem {
    if (info == null) {
        return ExcessItem(path, dir, localSize, ExcessKind.UNKNOWN,
            "查不到远端状态，不能自动删")
    }
    if (!info.exists) {
        return ExcessItem(path, dir, localSize, ExcessKind.ONLY_LOCAL,
            "全局模型里没有它，只有本机这一份")
    }
    if (!info.hasRemoteCopy(localDeviceId)) {
        return ExcessItem(path, dir, localSize, ExcessKind.ONLY_LOCAL,
            "远端没有这个文件的副本（除本机外无人持有），删除将永久丢失")
    }
    // 规则一要求的那一步：确认远端确实已同步过这份（不只"存在记录"）。
    // Syncthing 的 availability 就是"谁持有当前版本"，据此可判断同步是否完成。
    if (!info.isInSyncWith(localDeviceId) && !info.ignoredLocal) {
        return ExcessItem(path, dir, localSize, ExcessKind.RESYNC,
            "本机这份还没与远端同步完成，先同步再删，否则会丢改动")
    }
    // 有别的设备持有。目录不做大小比对（各通道对目录大小的定义不一致），
    // 只对文件比对，避免"内容变了但大小没变"之外的误判。
    if (dir || localSize < 0L) {
        return ExcessItem(path, dir, localSize, ExcessKind.SAFE,
            "其它设备持有该副本")
    }
    return when (info.matchesLocal(localSize)) {
        true -> ExcessItem(path, dir, localSize, ExcessKind.SAFE,
            "其它设备有相同副本")
        false -> ExcessItem(path, dir, localSize, ExcessKind.RESYNC,
            "其它设备副本大小不同（本机 $localSize，远端 ${info.size}），可能已改动未同步")
        null -> ExcessItem(path, dir, localSize, ExcessKind.UNKNOWN,
            "拿不到大小信息，无法比对状态")
    }
}

private suspend fun scanExcess(
    /** 文件通道：扫目标设备上的真实磁盘。 */
    diskChannel: SyncChannel,
    /** 信息面（REST）：查全局模型，判断"别的设备有没有、状态是否一致"。 */
    infoChannel: SyncChannel?,
    folderId: String,
    selection: SelectionSets,
    localDeviceId: String?,
    onProgress: (scanned: Int, pending: Int, found: Int) -> Unit,
): List<ExcessItem> = withContext(Dispatchers.IO) {
    data class Hit(val path: String, val dir: Boolean, val size: Long)

    val hits = ArrayList<Hit>()
    var level: List<String> = listOf("")
    var scanned = 0
    while (level.isNotEmpty()) {
        val next = ArrayList<String>()
        for (chunk in level.chunked(SCAN_PARALLELISM)) {
            chunk.map { prefix ->
                async {
                    val entries = runCatching { diskChannel.tree(folderId, prefix) }
                        .getOrDefault(emptyList())
                    for (entry in entries) {
                        val name = entry.name ?: continue
                        if (name.isEmpty() || name == "." || name == "..") continue
                        if (PROTECTED_NAMES.contains(name)) continue
                        val path = if (prefix.isEmpty()) name else "$prefix/$name"
                        val dir = entry.isDirectory()
                        if (isExcess(path, dir, selection)) {
                            synchronized(hits) {
                                hits.add(Hit(path, dir, if (dir) -1L else entry.size))
                            }
                        } else if (dir) {
                            synchronized(next) { next.add(path) }
                        }
                    }
                }
            }.awaitAll()
        }
        scanned += level.size
        onProgress(scanned, next.size, hits.size)
        level = next
    }

    // 批量查全局模型：逐条查意味着每条一次完整握手，那才是慢的根源
    val probed = infoChannel?.let {
        runCatching { it.remoteInfoBatch(folderId, hits.map { it.path }) }.getOrNull()
    } ?: emptyMap()

    return@withContext hits
        .map { classifyExcess(it.path, it.dir, it.size, probed[it.path], localDeviceId) }
        .sortedWith(compareBy({ it.kind.ordinal }, { it.path }))
}

/**
 * 判断某个真实存在的路径是否"多余"—— 即按当前勾选它本不该出现在目标设备上。
 *
 * 只有三种情况要保留：整棵子树被放行、自己被放行（目录或文件）、
 * 或者是某个被放行项的祖先（没有中间目录就进不去）。
 */
private fun isExcess(path: String, dir: Boolean, selection: SelectionSets): Boolean {
    if (selection.fullDirs.contains(path)) return false
    if (selection.partialDirs.contains(path) || selection.files.contains(path)) return false
    var slash = path.indexOf('/')
    while (slash > 0) {
        if (selection.fullDirs.contains(path.substring(0, slash))) return false
        slash = path.indexOf('/', slash + 1)
    }
    // 目录还可能是"通往被放行项的中间层"，没了它里面的选中项也进不去
    if (dir) {
        val prefix = "$path/"
        if (selection.all().any { it.startsWith(prefix) }) return false
    }
    return true
}

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
    /**
     * 本文件夹自己的文件通道（SMB/FTP/本地共享目录）。
     * 它是 API 的补充：用于浏览真实文件系统上的目录结构，以及执行删除。
     */
    fileChannel: SyncChannel? = null,
    /** 文件通道在界面上的名字。 */
    fileChannelLabel: String? = null,
    onAddFileChannel: () -> Unit = {},
    onEditFileChannel: () -> Unit = {},
    onRemoveFileChannel: () -> Unit = {},
    onBack: () -> Unit = {},
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
    /** 确认框里逐条展示"通道会去删哪个路径、现在看不看得见"。 */
    /** 文件通道页里"自检"按钮的结果。 */
    var probeText by remember { mutableStateOf<String?>(null) }
    /** 保存/删除/扫描进行到哪一步了。 */
    var progress by remember { mutableStateOf<TaskProgress?>(null) }
    /**
     * 处于"暂停下载"：托管块里只有"忽略一切"，本机什么都不下载。
     * 用于新加的文件夹还没扫出目录结构时先按住，避免文件先下来一大堆。
     */
    var paused by remember { mutableStateOf(false) }
    /** 暂停下载是否正在写入。 */
    var pausing by remember { mutableStateOf(false) }
    /**
     * 本机（目标设备）的 Syncthing 设备 ID。
     * 判断"别的设备有没有副本"时必须把它排除 —— 要删的正是本机这一份。
     */
    var localDeviceId by remember { mutableStateOf<String?>(null) }

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
        paused = parsed.paused
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

    /**
     * 保存之后用：目录结构没动过，只有规则变了，
     * 所以只读回 .stignore 反解，不再整棵重拉（那才是耗时大头）。
     */
    suspend fun refreshRulesOnly() {
        val lines = withContext(Dispatchers.IO) {
            runCatching { channel.ignores(folder.id) }.getOrDefault(emptyList())
        }
        stignoreText = lines.joinToString("\n")
        applyParsed(SelectiveSyncPatterns.parseManagedBlock(stignoreText))
    }

    /** 某些路径已经从目标设备上消失了，直接从目录树行里摘掉，避免展示与事实不符。 */
    fun dropRows(paths: Collection<String>) {
        if (paths.isEmpty()) {
            return
        }
        val gone = paths.toSet()
        val kept = rows.filter { row ->
            gone.none { removed -> row.path == removed || row.path.startsWith("$removed/") }
        }
        rows.clear()
        rows.addAll(kept)
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
     * 取消勾选前，先把"整棵子树被放行"的祖先逐个拆成逐项放行。
     *
     * <p>不拆的话，那条"放行父目录整棵子树"的规则会把子项一起放行，
     * 子项单独取消是无效的 —— .stignore 首个匹配生效，父规则先命中就轮不到子项。
     *
     * <p>这里踩过两个坑，别再犯：
     * <ol>
     *   <li>循环必须走到 path 的直接父目录。以前写成 0 until size-1 且在循环头判断，
     *       两层路径（a/b）时循环体一次都没执行，父目录 a 保持"整棵放行"，
     *       结果刚取消的 a/b 立刻又被 hasSelectedAncestor 判成 ON，
     *       表现就是"子目录怎么点都取消不掉"；</li>
     *   <li>展开时不能预先排除"通往目标那一支"。排除等于把目标的兄弟目录一起误伤，
     *       导致取消 a/b/c 时 a/b/x 也跟着被取消。
     *       现在先全部展开，再由 onToggle 单独摘掉目标及其后代。</li>
     * </ol>
     */
    suspend fun materializeAncestors(path: String) {
        val comps = path.split("/")
        for (depth in 1 until comps.size) {
            val ancestor = comps.subList(0, depth).joinToString("/")
            if (!fullDirs.containsKey(ancestor)) {
                continue
            }
            val children = fetchChildren(ancestor)
            if (children.isEmpty()) {
                continue
            }
            for (child in children) {
                if (child.dir) {
                    fullDirs[child.path] = true
                } else {
                    selectedFiles[child.path] = true
                }
                partialDirs.remove(child.path)
            }
            fullDirs.remove(ancestor)
            partialDirs[ancestor] = true
        }
    }

    /**
     * 把这一项连同它的后代一起从勾选里摘掉。
     *
     * 目录整棵取消时，它下面被单独勾选的子项也必须一起摘掉 ——
     * 父目录被忽略后，子项单独放行在 Syncthing 里根本不生效，
     * 留着只会让生成的 .stignore 自相矛盾。
     */
    fun removeWithDescendants(row: TreeRow) {
        fullDirs.remove(row.path)
        partialDirs.remove(row.path)
        selectedFiles.remove(row.path)
        if (!row.dir) {
            return
        }
        val prefix = row.path + "/"
        for (path in fullDirs.keys.toList()) {
            if (path.startsWith(prefix)) {
                fullDirs.remove(path)
            }
        }
        for (path in partialDirs.keys.toList()) {
            if (path.startsWith(prefix)) {
                partialDirs.remove(path)
            }
        }
        for (path in selectedFiles.keys.toList()) {
            if (path.startsWith(prefix)) {
                selectedFiles.remove(path)
            }
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
            removeWithDescendants(row)
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
        refreshRulesOnly()
        baseline = currentSelection()
        persist()
        message = text
        progress = null
        busy = false
    }


    /**
     * 暂停下载：立刻写入"忽略一切"的托管块，本机不再下载任何内容。
     *
     * <p>场景：新加的文件夹还没被扫进全局模型时，browse 是空的、无从勾选，
     * 而等扫出来这段时间本机已经在下载，等能勾时文件已经下来一大堆。
     *
     * <p>暂停后等目录结构出现（界面会自动重试），用户勾选想要的 → 保存 →
     * 托管块被换成正常的放行规则，那时才开始下载勾选的内容。
     */
    /**
     * 把保留下来的文件纳入同步 —— 改 .stignore 放行它们。
     *
     * 不做这一步的话，这些文件既留在磁盘上又不参与同步，
     * 下次扫描还会被当成多余项，反复提示。
     */
    fun includeInSync(paths: List<String>) {
        if (paths.isEmpty()) {
            return
        }
        busy = true
        scope.launch {
            for (path in paths) {
                markSelected(path)
            }
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    channel.writeIgnores(folder.id, buildStignoreText().split("\n"))
                }.getOrDefault(false)
            }
            if (!written) {
                message = "纳入同步失败：忽略规则没写成"
                busy = false
                return@launch
            }
            withContext(Dispatchers.IO) { channel.rescan(folder.id) }
            refreshRulesOnly()
            message = "已把 ${paths.size} 项纳入同步"
            busy = false
        }
    }

    fun pauseDownload() {
        pausing = true
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    channel.writeIgnores(
                        folder.id,
                        SelectiveSyncPatterns.buildPausedStignore(stignoreText).text.split("\n"),
                    )
                }.getOrDefault(false)
            }
            if (!written) {
                message = "写入忽略规则失败，未能暂停下载。"
                pausing = false
                return@launch
            }
            withContext(Dispatchers.IO) { channel.rescan(folder.id) }
            refreshRulesOnly()
            message = "已暂停下载，本机不会下载任何内容。" +
                "等目录结构出现后勾选要同步的内容，保存即开始下载。"
            pausing = false
        }
    }

    fun save() {
        if (rows.isEmpty()) {
            message = "目录树还没有内容，先点刷新取一次再保存"
            return
        }
        busy = true
        message = null
        progress = TaskProgress("正在写入忽略规则…", 0, 0)
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                channel.writeIgnores(folder.id, buildStignoreText().split("\n"))
        }
            if (!written) {
                message = "写入 .stignore 失败"
                progress = null
                busy = false
                return@launch
            }
            // 修改 .stignore 本身就会让 Syncthing 重新扫描，这里再显式触发一次
            withContext(Dispatchers.IO) { channel.rescan(folder.id) }
            // 本次取消勾选的：拿打开时读到的规则做差集
            val removed = topLevelPaths(baseline.all() - currentSelection().all())
            // 这一页只负责写规则，删除一律交给「校验文件」模块 ——
            // 那边会先确认远端有没有副本，能挡住"只有本机一份"的误删。
            finishSave(
                if (removed.isEmpty()) {
                    "已保存"
                } else {
                    "已保存并写入忽略规则。本次取消勾选 ${removed.size} 项，" +
                        "等 Syncthing 完成扫描（修改忽略规则文件会触发扫描）后，" +
                        "记得去「校验文件」删除这些不再同步的文件。"
                },
            )
        }
    }

    LaunchedEffect(folder.id) {
        loading = true
        // 本机 ID 只是 REST 面缓存下来的字段，取一次就好
        localDeviceId = withContext(Dispatchers.IO) {
            runCatching { channel.deviceId() }.getOrNull()
        }
        loadCached()
        refreshFromRemote()
        baseline = currentSelection()
        persist()
        loading = false

        // 目录结构还空着就定时重试：新加的文件夹要等目标端扫完才有内容，
        // 让用户一遍遍手动点刷新不现实。首次扫描几十秒到几分钟都正常。
        var waited = 0L
        while (rows.isEmpty() && waited < SCAN_WAIT_MS) {
            delay(SCAN_RETRY_MS)
            waited += SCAN_RETRY_MS
            if (rows.isEmpty()) {
                loading = true
                refreshFromRemote()
                baseline = currentSelection()
                loading = false
            }
        }
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

        // 进度条：删除/保存都走网络，不给反馈会让人以为卡死
        val shownProgress = progress
        if (shownProgress != null) {
            TaskProgressBar(
                progress = shownProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Text(
                if (shownProgress.total > 0) {
                    shownProgress.label +
                        "（${shownProgress.current}/${shownProgress.total}）"
                } else {
                    shownProgress.label
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ViewButton("选择性同步", view == FolderView.TREE, Modifier.weight(1f)) {
                    view = FolderView.TREE
                }
                ViewButton("文件通道", view == FolderView.CHANNEL, Modifier.weight(1f)) {
                    view = FolderView.CHANNEL
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                ViewButton("忽略规则", view == FolderView.RULES, Modifier.weight(1f)) {
                    view = FolderView.RULES
                }
                ViewButton("校验文件", view == FolderView.CLEANUP, Modifier.weight(1f)) {
                    view = FolderView.CLEANUP
                }
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            ) {
                                if (paused) {
                                    Text("已暂停下载，本机不会下载任何内容。")
                                    Text(
                                        "等目标端扫描完成，这里会自动出现目录结构；" +
                                            "届时勾选要同步的内容，保存即开始下载。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Text("还没有目录结构，目标端可能还在首次扫描。")
                                    Text(
                                        "可以先暂停下载，免得文件先下来一大堆；" +
                                            "等目录结构出现后再勾选要同步的内容。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Button(
                                        onClick = { pauseDownload() },
                                        enabled = !pausing && !busy,
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) {
                                        Text(if (pausing) "正在暂停…" else "暂停下载")
                                    }
                                }
                                TextButton(
                                    onClick = { scope.launch { refreshFromRemote() } },
                                    enabled = !loading && !busy,
                                ) {
                                    Text("立即重试")
                                }
                            }
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
                    val action = fileChannel
                    if (action == null) {
                        Text(
                            "这个文件夹还没配文件通道。" +
                                "Syncthing 的 REST 接口没有删除文件的端点，" +
                                "没配通道时取消勾选只会写入忽略规则，对方磁盘上的文件仍然保留。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = onAddFileChannel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        ) {
                            Text("添加 SMB / FTP / WebDAV / 本地文件通道")
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                fileChannelLabel ?: "已配文件通道",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onEditFileChannel) { Text("编辑") }
                            TextButton(onClick = onRemoveFileChannel) { Text("删除") }
                        }
                        Text(
                            "文件通道用于浏览真实文件系统上的目录结构，以及执行删除。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = {
                                probeText = null
                                scope.launch {
                                    probeText = withContext(Dispatchers.IO) {
                                        runCatching {
                                        channel.diagnoseBatch(folder, listOf(""))[""]
                                    }
                                            .getOrElse {
                                                "自检失败：${it.message ?: it.javaClass.simpleName}"
                                            }
                                    }
                                }
                            }) { Text("自检根目录") }
                        }
                        val probe = probeText
                        if (probe != null) {
                            Text(
                                probe,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        FileChannelBrowser(
                            channel = action,
                            folderId = folder.id,
                            modifier = Modifier.weight(1f),
                        )
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

            FolderView.CLEANUP -> {
                CleanupPane(
                    fileChannel = fileChannel,
                    infoChannel = channel,
                    folder = folder,
                    selection = currentSelection(),
                    localDeviceId = localDeviceId,
                    folderStatus = folderStatus,
                    onRowsGone = { dropRows(it) },
                    onMessage = { message = it },
                    onIncludeInSync = { paths -> includeInSync(paths) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        message?.let { Text(it, modifier = Modifier.padding(16.dp)) }
    }

}

/**
 * 自绘的进度条。
 *
 * 不用 LinearProgressIndicator 的 progress 参数：那个重载在不同 material3 版本里
 * 一个是 Float、一个是 () -> Float，换成自绘就没有兼容坑。
 * 总数未知（total<=0）时走循环扫动的条纹，表示"还在动"。
 */
@Composable
private fun TaskProgressBar(
    progress: TaskProgress,
    modifier: Modifier = Modifier,
) {
    val known = progress.total > 0
    val fraction by animateFloatAsState(
        targetValue = if (known) progress.fraction else 1f,
        label = "taskProgress",
    )
    val infinite = rememberInfiniteTransition(label = "taskProgressShimmer")
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "taskProgressSweep",
    )
    Box(
        modifier = modifier
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (known) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        } else {
            // 不确定态：一段固定宽度的色块来回扫
            val span = 0.35f
            val start = ((1f - span) * sweep).coerceIn(0f, 1f - span)
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(start)
                        .height(6.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(span)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * 「校验文件」页（原「清理多余文件」）。
 *
 * <p>用途：.stignore 里已经忽略掉的东西，目标设备上可能还留着实体文件
 * （比如以前同步过、后来才取消勾选的）。这一页把它们扫出来，允许批量删掉。
 *
 * <p>走文件通道而不是 REST：REST 只能看全局模型，删不掉；
 * 而且这一页关心的正是"磁盘上到底还剩什么"，只有文件通道看得到。
 */
/**
 * 「校验文件」列表的一行：要么是分组标题，要么是一个待处理的条目。
 *
 * 之所以做成扁平列表，是因为 LazyListScope 的 item() 不能在 itemsIndexed 的
 * lambda 里调用（DSL_SCOPE_VIOLATION）—— 分组标题必须先混进数据里。
 */
private sealed class CleanupRow {
    class Header(val kind: ExcessKind) : CleanupRow()
    /** depth 用于展开子项时缩进。 */
    class Entry(val item: ExcessItem, val depth: Int = 0) : CleanupRow()
}

/** 每种分类的小标题，让"危险的"和"安全的"一眼分得开。 */
@Composable
private fun ExcessGroupHeader(kind: ExcessKind) {
    val title = when (kind) {
        ExcessKind.ONLY_LOCAL -> "⚠ 只有本机这一份 —— 默认不勾，删了就永久丢失"
        ExcessKind.RESYNC -> "⟳ 其它设备有副本但状态对不上 —— 建议重新同步，别直接删"
        ExcessKind.SAFE -> "✓ 其它设备有相同副本 —— 可安全删除"
        ExcessKind.UNKNOWN -> "? 无法判断 —— 默认不勾"
    }
    val color = when (kind) {
        ExcessKind.ONLY_LOCAL, ExcessKind.UNKNOWN -> MaterialTheme.colorScheme.error
        ExcessKind.RESYNC -> MaterialTheme.colorScheme.tertiary
        ExcessKind.SAFE -> MaterialTheme.colorScheme.primary
    }
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun CleanupPane(
    fileChannel: SyncChannel?,
    /** 信息面（REST）：用来判断"别的设备有没有副本、状态是否一致"。 */
    infoChannel: SyncChannel?,
    folder: Folder,
    selection: SelectionSets,
    localDeviceId: String?,
    /** 文件夹当前状态 —— 正在扫描时必须拒绝操作，否则索引在变、判定不准。 */
    folderStatus: FolderStatus? = null,
    onRowsGone: (List<String>) -> Unit,
    onMessage: (String) -> Unit,
    /** 保留下来的文件要不要纳入同步（改 .stignore 放行）。 */
    onIncludeInSync: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var excess by remember(folder.id) { mutableStateOf(emptyList<ExcessItem>()) }
    var chosen by remember(folder.id) { mutableStateOf(emptySet<String>()) }
    var scanning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** 已展开的目录路径 —— 需要"保留某个子项"时展开它再单独取消。 */
    var expanded by remember(folder.id) { mutableStateOf(emptySet<String>()) }
    /** 展开过的目录的子项，按父路径缓存。 */
    var children by remember(folder.id) { mutableStateOf(emptyMap<String, List<ExcessItem>>()) }

    /**
     * 勾选/取消一项，并维护父子联动。
     *
     * <p>默认目录是整棵勾上的（否则删完会留一地空目录）。
     * 只有当用户在展开后取消某个子项时，才把父目录从"整棵删"降级成"逐项删"：
     * 父取消勾选、其余子项补上勾选。这样既保证默认不留空目录，
     * 又不会把用户想保留的那一支删掉。
     */
    fun toggle(path: String, want: Boolean) {
        if (want) {
            chosen = chosen + path
            // 父整棵勾上后，子项不需要再各自勾（删父就够）
            val kids = children[path]
            if (kids != null) {
                chosen = chosen - kids.map { it.path }.toSet()
            }
            return
        }
        chosen = chosen - path
        val parent = path.substringBeforeLast('/', "")
        // 取消子项时，父目录不能再整棵删 —— 降级为逐项删
        if (parent.isNotBlank() && chosen.contains(parent)) {
            chosen = chosen - parent
            val siblings = children[parent]
            if (siblings != null) {
                chosen = chosen + siblings.filter { it.path != path }.map { it.path }.toSet()
            }
        }
    }

    /** 展开/收起一个目录，首次展开时加载其子项。 */
    fun toggleExpand(item: ExcessItem) {
        if (!item.dir) {
            return
        }
        val path = item.path
        if (children.containsKey(path)) {
            expanded = if (expanded.contains(path)) expanded - path else expanded + path
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val entries = runCatching { fileChannel?.tree(folder.id, path) }
                    .getOrNull() ?: emptyList()
                val hits = ArrayList<Pair<String, Pair<Boolean, Long>>>()
                for (entry in entries) {
                    val name = entry.name
                    if (name == null || name.isEmpty() || PROTECTED_NAMES.contains(name)) {
                        continue
                    }
                    val childPath = "$path/$name"
                    val dir = entry.isDirectory()
                    hits.add(childPath to (dir to if (dir) -1L else entry.size))
                }
                val probed = infoChannel?.let {
                    runCatching { it.remoteInfoBatch(folder.id, hits.map { it.first }) }.getOrNull()
                } ?: emptyMap()
                hits.map { (childPath, meta) ->
                    classifyExcess(childPath, meta.first, meta.second, probed[childPath],
                        localDeviceId)
                }
            }
            children = children + (path to loaded)
            expanded = expanded + path
        }
    }

    // Syncthing 正在扫描时整个模块都不许动：索引这一刻还在变，
    // "远端有没有副本"的判定会失真，按它删下去就是赌运气。
    val folderScanning = folderStatus?.state == "scanning"

    fun scan() {
        if (folderScanning) {
            onMessage("Syncthing 正在扫描该文件夹，等扫描完成后再校验。")
            return
        }
        val disk = fileChannel
        if (disk == null) {
            onMessage("这个文件夹还没配文件通道，扫不了磁盘上的残留。")
            return
        }
        scanning = true
        scanned = 0
        excess = emptyList()
        chosen = emptySet()
        progressLabel = "扫描目录…"
        scope.launch {
            val found = scanExcess(
                diskChannel = disk,
                infoChannel = infoChannel,
                folderId = folder.id,
                selection = selection,
                localDeviceId = localDeviceId,
            ) { done, _, hits ->
                scanned = done
                progressLabel = "已扫 $done 个目录，发现 $hits 项待校验"
            }
            excess = found
            // 目录也一起勾：只删文件不删目录会留下一地空目录。
            // 真要保留某个子项时，展开该目录再单独取消即可。
            chosen = found.filter { it.kind == ExcessKind.SAFE }.map { it.path }.toSet()
            scanning = false
            progressLabel = null
        }
    }

    LaunchedEffect(folder.id, fileChannel) { scan() }

    // 分组标题混进数据里：LazyListScope.item() 不能在 items 的 lambda 里调用。
    // 展开目录时，它的子项也递归地铺进同一张扁平列表。
    val rows = remember(excess, expanded, children) {
        val out = ArrayList<CleanupRow>()
        var last: ExcessKind? = null
        fun walk(items: List<ExcessItem>, depth: Int) {
            for (item in items) {
                if (depth == 0 && item.kind != last) {
                    out.add(CleanupRow.Header(item.kind))
                    last = item.kind
                }
                out.add(CleanupRow.Entry(item, depth))
                if (item.dir && expanded.contains(item.path)) {
                    val kids = children[item.path]
                    if (!kids.isNullOrEmpty()) {
                        walk(kids, depth + 1)
                    }
                }
            }
        }
        walk(excess, 0)
        out
    }

    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        if (folderScanning) {
            Text(
                "Syncthing 正在扫描该文件夹，此时索引还在变动，" +
                    "判断结果不可靠 —— 已暂停扫描与删除，请等扫描完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Text(
            "按当前目录树状态，下面这些文件本不该出现在目标设备上" +
                "（已忽略但磁盘上还在）。危险的分在前面且不勾，安全的已默认勾上。" +
                "要保留某个子项，展开它的父目录再单独取消即可。",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            OutlinedButton(
                onClick = { scan() },
                enabled = !scanning && !deleting && !folderScanning,
            ) {
                Text(if (scanning) "扫描中…" else "重新扫描")
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val all = excess.map { it.path }.toSet()
                    chosen = if (chosen.size == all.size) emptySet() else all
                },
                enabled = excess.isNotEmpty() && !deleting,
            ) {
                Text(
                    if (chosen.size == excess.size && excess.isNotEmpty()) "全不选" else "全选",
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 只勾"确认安全"的，把危险的（只有本机一份 / 状态对不上）排除掉
            TextButton(
                onClick = {
                    val safe = excess.filter { it.kind == ExcessKind.SAFE }
                        .map { it.path }.toSet()
                    // 父整棵勾上时清掉子项的独立勾选，避免同一棵被记两次
                    chosen = safe.filter { path ->
                        safe.none { other -> other != path && path.startsWith("$other/") }
                    }.toSet()
                },
                enabled = excess.isNotEmpty() && !deleting,
            ) {
                Text("只选安全的")
            }
        }

        val label = progressLabel
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        when {
            scanning && excess.isEmpty() -> {
                Text("扫描中…", modifier = Modifier.padding(16.dp))
            }

            excess.isEmpty() -> {
                Text(
                    "没有需要处理的条目，目标设备上的内容与当前勾选一致。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(
                        rows,
                        key = { row ->
                            when (row) {
                                is CleanupRow.Header -> "hdr_" + row.kind
                                is CleanupRow.Entry -> row.item.path
                            }
                        },
                    ) { row ->
                        when (row) {
                            is CleanupRow.Header -> ExcessGroupHeader(row.kind)
                            is CleanupRow.Entry -> {
                                val entry = row.item
                                val on = chosen.contains(entry.path)
                                val open = expanded.contains(entry.path)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = (row.depth * 16).dp)
                                        .clickable(enabled = !deleting) {
                                            toggle(entry.path, !on)
                                        }
                                        .padding(vertical = 4.dp),
                                ) {
                                    Checkbox(
                                        checked = on,
                                        onCheckedChange = { want -> toggle(entry.path, want) },
                                        enabled = !deleting,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.path + if (entry.dir) "/" else "",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            entry.note,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (entry.kind) {
                                                ExcessKind.ONLY_LOCAL, ExcessKind.UNKNOWN ->
                                                    MaterialTheme.colorScheme.error
                                                ExcessKind.RESYNC ->
                                                    MaterialTheme.colorScheme.tertiary
                                                ExcessKind.SAFE ->
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                    // 目录可展开：要保留里面某一支时，展开后单独取消它
                                    if (entry.dir) {
                                        TextButton(
                                            onClick = { toggleExpand(entry) },
                                            enabled = !deleting && !folderScanning,
                                        ) {
                                            Text(if (open) "收起" else "展开")
                                        }
                                    }
                                    // 远端没有副本的：要么删（会丢），要么保留 —— 保留就立刻纳入同步，
                                    // 否则它永远是个"多余项"，每次扫描都冒出来。
                                    if (entry.kind == ExcessKind.ONLY_LOCAL) {
                                        TextButton(
                                            onClick = {
                                                onIncludeInSync(listOf(entry.path))
                                                excess =
                                                    excess.filter { it.path != entry.path }
                                                chosen = chosen - entry.path
                                            },
                                            enabled = !deleting && !folderScanning,
                                        ) {
                                            Text("保留")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (excess.isNotEmpty()) {
            Button(
                onClick = { confirmDelete = true },
                enabled = chosen.isNotEmpty() && !deleting && !folderScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(if (deleting) "删除中…" else "删除选中的 ${chosen.size} 项")
            }
            // 剩下的这些是用户决定保留的：要么就地留着（仍在同步范围外），
            // 要么纳入同步——不处理的话它们一直是"多余项"，每次扫描都会冒出来。
            OutlinedButton(
                onClick = { onIncludeInSync(excess.map { it.path }) },
                enabled = !deleting && !folderScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            ) {
                Text("把保留的 ${excess.size} 项纳入同步")
            }
        }
    }

    // 显式声明返回 Unit：否则会被推断成 () -> Job（末尾 scope.launch），
    // 与 onClick 的 () -> Unit 对不上，编译报错 ARGUMENT_TYPE_MISMATCH。
    val goDelete: () -> Unit = {
        confirmDelete = false
        deleting = true
        scanned = 0
        progressLabel = "准备删除…"
        // 只保留顶层：勾了 a 又勾了 a/b 时，删掉 a 就够了，别重复删。
        // 同时保证删除只作用于用户勾选的这一层 —— 绝不会顺带删掉它们的父目录，
        // 这样"子项要保留、父项要删除"的组合才不会把保留的那份一起带走。
        val targets = topLevelPaths(chosen)
        scope.launch {
            var reported = 0
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    fileChannel?.delete(folder.copy(path = ""), targets) { done, total ->
                        if (done - reported >= 50 || done == total) {
                            reported = done
                            progressLabel = "正在删除 $done/$total"
                        }
                    }
                }.getOrNull()
            }
            if (result == null) {
                onMessage("删除失败：文件通道没有返回结果")
            } else {
                val gone = result.deleted.toSet()
                excess = excess.filter { it.path !in gone }
                chosen = chosen - gone
                onRowsGone(result.deleted)
                val notes = ArrayList<String>()
                notes.add("已删除 ${result.deleted.size} 项")
                if (result.failed.isNotEmpty()) {
                    notes.add("失败 ${result.failed.size} 项")
                    if (result.reasons.isNotEmpty()) {
                        notes.add(
                            "失败原因：" + result.reasons.entries.take(3)
                                .joinToString("；") { "${it.key} → ${it.value}" },
                        )
                    }
                }
                // 保留下来的既留在磁盘又不参与同步，下次扫描还会被当成多余 —— 提醒一声
                if (excess.isNotEmpty()) {
                    notes.add("还有 ${excess.size} 项保留着，可点下方「纳入同步」")
                }
                onMessage(notes.joinToString("；"))
            }
            deleting = false
            progressLabel = null
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("确认删除") },
            text = {
                Text(
                    "将从目标设备删除 ${chosen.size} 项，删除后无法恢复：\n" +
                        chosen.joinToString("\n") { "· " + it },
                )
            },
            confirmButton = {
                TextButton(onClick = goDelete) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/**
 * 用文件通道浏览目标设备上的真实目录结构。
 *
 * 这是 REST 看不到的那一面：SMB/FTP/本地共享目录只能看到"实际存在的"文件，
 * 看不到远端有、本机没下载的内容，但反过来它能确认删除目标到底在不在。
 */
@Composable
private fun FileChannelBrowser(
    channel: SyncChannel,
    folderId: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var path by remember(folderId) { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<BrowseEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(target: String) {
        scope.launch {
            loading = true
            error = null
            val pair: Pair<List<BrowseEntry>, String?> = withContext(Dispatchers.IO) {
                try {
                    Pair(channel.tree(folderId, target), null)
                } catch (e: Exception) {
                    Pair(emptyList(), e.message ?: e.javaClass.simpleName)
                }
            }
            entries = pair.first
            error = pair.second
            loading = false
        }
    }

    LaunchedEffect(folderId, path) { load(path) }

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (path.isEmpty()) "根目录 /" else "/$path",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (path.isNotEmpty()) {
                TextButton(onClick = { path = path.substringBeforeLast('/', "") }) {
                    Text("返回上级")
                }
            }
            TextButton(onClick = { load(path) }, enabled = !loading) { Text("刷新") }
        }

        val shown = error
        if (shown != null) {
            Text(
                "列出目录失败：$shown",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else if (loading && entries.isEmpty()) {
            Text("加载中…", style = MaterialTheme.typography.bodySmall)
        } else if (entries.isEmpty()) {
            Text(
                "这一层是空的，或路径不对。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(entries) { _, entry ->
                val name = entry.name ?: return@itemsIndexed
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (entry.isDirectory()) {
                                path = if (path.isEmpty()) name else "$path/$name"
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (entry.isDirectory()) "$name/" else name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (entry.size > 0L && !entry.isDirectory()) {
                        Text(
                            "${entry.size / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
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
