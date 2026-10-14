package com.qtjay.syncscope.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
 * 应用外壳：左侧抽屉管理多个客户端配置，随时切换。
 *
 * 每个配置可以是信息面（REST）或动作面（FTP/SMB/本地目录），
 * 两者都在时合成 CompositeChannel —— 看走 REST，删走文件通道。
 * 连接成功后会立刻把同步文件夹列表取回来，并提示补一个文件通道。
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
    var status by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editingRole by remember { mutableStateOf(ProfileRole.INFO) }
    /** 抽屉里哪个客户端被展开。 */
    var expandedId by remember { mutableStateOf<String?>(null) }
    /** 正在为哪个文件夹配文件通道（文件通道按文件夹归属）。 */
    var pendingFolderId by remember { mutableStateOf<String?>(null) }

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

    // 切换配置 / 点刷新：连上之后立刻取同步文件夹信息
    LaunchedEffect(activeId, actionId, reloadKey) {
        val infoProfile = profiles.firstOrNull { it.id == activeId }
        val actionProfile = profiles.firstOrNull { it.id == actionId }
        if (infoProfile == null && actionProfile == null) {
            channel = null
            folders = emptyList()
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
        ConnectionScreen(
            title = if (editingRole == ProfileRole.ACTION) "添加文件通道" else "添加客户端配置",
            onCancel = { editing = false },
            onConnected = { _, profile ->
                val saved = if (profile.role == ProfileRole.ACTION) {
                    profile.copy(
                        linkedTo = activeId.orEmpty(),
                        folderId = pendingFolderId.orEmpty(),
                    )
                } else {
                    profile
                }
                persist(profiles + saved)
                editing = false
                folder = null
                pendingFolderId = null
                if (saved.role == ProfileRole.INFO) {
                    activeId = saved.id
                    expandedId = saved.id
                    actionId = profiles.firstOrNull {
                        it.role == ProfileRole.ACTION && it.linkedTo == saved.id
                    }?.id
                } else {
                    actionId = saved.id
                }
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
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    Text(
                        "客户端配置",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    profiles.forEach { profile ->
                        if (profile.role != ProfileRole.INFO) {
                            return@forEach
                        }
                        val expanded = expandedId == profile.id
                        NavigationDrawerItem(
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
                            badge = {
                                Text(
                                    if (expanded) "▾" else "▸",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            onClick = {
                                if (activeId != profile.id) {
                                    activeId = profile.id
                                    folder = null
                                }
                                expandedId = if (expanded) null else profile.id
                            },
                        )

                        // 展开后列出该客户端的共享文件夹，以及每个文件夹各自的文件通道
                        if (expanded && profile.id == activeId) {
                            folders.forEach { item ->
                                val status = statuses[item.id]
                                NavigationDrawerItem(
                                    label = {
                                        Column(modifier = Modifier.padding(start = 12.dp)) {
                                            Text(item.displayName)
                                            Text(
                                                if (status != null) {
                                                    "${status.stateLabel()} · " +
                                                        "${status.fileSummary()} · " +
                                                        "${status.completionPercent()}%"
                                                } else {
                                                    item.path.ifBlank { "点开可设置选择性同步" }
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    },
                                    selected = folder?.id == item.id,
                                    onClick = {
                                        folder = item
                                        // 切到该文件夹时，自动带上它自己的文件通道
                                        actionId = profiles.firstOrNull {
                                            it.role == ProfileRole.ACTION &&
                                                it.linkedTo == profile.id &&
                                                it.folderId == item.id
                                        }?.id
                                        scope.launch { drawerState.close() }
                                    },
                                )

                                val channels = profiles.filter {
                                    it.role == ProfileRole.ACTION &&
                                        it.linkedTo == profile.id &&
                                        it.folderId == item.id
                                }
                                channels.forEach { channel ->
                                    NavigationDrawerItem(
                                        label = {
                                            Text(
                                                "　文件通道：${channel.name}",
                                                modifier = Modifier.padding(start = 12.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        },
                                        selected = actionId == channel.id,
                                        onClick = {
                                            actionId = channel.id
                                            folder = item
                                            scope.launch { drawerState.close() }
                                        },
                                    )
                                }

                                NavigationDrawerItem(
                                    label = {
                                        Text(
                                            "　＋ 给「${item.displayName}」配文件通道",
                                            modifier = Modifier.padding(start = 12.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    },
                                    selected = false,
                                    onClick = {
                                        pendingFolderId = item.id
                                        editingRole = ProfileRole.ACTION
                                        editing = true
                                        scope.launch { drawerState.close() }
                                    },
                                )
                            }
                        }
                    }

                    // 还没挂到任何客户端下的文件通道独立列出
                    profiles.forEach { profile ->
                        if (profile.role == ProfileRole.ACTION && profile.linkedTo.isBlank()) {
                            NavigationDrawerItem(
                                label = {
                                    Column {
                                        Text(profile.name)
                                        Text(
                                            profile.subtitle(),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                },
                                selected = profile.id == actionId,
                                badge = {
                                    Text(
                                        profile.roleLabel(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                onClick = {
                                    actionId = profile.id
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
                    }

                    NavigationDrawerItem(
                        label = { Text("＋ 添加客户端配置") },
                        selected = false,
                        onClick = {
                            editingRole = ProfileRole.INFO
                            editing = true
                            scope.launch { drawerState.close() }
                        },
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(activeName) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "打开配置列表")
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
                            "还没有配置。点左上角菜单 →「＋ 添加客户端配置」。",
                            modifier = Modifier.padding(16.dp),
                        )
                    }

                    current == null -> {
                        FolderPickScreen(
                            channel = active,
                            folders = folders,
                            onPick = { folder = it },
                            onAddFileChannel = {
                                editingRole = ProfileRole.ACTION
                                editing = true
                            },
                            onReload = { reloadKey++ },
                        )
                    }

                    else -> {
                        FolderScreen(
                            channel = active,
                            folder = current,
                            folderStatus = statuses[current.id],
                            onBack = { folder = null },
                            onAttachFileChannel = {
                                pendingFolderId = current.id
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
