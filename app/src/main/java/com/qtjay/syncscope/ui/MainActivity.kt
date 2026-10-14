package com.qtjay.syncscope.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import com.qtjay.syncscope.channel.Capabilities
import com.qtjay.syncscope.channel.DeleteResult
import com.qtjay.syncscope.channel.FileInfo
import com.qtjay.syncscope.channel.RestChannel
import com.qtjay.syncscope.channel.ShareChannel
import com.qtjay.syncscope.channel.SyncChannel
import com.qtjay.syncscope.core.model.BrowseEntry
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

private enum class Screen { CONNECTION, FOLDER_PICK, SELECTION }

@Composable
fun SyncScopeApp() {
    var screen by remember { mutableStateOf(Screen.CONNECTION) }
    var channel by remember { mutableStateOf<SyncChannel?>(null) }
    var folder by remember { mutableStateOf<Folder?>(null) }

    val activeChannel = channel
    val pickedFolder = folder

    if (activeChannel == null) {
        ConnectionScreen(onConnected = { connected ->
            channel = connected
            screen = Screen.FOLDER_PICK
        })
    } else if (pickedFolder == null) {
        FolderPickScreen(
            channel = activeChannel,
            onPick = { picked ->
                folder = picked
                screen = Screen.SELECTION
            },
            onBack = {
                channel = null
                screen = Screen.CONNECTION
            },
        )
    } else {
        FolderScreen(
            channel = activeChannel,
            folder = pickedFolder,
            onBack = {
                folder = null
                screen = Screen.FOLDER_PICK
            },
        )
    }
}
