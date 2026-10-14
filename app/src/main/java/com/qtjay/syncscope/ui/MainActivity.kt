package com.qtjay.syncscope.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import com.qtjay.syncscope.channel.CompositeChannel
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.Folder

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

private enum class Screen { CONNECT, PICK, FOLDER }

/**
 * 应用主流程。
 *
 * 支持"混合模式"：信息面通道（REST）与动作面通道（FTP/SMB/本地目录）各连一个，
 * 两个都在时合成 CompositeChannel，看用 REST、删用文件通道。
 */
@Composable
fun SyncScopeApp() {
    var screen by remember { mutableStateOf(Screen.CONNECT) }
    var infoChannel by remember { mutableStateOf<SyncChannel?>(null) }
    var actionChannel by remember { mutableStateOf<SyncChannel?>(null) }
    var folder by remember { mutableStateOf<Folder?>(null) }

    val channel: SyncChannel? = when {
        infoChannel != null && actionChannel != null ->
            CompositeChannel(infoChannel, actionChannel)

        infoChannel != null -> infoChannel
        actionChannel != null -> actionChannel
        else -> null
    }

    val active = channel
    val picked = folder

    if (active == null || screen == Screen.CONNECT) {
        ConnectionScreen(
            title = if (active == null) "SyncScope" else "添加文件通道",
            onCancel = if (active != null) {
                { screen = if (picked != null) Screen.FOLDER else Screen.PICK }
            } else {
                null
            },
            onConnected = { connected, role ->
                if (role == ChannelRole.INFO) {
                    infoChannel = connected
                } else {
                    actionChannel = connected
                }
                screen = if (picked != null) Screen.FOLDER else Screen.PICK
            },
        )
    } else if (picked == null) {
        FolderPickScreen(
            channel = active,
            onPick = { selected ->
                folder = selected
                screen = Screen.FOLDER
            },
            onBack = {
                infoChannel = null
                actionChannel = null
                screen = Screen.CONNECT
            },
        )
    } else {
        FolderScreen(
            channel = active,
            folder = picked,
            onBack = {
                folder = null
                screen = Screen.PICK
            },
            onAttachFileChannel = { screen = Screen.CONNECT },
        )
    }
}
