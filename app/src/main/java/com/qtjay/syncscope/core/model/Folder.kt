package com.qtjay.syncscope.core.model

/**
 * 目标 Syncthing 客户端上的一个共享文件夹。
 */
data class Folder(
    val id: String,
    val label: String = "",
    val path: String = "",
) {
    val displayName: String
        get() = label.ifBlank { id }
}
