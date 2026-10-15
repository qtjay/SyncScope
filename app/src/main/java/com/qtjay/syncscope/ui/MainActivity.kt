package com.qtjay.syncscope.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.CompositeChannel
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.Folder
import com.qtjay.syncscope.core.model.FolderStatus
import com.qtjay.syncscope.data.ClientProfile
import com.qtjay.syncscope.data.ProfileRole
import com.qtjay.syncscope.data.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SyncScopeApp()
            }
        }
    }
}

/**
 * 应用外壳。
 *
 * <p>左侧抽屉里只有"客户端"（REST API 配置）—— 一个抽屉条目就是一台 Syncthing。
 * 文件通道（SMB/FTP/本地共享目录）是 API 的补充，只负责浏览目录结构和删除文件，
 * 它不再作为独立配置出现在抽屉里：每个同步文件夹自己配一条，入口在该文件夹的
 * 「文件通道」页里，删改也都在那儿。
 *
 * <p>因此通道是按文件夹现拼的：打开某个文件夹时，才把「当前客户端」与该文件夹
 * 自己的文件通道合成一个 CompositeChannel；换个文件夹就换一套，不会串。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScopeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val appCtx = context.applicationContext
    val initialProfiles = remember(appCtx) { ProfileStore.load(appCtx) }
    var profiles by remember { mutableStateOf(initialProfiles) }
    // 启动时自动接上上次那台客户端：省得每次进来都要手动点一次。
    // 存下来的 id 可能已经过期（配置被删了），所以这里要校验它还在不在。
    var activeId by remember {
        mutableStateOf(
            ProfileStore.loadLastActive(appCtx)?.takeIf { id ->
                initialProfiles.any { it.id == id && it.role == ProfileRole.INFO }
            },
        )
    }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(activeId) {
        ProfileStore.saveLastActive(appCtx, activeId)
    }
    /** 文件通道增删改之后 +1，用来重建该文件夹的通道。 */
    var fileChannelKey by remember { mutableStateOf(0) }

    // 信息面：抽屉里选中的那台客户端
    var infoChannel by remember { mutableStateOf<SyncChannel?>(null) }
    // 当前打开文件夹的合成通道（信息面 + 该文件夹自己的文件通道）
    var folderChannel by remember { mutableStateOf<SyncChannel?>(null) }
    // 当前文件夹的文件通道本体，用于浏览与删除前的存在性校验
    var fileChannel by remember { mutableStateOf<SyncChannel?>(null) }

    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String, FolderStatus>>(emptyMap()) }
    var folder by remember { mutableStateOf<Folder?>(null) }
    var folderView by remember { mutableStateOf(FolderView.TREE) }
    var status by remember { mutableStateOf<String?>(null) }
    // 抽屉内的添加/编辑表单（只编辑客户端配置）
    var editorOpen by remember { mutableStateOf(false) }
    var editorInitial by remember { mutableStateOf<ClientProfile?>(null) }
    // 全屏表单：只用于给某个文件夹配文件通道
    var editing by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ClientProfile?>(null) }

    // 全屏配置页（给文件夹配文件通道）时，返回键先退回文件夹
    BackHandler(enabled = editing) {
        editing = false
        editingProfile = null
    }

    fun persist(list: List<ClientProfile>) {
        profiles = list
        ProfileStore.save(context.applicationContext, list)
    }

    fun deleteProfile(id: String) {
        // 连带的 ACTION（文件通道）一并清掉，避免留下孤儿配置
        persist(profiles.filterNot { it.id == id || it.linkedTo == id })
        if (activeId == id) activeId = null
        folder = null
    }

    /** 当前文件夹自己的文件通道配置。 */
    fun fileChannelProfileOf(folderId: String?): ClientProfile? {
        if (folderId.isNullOrBlank()) {
            return null
        }
        return profiles.firstOrNull {
            it.role == ProfileRole.ACTION &&
                it.folderId == folderId &&
                (it.linkedTo.isBlank() || it.linkedTo == activeId)
        }
    }

    fun removeFileChannel() {
        val target = fileChannelProfileOf(folder?.id) ?: return
        persist(profiles.filterNot { it.id == target.id })
        fileChannelKey++
    }

    // 切换客户端 / 点刷新：连上之后立刻取同步文件夹与其状态
    LaunchedEffect(activeId, reloadKey) {
        val infoProfile = profiles.firstOrNull {
            it.id == activeId && it.role == ProfileRole.INFO
        }
        if (infoProfile == null) {
            infoChannel = null
            folderChannel = null
            fileChannel = null
            folders = emptyList()
            statuses = emptyMap()
            folder = null
            status = null
            return@LaunchedEffect
        }
        status = "正在连接…"
        val ch = withContext(Dispatchers.IO) {
            runCatching { infoProfile.build(context.applicationContext) }.getOrNull()
        }
        val result = ch?.let {
            withContext(Dispatchers.IO) { runCatching { it.test() }.getOrNull() }
        }
        val list = ch?.let {
            withContext(Dispatchers.IO) { runCatching { it.folders() }.getOrDefault(emptyList()) }
        } ?: emptyList()
        val statusMap = HashMap<String, FolderStatus>()
        for (item in list) {
            val st = withContext(Dispatchers.IO) {
                runCatching { ch?.status(item.id) }.getOrNull()
            }
            if (st != null) {
                statusMap[item.id] = st
            }
        }
        infoChannel = ch
        folders = list
        statuses = statusMap
        folder = null
        // 连上之后把"设备名 · 短ID"回写成配置名，省得自己起名字还要记设备是哪台。
        // 只在没自动命名过时写一次，之后用户想怎么改都不再动它。
        if (ch != null && infoProfile != null && !infoProfile.autoNamed) {
            // deviceName() 内部要再发一次 /rest/config 请求，
            // 必须放 IO 线程 —— 在主线程发网络请求会抛 NetworkOnMainThreadException，
            // 异常被吞掉后只剩短 ID，表现就是"设备名没出现"。
            val id = withContext(Dispatchers.IO) {
                runCatching { ch.deviceId() }.getOrNull()
            }
            if (!id.isNullOrBlank()) {
                val short = id.take(7)
                val device = withContext(Dispatchers.IO) {
                    runCatching { ch.deviceName() }.getOrNull()
                }
                val auto = if (device.isNullOrBlank()) short else "$device · $short"
                persist(
                    profiles.map {
                        if (it.id == infoProfile.id) {
                            it.copy(name = auto, autoNamed = true)
                        } else {
                            it
                        }
                    },
                )
            }
        }
        status = when {
            ch == null -> "配置无效，请重新添加"
            result == null -> "连接失败"
            result.ok -> result.summary
            else -> result.error
        }
    }

    // 打开某个文件夹时，把信息面与该文件夹自己的文件通道合成一条通道
    LaunchedEffect(activeId, folder?.id, fileChannelKey, profiles) {
        val info = infoChannel
        val current = folder
        if (info == null || current == null) {
            folderChannel = null
            fileChannel = null
            return@LaunchedEffect
        }
        val actionProfile = fileChannelProfileOf(current.id)
        val act = actionProfile?.let { profile ->
            withContext(Dispatchers.IO) {
                runCatching { profile.build(context.applicationContext) }.getOrNull()
            }
        }
        fileChannel = act
        folderChannel = if (act != null) CompositeChannel(info, act) else info
    }

    if (editing) {
        val initial = editingProfile
        ConnectionScreen(
            title = if (initial != null) "编辑文件通道：${initial.name}" else "给文件夹配文件通道",
            initial = initial,
            onCancel = {
                editing = false
                editingProfile = null
            },
            onConnected = { _, profile ->
                val folderId = folder?.id.orEmpty()
                val saved = if (initial != null) {
                    profile.copy(
                        id = initial.id,
                        role = ProfileRole.ACTION,
                        linkedTo = activeId.orEmpty(),
                        folderId = initial.folderId.ifBlank { folderId },
                    )
                } else {
                    profile.copy(
                        role = ProfileRole.ACTION,
                        linkedTo = activeId.orEmpty(),
                        folderId = folderId,
                    )
                }
                if (initial != null) {
                    persist(profiles.map { if (it.id == initial.id) saved else it })
                } else {
                    persist(profiles + saved)
                }
                editing = false
                editingProfile = null
                fileChannelKey++
            },
        )
        return
    }

    // 配置名本身已经是"设备名 · 短ID"（连上时回写），标题直接用它
    val activeName = profiles.firstOrNull { it.id == activeId }?.name ?: "SyncScope"
    val titleText = activeName
    // 抽屉里只放客户端（REST）；文件通道属于某个文件夹，不在这里出现
    val clientProfiles = profiles.filter { it.role == ProfileRole.INFO }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp),
                ) {
                    // 可滚动的主体：编辑表单 + 客户端列表
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    ) {
                    if (editorOpen) {
                        // 表单展开时给一条明确的退路，不能只靠系统返回键
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    editorOpen = false
                                    editorInitial = null
                                },
                            ) {
                                Text("← 返回")
                            }
                            Text(
                                if (editorInitial == null) "添加客户端" else "编辑客户端",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        ProfileEditor(
                            initial = editorInitial,
                            onCancel = {
                                editorOpen = false
                                editorInitial = null
                            },
                            onSaved = { saved ->
                                val initial = editorInitial
                                val client = saved.copy(
                                    role = ProfileRole.INFO,
                                    // 用户改过名字就别再被自动命名覆盖；没改则沿用原标记
                                    autoNamed = if (initial == null) {
                                        false
                                    } else {
                                        initial.autoNamed || saved.name != initial.name
                                    },
                                )
                                if (initial != null) {
                                    persist(profiles.map { if (it.id == initial.id) client else it })
                                } else {
                                    persist(profiles + client)
                                }
                                editorOpen = false
                                editorInitial = null
                                activeId = client.id
                                folder = null
                                reloadKey++
                                scope.launch { drawerState.close() }
                            },
                        )
                    }

                    clientProfiles.forEach { profile ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NavigationDrawerItem(
                                modifier = Modifier.weight(1f),
                                label = {
                                    Column {
                                        Text(profile.name)
                                        Text(
                                            profile.subtitle(),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                selected = profile.id == activeId,
                                onClick = {
                                    activeId = profile.id
                                    folder = null
                                    scope.launch { drawerState.close() }
                                },
                            )
                            // 右侧编辑按钮
                            IconButton(
                                onClick = {
                                    editorInitial = profile
                                    editorOpen = true
                                },
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑该客户端")
                            }
                        }
                    }
                    }

                    // 底部常驻：添加客户端。表单展开时藏起来，免得和"返回"抢位置。
                    if (!editorOpen) {
                        NavigationDrawerItem(
                            label = { Text("＋ 添加客户端") },
                            selected = false,
                            onClick = {
                                editorInitial = null
                                editorOpen = true
                            },
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titleText) },
                    navigationIcon = {
                        // 开着的再按一次就关掉 —— 抽屉打开后必须有明确的退路
                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (drawerState.isOpen) drawerState.close() else drawerState.open()
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = if (drawerState.isOpen) "收起配置列表" else "打开配置列表",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { reloadKey++ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新连接并获取")
                        }
                        val removable = activeId
                        if (removable != null) {
                            IconButton(onClick = { deleteProfile(removable) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除当前客户端")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            // 返回键的优先级：抽屉里的表单 → 抽屉本身 → 文件夹详情页 → 系统默认（退出）
            BackHandler(enabled = drawerState.isOpen) {
                if (editorOpen) {
                    editorOpen = false
                    editorInitial = null
                } else {
                    scope.launch { drawerState.close() }
                }
            }
            BackHandler(enabled = !drawerState.isOpen && folder != null) {
                folder = null
            }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                val shown = status
                if (shown != null) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                // ID 已经写进配置名称了，主页不再重复展示
                // 先拷成局部 val：委托属性没法智能转换成非空
                val info = infoChannel
                val current = folder
                when {
                    info == null -> {
                        Text(
                            "还没有客户端。点左上角菜单 →「＋ 添加客户端」。",
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    current == null -> {
                        FolderPickScreen(
                            channel = info,
                            folders = folders,
                            statuses = statuses,
                            onOpen = {
                                folderView = FolderView.TREE
                                folder = it
                            },
                            onEdit = {
                                folderView = FolderView.CHANNEL
                                folder = it
                            },
                            onReload = { reloadKey++ },
                        )
                    }

                    else -> {
                        val ch = folderChannel
                        val actionProfile = fileChannelProfileOf(current.id)
                        if (ch == null) {
                            Text("正在准备通道…", modifier = Modifier.padding(16.dp))
                        } else {
                            FolderScreen(
                                channel = ch,
                                folder = current,
                                folderStatus = statuses[current.id],
                                initialView = folderView,
                                fileChannel = fileChannel,
                                fileChannelLabel = actionProfile?.let {
                                    "${it.name} · ${it.subtitle()}"
                                },
                                onAddFileChannel = {
                                    editingProfile = null
                                    editing = true
                                },
                                onEditFileChannel = {
                                    editingProfile = actionProfile
                                    editing = true
                                },
                                onRemoveFileChannel = { removeFileChannel() },
                                onBack = { folder = null },
                            )
                        }
                    }
                }
            }
        }
    }
}
