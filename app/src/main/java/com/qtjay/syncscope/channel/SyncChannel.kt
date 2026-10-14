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

/**
 * 连通性测试结果。
 *
 * 之前 test() 只返回 String?（失败即 null），失败原因全被吞掉，
 * 用户点了"连接"什么反馈都看不到。现在把人类可读的结果与原因都带出来。
 */
data class TestResult(
    val ok: Boolean,
    /** 成功时的摘要，例如"设备已连接，ID ABCD123…"。 */
    val summary: String,
    /** 失败时的原因，例如"API key 不正确（HTTP 401）"。 */
    val error: String?,
) {
    companion object {
        fun ok(summary: String): TestResult = TestResult(true, summary, null)
        fun fail(error: String): TestResult = TestResult(false, "", error)
    }
}

/** 通道能力，用于决定 UI 能提供哪些功能。 */
data class Capabilities(
    /** 能否看到远端有、但本机还没下载的文件（REST 可以，SMB/FTP 不行）。 */
    val canSeeRemoteOnlyFiles: Boolean,
    /** 能否主动触发重新扫描（REST 可以，SMB/FTP 只能等 watcher）。 */
    val canTriggerRescan: Boolean,
    /**
     * 能否删除"目标设备"上的文件。
     *
     * REST 通道为 false：Syncthing 官方 REST API 并没有删除文件的端点，
     * 只能靠 SMB/FTP/本地目录这类文件通道动手。
     */
    val canDeleteTargetFiles: Boolean,
)

/**
 * 与目标 Syncthing 客户端之间的数据通道。
 *
 * <p>两类实现：
 * <ul>
 *   <li>信息面 —— REST API：完整视图、读写忽略规则、触发重扫，但不能删文件；
 *   <li>动作面 —— 本地目录 / SMB / FTP：能写 .stignore 并删除文件，但看不到远端未下载的内容。
 * </ul>
 * 两种同时使用即为"混合模式"，由 CompositeChannel 按能力分工。
 */
interface SyncChannel {
    val capabilities: Capabilities

    /**
     * 测试连通性。成功时 [TestResult.ok] 为 true 且带有可读摘要；
     * 失败时必须给出人类能看懂的原因，不要吞掉异常。
     */
    fun test(): TestResult

    /** 列出所有共享文件夹。 */
    fun folders(): List<Folder>

    /** 读取某一层目录内容，prefix 为空表示根目录。 */
    fun tree(folderId: String, prefix: String): List<BrowseEntry>

    /** 读取现有忽略规则。 */
    fun ignores(folderId: String): List<String>

    /** 写入忽略规则。 */
    fun writeIgnores(folderId: String, lines: List<String>): Boolean

    /** 删除文件/目录，返回成功与失败清单。 */
    fun delete(folder: Folder, paths: List<String>): DeleteResult

    /** 请求重新扫描（REST 才有实际作用）。 */
    fun rescan(folderId: String)
}
