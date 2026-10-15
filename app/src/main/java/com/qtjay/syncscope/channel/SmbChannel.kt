package com.qtjay.syncscope.channel

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet

/**
 * SMB2/SMB3 文件通道：直接读写目标设备上的 `.stignore`，并删除取消勾选的文件。
 *
 * 与 FTP 一样属于"动作面"通道；REST 只能看、不能删，删除必须靠这类文件通道。
 * 只支持 SMB2/3 —— SMB1 已被淘汰，服务端若只开 SMB1 会连不上，
 * 这种情况下请改用 FTP，或在服务端开启 SMB2。
 *
 * API 说明：mssmb2 / msdtyp 的类已经打包在 smbj 的 jar 里，不需要另引依赖。
 */
class SmbChannel(
    private val host: String,
    private val shareName: String,
    private val user: String,
    private val password: String,
    private val domain: String = "",
    private val path: String = "",
    private val port: Int = 445,
) : SyncChannel {

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = false,
        canTriggerRescan = false,
        canDeleteTargetFiles = true,
    )

    override fun test(): TestResult {
        return try {
            withShare<TestResult> { _ ->
                TestResult.ok("SMB 已连接：\\\\$host\\$shareName${suffix()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMB 连接测试失败", e)
            TestResult.fail(describe(e))
        }
    }

    override fun folders(): List<Folder> {
        return listOf(
            Folder(
                id = "smb",
                label = "\\\\$host\\$shareName${suffix()}",
                path = path,
            ),
        )
    }

    override fun tree(folderId: String, prefix: String): List<BrowseEntry> {
        return try {
            withShare<List<BrowseEntry>> { share ->
                val dir = join(prefix)
                val listed = share.list(dir)
                listed.mapNotNull { info ->
                    val name = info.fileName ?: return@mapNotNull null
                    if (name.isEmpty() || name == "." || name == "..") {
                        null
                    } else {
                        BrowseEntry().apply {
                            this.name = name
                            // 目录项自带属性，不再为每个条目多发一次 folderExists 请求
                            val isDir = info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0L
                            type = if (isDir) {
                                BrowseEntry.TYPE_DIRECTORY
                            } else {
                                "FILE_INFO_TYPE_FILE"
                            }
                            size = info.endOfFile
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMB 列目录失败: $prefix", e)
            emptyList()
        }
    }

    /** SMB 同样看不到全局模型，按共享里的实际文件判断。 */
    override fun remoteInfo(folderId: String, path: String): RemoteInfo? {
        return try {
            withShare<RemoteInfo> { share ->
                val target = join(path)
                when {
                    share.folderExists(target) -> RemoteInfo(exists = true, directory = true)
                    share.fileExists(target) -> RemoteInfo(exists = true, directory = false)
                    else -> RemoteInfo(exists = false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMB 查询远端状态失败: $path", e)
            null
        }
    }

    override fun ignores(folderId: String): List<String> {
        return try {
            withShare<List<String>> { share ->
                val target = join(STIGNORE)
                if (!share.fileExists(target)) {
                    emptyList()
                } else {
                    val file = share.openFile(
                        target,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        null,
                        SHARE_ACCESS,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
                    val text: String = try {
                        file.inputStream.bufferedReader(Charsets.UTF_8).readText()
                    } finally {
                        runCatching { file.close() }
                    }
                    text.split("\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMB 读取 .stignore 失败", e)
            emptyList()
        }
    }

    override fun writeIgnores(folderId: String, lines: List<String>): Boolean {
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        return try {
            withShare<Boolean> { share ->
                val target = join(STIGNORE)
                val file = share.openFile(
                    target,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SHARE_ACCESS,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null,
                )
                try {
                    file.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                } finally {
                    runCatching { file.close() }
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMB 写入 .stignore 失败", e)
            false
        }
    }

    override fun delete(folder: Folder, paths: List<String>): DeleteResult {
        val base = folder.path.ifBlank { path }
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        val reasons = LinkedHashMap<String, String>()
        try {
            withShare<Unit> { share ->
                // 短路径优先：先删父目录，子项随之消失，
                // 避免把"已被父目录带走"误报成失败。
                for (relative in paths.sortedBy { it.length }) {
                    val target = joinIn(base, relative)
                    val ok = try {
                        if (share.folderExists(target)) {
                            // 递归删目录；少数服务端对 rmdir 有额外限制，退一步按文件删再试
                            runCatching { share.rmdir(target, true) }
                                .getOrElse { share.rm(target) }
                        } else {
                            // 反过来：列不出来但其实是目录的情况，按目录删再试
                            runCatching { share.rm(target) }
                                .getOrElse { share.rmdir(target, true) }
                        }
                        true
                    } catch (e: Exception) {
                        reasons[relative] = e.message ?: e.javaClass.simpleName
                        false
                    }
                    if (ok) {
                        deleted.add(relative)
                    } else {
                        failed.add(relative)
                        if (!reasons.containsKey(relative)) {
                            reasons[relative] = "SMB 删除失败，服务端未给出原因（路径 $target）"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SMB 删除失败", e)
            val why = e.message ?: e.javaClass.simpleName
            for (relative in paths) {
                if (deleted.contains(relative)) {
                    continue
                }
                if (!failed.contains(relative)) {
                    failed.add(relative)
                }
                reasons[relative] = why
            }
        }
        return DeleteResult(deleted, failed, reasons)
    }

    /**
     * 批量核对：所有路径共用一次 SMB 会话。
     * 默认实现是每条路径各建一次连接，目录多的时候握手开销比删除本身还大。
     */
    override fun remoteInfoBatch(folderId: String, paths: List<String>): Map<String, RemoteInfo?> {
        if (paths.isEmpty()) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, RemoteInfo?>()
        for (path in paths) {
            out[path] = null
        }
        return try {
            withShare<Unit> { share ->
                for (path in paths) {
                    val target = join(path)
                    out[path] = when {
                        share.folderExists(target) -> RemoteInfo(exists = true, directory = true)
                        share.fileExists(target) -> RemoteInfo(exists = true, directory = false)
                        else -> RemoteInfo(exists = false)
                    }
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "SMB 批量核对远端状态失败", e)
            out
        }
    }

    /** 把真实要删的 UNC 路径打出来，顺带说明现在看不看得见它。 */
    override fun diagnoseBatch(folder: Folder, paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) {
            return emptyMap()
        }
        val base = folder.path.ifBlank { this.path }
        val out = LinkedHashMap<String, String>()
        return try {
            withShare<Map<String, String>> { share ->
                for (path in paths) {
                    val target = joinIn(base, path)
                    val unc = "\\\\$host\\$shareName" +
                        if (target.isEmpty()) "" else "\\$target"
                    out[path] = when {
                        target.isEmpty() -> "$unc\n（通道根目录，即配置的共享名+子路径）"
                        share.folderExists(target) -> "$unc\n（目录，能看到）"
                        share.fileExists(target) -> "$unc\n（文件，能看到）"
                        else -> "$unc\n看不到这一项，删除会失败：" +
                            "检查共享名/子路径是否就是同步文件夹根目录"
                    }
                }
                out
            }
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            for (path in paths) {
                out[path] = "查询失败：$why"
            }
            out
        }
    }

    override fun rescan(folderId: String) {
        Log.d(TAG, "SMB 通道无法主动触发 rescan，等待目标客户端的 watcher 发现变化")
    }

    /** 每次操作独立开连接，用完即断：SMB 长连接容易被服务器踢掉。 */
    private fun <T> withShare(block: (DiskShare) -> T): T {
        val client = SMBClient()
        var connection: Connection? = null
        try {
            connection = client.connect(host, port)
            val auth = AuthenticationContext(user, password.toCharArray(), domain)
            val session = connection.authenticate(auth)
            val share = session.connectShare(shareName) as DiskShare
            return block(share)
        } finally {
            runCatching { connection?.close() }
            runCatching { client.close() }
        }
    }

    private fun suffix(): String {
        val p = path.trim().replace('/', '\\').trim('\\')
        return if (p.isEmpty()) "" else "\\$p"
    }

    /** base 目录（配置里的 path）与子路径拼接。 */
    private fun join(child: String): String = joinIn(path, child)

    private fun join2(base: String, name: String): String = joinIn(base, name)

    /**
     * Windows 文件名里不允许出现 / 和 \，所以两者都可以安全地当作分隔符来切分。
     * 只规范分隔符，绝不动每一段自身的字符。
     */
    private fun segments(value: String): List<String> =
        value.split('\\', '/').filter { it.isNotEmpty() }

    /**
     * 关键修正：以前用 String.trim() 规范路径，会把 "名字 " 这类含首尾空格的
     * 文件名修坏（trim 掉了尾随空格），SMB 侧就变成 STATUS_OBJECT_NAME_NOT_FOUND，
     * 表现正是"有些文件删不掉"。现在按段拼接，原样保留每一段。
     */
    private fun joinIn(base: String, child: String): String =
        (segments(base) + segments(child)).joinToString("\\")

    private fun describe(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            e is UnknownHostException -> "找不到主机 $host，请检查地址或设备是否离线"
            e is ConnectException -> "连接被拒绝：请确认 SMB 服务已开启、端口 $port 未被防火墙挡住"
            e is SocketTimeoutException -> "连接超时：地址不可达，或被防火墙拦住"
            raw.contains("STATUS_LOGON_FAILURE", ignoreCase = true) ->
                "账号或密码不对（服务端返回 STATUS_LOGON_FAILURE）"
            raw.contains("STATUS_ACCESS_DENIED", ignoreCase = true) ->
                "没有访问权限（STATUS_ACCESS_DENIED）：请检查该账号对共享的权限"
            raw.contains("STATUS_BAD_NETWORK_NAME", ignoreCase = true) ->
                "共享名不存在：$shareName（STATUS_BAD_NETWORK_NAME）"
            raw.contains("STATUS_OBJECT_NAME_NOT_FOUND", ignoreCase = true) ->
                "路径不存在：$path"
            else -> raw.ifBlank { "SMB 连接失败：${e.javaClass.simpleName}" }
        }
    }

    companion object {
        private const val TAG = "SmbChannel"
        private const val STIGNORE = ".stignore"

        /** FILE_ATTRIBUTE_DIRECTORY */
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x00000010L

        /**
         * 打开文件时声明的共享访问方式。
         * smbj 0.14 没有 SMB2ShareAccess.ALL，只能自己枚举。
         */
        private val SHARE_ACCESS: Set<SMB2ShareAccess> = EnumSet.of(
            SMB2ShareAccess.FILE_SHARE_READ,
            SMB2ShareAccess.FILE_SHARE_WRITE,
            SMB2ShareAccess.FILE_SHARE_DELETE,
        )
    }
}
