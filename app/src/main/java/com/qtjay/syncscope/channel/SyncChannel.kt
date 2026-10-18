package com.qtjay.syncscope.channel

import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import com.qtjay.syncscope.core.model.FolderStatus

/**
 * 删除结果。
 *
 * [reasons] 逐条给出失败原因（相对路径 → 服务端返回或异常信息）。
 * 之前只报"失败了"却不说为什么，用户完全没法自查，所以把原因带上。
 */
data class DeleteResult(
    val deleted: List<String>,
    val failed: List<String>,
    val reasons: Map<String, String> = emptyMap(),
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
 * 某个相对路径在"远端"的状态。
 *
 * <p>这里说的远端是全局模型（REST 的 /rest/db/file）：即使本机没下载，
 * 只要集群里有别的设备持有，exists 也为 true。删除前用它做存在性校验。
 */
data class RemoteInfo(
    val exists: Boolean,
    val directory: Boolean = false,
    /** 持有该文件副本的设备 ID 列表。 */
    val availability: List<String> = emptyList(),
    /** 全局模型记录的大小；小于 0 表示这一侧的通道拿不到（SMB/FTP 等没有这条信息）。 */
    val size: Long = -1L,
    /** 全局模型记录的修改时间（原样字符串），拿不到时为空。 */
    val modified: String = "",
    /** 全局模型是否已把它标记为忽略。 */
    val ignored: Boolean = false,
) {
    /**
     * 除本机之外是否还有其它设备持有它。
     *
     * 必须排除本机：要删的正是本机这一份，本机持有并不代表删掉之后还有备份。
     * 只看"availability 非空"会把"仅此一份"误判成安全，那正是会丢数据的地方。
     */
    fun hasRemoteCopy(localDeviceId: String?): Boolean {
        return availability.any { it != localDeviceId }
    }

    /**
     * 与本地这一份比对状态是否一致。
     *
     * @return true 一致、false 不一致、null 信息不足无法判断（此时不能当作一致）。
     */
    fun matchesLocal(localSize: Long): Boolean? {
        if (size < 0L || localSize < 0L) {
            return null
        }
        return size == localSize
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

    /**
     * 删除文件/目录，返回成功与失败清单。
     *
     * @param onProgress 已处理条数 / 总条数；大目录删除要几十秒，没有进度等于卡死观感。
     */
    fun delete(
        folder: Folder,
        paths: List<String>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): DeleteResult

    /**
     * 删除前的自检：把"这条通道会去删哪个真实路径、现在看不看得见"讲清楚。
     *
     * <p>之前用户只看到"删除失败"四个字，无从判断是路径根配错、权限不足还是文件真不在，
     * 所以在确认框里把真实目标路径打出来，让用户一眼看出根配得对不对。
     *
     * <p>批量接口：逐条查意味着每条路径一次完整握手，目录一多就慢到没法用。
     */
    fun diagnoseBatch(folder: Folder, paths: List<String>): Map<String, String> =
        paths.associateWith { "该通道不支持自检" }

    /**
     * 查询某个相对路径在远端是否存在，用于删除前的存在性校验。
     *
     * 返回 null 表示这个通道判断不出来（比如只有 FTP/SMB，看不到全局模型），
     * 调用方应当按"未确认存在"处理 —— 也就是不要删。
     */
    fun remoteInfo(folderId: String, path: String): RemoteInfo? = null

    /**
     * 批量核对多个路径，返回"路径 → 状态"（查不出来则为 null）。
     *
     * 默认实现是逐条调用 [remoteInfo]，对 FTP/SMB 意味着每条路径一次完整握手；
     * 这两个通道已重写成单连接批量查询，大目录时快一个数量级。
     */
    fun remoteInfoBatch(folderId: String, paths: List<String>): Map<String, RemoteInfo?> =
        paths.associateWith { runCatching { remoteInfo(folderId, it) }.getOrNull() }

    /**
     * 目标设备的 Syncthing 设备 ID（只有 REST 通道拿得到）。
     * 拿不到或还没连过时返回 null。
     */
    fun deviceId(): String? = null

    /**
     * 目标设备自己报的设备名（只有 REST 通道拿得到）。
     * 拿不到返回 null —— 调用方要能接受没有名字、只用短 ID 的情况。
     */
    fun deviceName(): String? = null

    /** 请求重新扫描（REST 才有实际作用）。 */
    fun rescan(folderId: String)

    /**
     * 读取文件夹的同步状态（文件数、字节数、同步进度）。
     * 只有 REST 通道能提供，其余通道返回 null。
     */
    fun status(folderId: String): FolderStatus? = null
}
