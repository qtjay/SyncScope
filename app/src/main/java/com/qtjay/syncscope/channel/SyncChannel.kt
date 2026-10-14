package com.qtjay.syncscope.channel

import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder

/** 删除结果。 */
data class DeleteResult(
    val deleted: List<String>,
    val failed: List<String>,
)

/** 单个文件在全局模型中的信息。 */
data class FileInfo(
    val name: String,
    val availability: List<String>,
) {
    /** 除本机之外是否还有其它设备持有该文件。 */
    fun hasRemoteCopy(localDeviceId: String?): Boolean {
        return availability.any { it != localDeviceId }
    }
}

/** 通道能力，用于决定 UI 能提供哪些功能。 */
data class Capabilities(
    /** 能否看到远端有、但本机还没下载的文件（REST 可以，SMB/FTP 不行）。 */
    val canSeeRemoteOnlyFiles: Boolean,
    /** 能否主动触发重新扫描（REST 可以，SMB/FTP 只能等 watcher）。 */
    val canTriggerRescan: Boolean,
)

/**
 * 与目标 Syncthing 客户端之间的数据通道。
 *
 * <p>两种实现：REST API（优先，功能完整）与 SMB/FTP 共享目录（降级，只能看到本地已有文件）。
 */
interface SyncChannel {
    val capabilities: Capabilities

    /** 测试连通性，成功返回本机设备 ID（可能为空）。 */
    fun test(): String?

    /** 列出所有共享文件夹。 */
    fun folders(): List<Folder>

    /** 读取某一层目录内容，prefix 为空表示根目录。 */
    fun tree(folderId: String, prefix: String): List<BrowseEntry>

    /** 读取现有忽略规则。 */
    fun ignores(folderId: String): List<String>

    /** 写入忽略规则。 */
    fun writeIgnores(folderId: String, lines: List<String>): Boolean

    /** 删除本机上的文件/目录，返回成功与失败清单。 */
    fun delete(folder: Folder, paths: List<String>): DeleteResult

    /** 请求重新扫描（REST 才有实际作用）。 */
    fun rescan(folderId: String)
}
