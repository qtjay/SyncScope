package com.qtjay.syncscope.channel

import android.util.Log
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import com.qtjay.syncscope.core.model.FolderStatus

/**
 * 混合通道：把"信息面"与"动作面"两个通道合成一个，互补短板。
 *
 * | 能力                | 信息面 REST | 动作面 FTP/SMB/本地 |
 * |---------------------|-------------|---------------------|
 * | 看到远端未下载的文件 | 能          | 不能                |
 * | 读写 .stignore      | 能          | 能                  |
 * | 触发重新扫描        | 能          | 不能                |
 * | 删除文件            | 不能        | 能                  |
 *
 * 分工原则：
 *  - 看（browse / ignores）优先走信息面，拿不到再退到动作面；
 *  - 写（writeIgnores）优先走信息面，这样目标客户端立刻生效，失败再退到动作面；
 *  - 删（delete）只走动作面 —— REST 没有删除接口，做不到就是做不到；
 *  - 重新扫描只有信息面能做。
 */
class CompositeChannel(
    val infoChannel: SyncChannel?,
    val actionChannel: SyncChannel?,
) : SyncChannel {

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = infoChannel?.capabilities?.canSeeRemoteOnlyFiles ?: false,
        canTriggerRescan = infoChannel?.capabilities?.canTriggerRescan ?: false,
        canDeleteTargetFiles = actionChannel?.capabilities?.canDeleteTargetFiles
            ?: infoChannel?.capabilities?.canDeleteTargetFiles
            ?: false,
    )

    override fun test(): TestResult {
        val results = ArrayList<Pair<String, TestResult>>()
        infoChannel?.let { results.add("信息面" to it.test()) }
        actionChannel?.let { results.add("动作面" to it.test()) }
        if (results.isEmpty()) {
            return TestResult.fail("两个通道都没有配置")
        }
        val text = results.joinToString("；") { (name, r) ->
            if (r.ok) "$name：${r.summary}" else "$name：${r.error}"
        }
        return if (results.all { it.second.ok }) TestResult.ok(text) else TestResult.fail(text)
    }

    override fun folders(): List<Folder> {
        return firstNotEmpty(
            { infoChannel?.folders() },
            { actionChannel?.folders() },
        )
    }

    override fun tree(folderId: String, prefix: String): List<BrowseEntry> {
        val fromInfo = infoChannel?.let { runCatching { it.tree(folderId, prefix) }.getOrNull() }
        if (!fromInfo.isNullOrEmpty()) {
            return fromInfo
        }
        return actionChannel?.let {
            runCatching { it.tree(folderId, prefix) }.getOrNull()
        } ?: emptyList()
    }

    override fun ignores(folderId: String): List<String> {
        val fromInfo = infoChannel?.let { runCatching { it.ignores(folderId) }.getOrNull() }
        if (!fromInfo.isNullOrEmpty()) {
            return fromInfo
        }
        return actionChannel?.let {
            runCatching { it.ignores(folderId) }.getOrNull()
        } ?: emptyList()
    }

    override fun writeIgnores(folderId: String, lines: List<String>): Boolean {
        // 优先让信息面写：目标客户端能立刻感知并重新扫描
        infoChannel?.let {
            if (runCatching { it.writeIgnores(folderId, lines) }.getOrDefault(false)) {
                return true
            }
        }
        return actionChannel?.let {
            runCatching { it.writeIgnores(folderId, lines) }.getOrDefault(false)
        } ?: false
    }

    /** 真正动手删文件的那条通道：只有动作面做得到。 */
    private fun deleteTarget(): SyncChannel? =
        actionChannel?.takeIf { it.capabilities.canDeleteTargetFiles }
            ?: infoChannel?.takeIf { it.capabilities.canDeleteTargetFiles }

    /**
     * 相对路径以文件通道自己的根目录为准：REST 给的 folder.path 是目标设备上的
     * 绝对路径（如 D 盘的 sync、/volume1/sync），直接拼进 SMB/FTP 路径会错，
     * 所以用空 path 让它回落到通道自己配置的根目录。
     */
    private fun relax(folder: Folder, target: SyncChannel): Folder =
        if (target === infoChannel) folder else folder.copy(path = "")

    override fun delete(
        folder: Folder,
        paths: List<String>,
        onProgress: ((Int, Int) -> Unit)?,
    ): DeleteResult {
        val target = deleteTarget()
        if (target == null) {
            Log.w(TAG, "没有可用的文件通道，删除跳过；只写入了忽略规则")
            return DeleteResult(
                emptyList(),
                paths,
                paths.associateWith { "没有可用的文件通道（REST 接口不能删文件）" },
            )
        }
        return target.delete(relax(folder, target), paths, onProgress)
    }

    override fun diagnoseBatch(folder: Folder, paths: List<String>): Map<String, String> {
        val target = deleteTarget()
            ?: return paths.associateWith { "没有可用的文件通道，删不掉" }
        return runCatching { target.diagnoseBatch(relax(folder, target), paths) }
            .getOrDefault(paths.associateWith { "自检失败" })
    }

    override fun rescan(folderId: String) {
        infoChannel?.rescan(folderId) ?: actionChannel?.rescan(folderId)
    }

    /** 远端文件状态优先问信息面；没有信息面才退到动作面（比如只配了 SMB/FTP）。 */
    override fun remoteInfo(folderId: String, path: String): RemoteInfo? {
        val fromInfo = infoChannel?.let {
            runCatching { it.remoteInfo(folderId, path) }.getOrNull()
        }
        if (fromInfo != null) {
            return fromInfo
        }
        return actionChannel?.let {
            runCatching { it.remoteInfo(folderId, path) }.getOrNull()
        }
    }

    override fun remoteInfoBatch(folderId: String, paths: List<String>): Map<String, RemoteInfo?> {
        val fromInfo = infoChannel?.let {
            runCatching { it.remoteInfoBatch(folderId, paths) }.getOrNull()
        }
        if (fromInfo != null && fromInfo.values.any { it != null }) {
            return fromInfo
        }
        return actionChannel?.let {
            runCatching { it.remoteInfoBatch(folderId, paths) }.getOrNull()
        } ?: emptyMap()
    }

    /** 设备 ID 只有信息面（REST）拿得到。 */
    override fun deviceId(): String? = infoChannel?.deviceId()

    /** 状态只有信息面（REST）拿得到。 */
    override fun status(folderId: String): FolderStatus? {
        return infoChannel?.status(folderId) ?: actionChannel?.status(folderId)
    }

    private fun <T> firstNotEmpty(vararg providers: () -> List<T>?): List<T> {
        for (provider in providers) {
            val value = provider()
            if (!value.isNullOrEmpty()) {
                return value
            }
        }
        return emptyList()
    }

    companion object {
        private const val TAG = "CompositeChannel"
    }
}
