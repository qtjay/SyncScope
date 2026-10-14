package com.qtjay.syncscope.core.model

/**
 * 文件夹的同步状态（对应 Syncthing 的 /rest/db/status）。
 *
 * 只有 REST 通道拿得到；FTP/SMB/本地目录这些文件通道返回 null，
 * UI 上就不显示状态行，不拿假数据糊弄。
 */
data class FolderStatus(
    val state: String = "",
    val localFiles: Long = 0,
    val globalFiles: Long = 0,
    val needFiles: Long = 0,
    val localBytes: Long = 0,
    val globalBytes: Long = 0,
    val needBytes: Long = 0,
    val inSyncFiles: Long = 0,
) {

    /** 完成百分比：没有数据时按"是否还有待同步项"给 100/0。 */
    fun completionPercent(): Int {
        if (globalBytes <= 0L) {
            return if (needFiles == 0L) 100 else 0
        }
        val done = globalBytes - needBytes
        return (done * 100 / globalBytes).toInt().coerceIn(0, 100)
    }

    fun stateLabel(): String = when (state) {
        "idle" -> "已同步"
        "scanning" -> "扫描中"
        "syncing" -> "同步中"
        "sync-preparing" -> "准备同步"
        "cleaning" -> "清理中"
        "error" -> "出错"
        "unknown" -> "未连接"
        "" -> "未知"
        else -> state
    }

    fun fileSummary(): String {
        return if (globalFiles <= 0L) {
            "尚未扫描到文件"
        } else {
            "$localFiles / $globalFiles 个文件"
        }
    }
}
