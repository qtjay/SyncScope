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
 * 左侧抽屉（置顶的汉堡键打开）只管"配置"：内嵌添加/编辑表单 + 已保存配置列表，
 * 点某条配置即切到它并回到主界面。文件通道不再在抽屉里单独出现，
 * 它属于某个文件夹，只能从主界面文件夹右侧的编辑按钮进去配。
 *
 * 主界面显示取回来的同步文件夹与状态，每行右侧编辑进入该文件夹的
 * 文件通道 / 选择性同步 / .stignore 三处配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScopeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var profiles by remember { mutableStateOf(ProfileStore.load(context.applicationContext)) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var actionId by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    var channel by remember { mutableStateOf<SyncChannel?>(null) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var statuses by remember { mutableStateOf<Map<String, FolderStatus>>(emptyMap()) }
    var folder by remember { mutableStateOf<Folder?>(null) }
    var folderView by remember { mutableStateOf(FolderView.TREE) }
    var status by remember { mutableStateOf<String?>(null) }
    // 抽屉内的添加/编辑表单
    var editorOpen by remember { mutableStateOf(false) }
    var editorInitial by remember { mutableStateOf<ClientProfile?>(null) }
    // 全屏表单：只用于给某个文件夹配文件通道
    var editing by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ClientProfile?>(null) }
    var editingRole by remember { mutableStateOf(ProfileRole.INFO) }
    var pendingFolderId by remember { mutableStateOf<String?>(null) }

    // 全屏配置页（给文件夹配文件通道）时，返回键先退回主界面
    BackHandler(enabled = editing) {
        editing = false
        editingProfile = null
        pendingFolderId = null
    }

    fun persist(list: List<ClientProfile>) {
        profiles = list
        ProfileStore.save(context.applicationContext, list)
    }

    fun deleteProfile(id: String) {
        persist(profiles.filterNot { it.id == id || it.linkedTo == id })
        if (activeId == id) activeId = null
        if (actionId == id) actionId = null
        folder = null
    }

    // 切换配置 / 点刷新：连上之后立刻取同步文件夹与其状态
    LaunchedEffect(activeId, actionId, reloadKey) {
        val infoProfile = profiles.firstOrNull { it.id == activeId }
        val actionProfile = profiles.firstOrNull { it.id == actionId }
        if (infoProfile == null && actionProfile == null) {
            channel = null
            folders = emptyList()
            statuses = emptyMap()
            folder = null
            status = null
            return@LaunchedEffect
        }
        status = "正在连接…"
        val infoCh = withContext(Dispatchers.IO) {
            runCatching { infoProfile?.build(context.applicationContext) }.getOrNull()
        }
        val actCh = withContext(Dispatchers.IO) {
            runCatching { actionProfile?.build(context.applicationContext) }.getOrNull()
        }
        val combined: SyncChannel? = when {
            infoCh != null && actCh != null -> CompositeChannel(infoCh, actCh)
            infoCh != null -> infoCh
            actCh != null -> actCh
            else -> null
        }
        val result = combined?.let {
            withContext(Dispatchers.IO) { runCatching { it.test() }.getOrNull() }
        }
        val list = combined?.let {
            withContext(Dispatchers.IO) { runCatching { it.folders() }.getOrDefault(emptyList()) }
        } ?: emptyList()
        val statusMap = HashMap<String, FolderStatus>()
        for (item in list) {
            val st = withContext(Dispatchers.IO) {
                runCatching { combined?.status(item.id) }.getOrNull()
            }
            if (st != null) {
                statusMap[item.id] = st
            }
        }
        channel = combined
        folders = list
        statuses = statusMap
        folder = null
        status = when {
            combined == null -> "配置无效，请重新添加"
            result == null -> "连接失败"
            result.ok -> result.summary
            else -> result.error
        }
    }

    if (editing) {
        val initial = editingProfile
        ConnectionScreen(
            title = when {
                initial != null -> "编辑配置：${initial.name}"
                else -> "给文件夹配文件通道"
            },
            initial = initial,
            onCancel = {
                editing = false
                editingProfile = null
                pendingFolderId = null
            },
            onConnected = { _, profile ->
                val saved = when {
                    initial != null -> profile.copy(
                        id = initial.id,
                        role = initial.role,
                        linkedTo = initial.linkedTo,
                        folderId = initial.folderId,
                    )

                    else -> profile.copy(
                        linkedTo = activeId.orEmpty(),
                        folderId = pendingFolderId.orEmpty(),
                        role = ProfileRole.ACTION,
                    )
                }
                if (initial != null) {
                    persist(profiles.map { if (it.id == initial.id) saved else it })
                } else {
                    persist(profiles + saved)
                }
                editing = false
                editingProfile = null
                pendingFolderId = null
                actionId = saved.id
                reloadKey++
            },
        )
        return
    }

    val activeName = profiles.firstOrNull { it.id == activeId }?.name
        ?: profiles.firstOrNull { it.id == actionId }?.name
        ?: "SyncScope"

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                ) {
                    // 置顶：添加配置 / 编辑表单
                    if (editorOpen) {
                        ProfileEditor(
                            initial = editorInitial,
                            onCancel = {
                                editorOpen = false
                                editorInitial = null
                            },
                            onSaved = { saved ->
                                val initial = editorInitial
                                if (initial != null) {
                                    persist(profiles.map { if (it.id == initial.id) saved else it })
                                } else {
                                    persist(profiles + saved)
                                }
                                editorOpen = false
                                editorInitial = null
                                if (saved.role == ProfileRole.INFO) {
                                    activeId = saved.id
                                    actionId = profiles.firstOrNull {
                                        it.role == ProfileRole.ACTION && it.linkedTo == saved.id
                                    }?.id
                                } else {
                                    actionId = saved.id
                                }
                                folder = null
                                reloadKey++
                                scope.launch { drawerState.close() }
                            },
                        )
                    } else {
                        NavigationDrawerItem(
                            label = { Text("＋ 添加配置") },
                            selected = false,
                            onClick = {
                                editorInitial = null
                                editorOpen = true
                            },
                        )
                    }

                    profiles.forEach { profile ->
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
                                selected = profile.id == activeId || profile.id == actionId,
                                badge = {
                                    Text(
                                        profile.roleLabel(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                onClick = {
                                    if (profile.role == ProfileRole.INFO) {
                                        activeId = profile.id
                                        actionId = profiles.firstOrNull {
                                            it.role == ProfileRole.ACTION && it.linkedTo == profile.id
                                        }?.id
                                    } else {
                                        actionId = profile.id
                                        if (profile.linkedTo.isNotBlank()) {
                                            activeId = profile.linkedTo
                                        }
                                    }
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
                                Icon(Icons.Default.Edit, contentDescription = "编辑该配置")
                            }
                        }
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(activeName) },
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
                        val removable = activeId ?: actionId
                        if (removable != null) {
                            IconButton(onClick = { deleteProfile(removable) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除当前配置")
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
                val active = channel
                val current = folder
                when {
                    active == null -> {
                        Text(
                            "还没有配置。点左上角菜单 →「＋ 添加配置」。",
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    current == null -> {
                        FolderPickScreen(
                            channel = active,
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
                        FolderScreen(
                            channel = active,
                            folder = current,
                            folderStatus = statuses[current.id],
                            initialView = folderView,
                            onBack = { folder = null },
                            onAttachFileChannel = {
                                pendingFolderId = current.id
                                editingProfile = null
                                editingRole = ProfileRole.ACTION
                                editing = true
                            },
                        )
                    }
                }
            }
        }
    }
}
