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
                    if (name == "." || name == "..") {
                        null
                    } else {
                        BrowseEntry().apply {
                            this.name = name
                            type = if (share.folderExists(join2(dir, name))) {
                                BrowseEntry.TYPE_DIRECTORY
                            } else {
                                "FILE_INFO_TYPE_FILE"
                            }
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

    /** 把真实要删的 UNC 路径打出来，顺带说明现在看不看得见它。 */
    override fun diagnose(folder: Folder, path: String): String {
        val base = folder.path.ifBlank { this.path }
        val target = joinIn(base, path)
        val unc = "\\\\$host\\$shareName" + if (target.isEmpty()) "" else "\\$target"
        if (target.isEmpty()) {
            return "$unc\n（通道根目录，即配置的共享名+子路径）"
        }
        return try {
            withShare<String> { share ->
                when {
                    share.folderExists(target) -> "$unc\n（目录，能看到）"
                    share.fileExists(target) -> "$unc\n（文件，能看到）"
                    else -> "$unc\n看不到这一项，删除会失败：检查共享名/子路径是否就是同步文件夹根目录"
                }
            }
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            "$unc\n查询失败：$why"
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
        val p = normalize(path)
        return if (p.isEmpty()) "" else "\\$p"
    }

    /** base 目录（配置里的 path）与子路径拼接。 */
    private fun join(child: String): String = joinIn(path, child)

    private fun joinIn(base: String, child: String): String {
        val b = normalize(base)
        val c = normalize(child)
        return when {
            c.isEmpty() -> b
            b.isEmpty() -> c
            else -> "$b\\$c"
        }
    }

    private fun join2(base: String, name: String): String {
        return joinIn(base, name)
    }

    /** SMB 路径用反斜杠分隔，统一把 / 转换掉并去掉首尾多余分隔符。 */
    private fun normalize(value: String): String {
        return value.trim().replace('/', '\\').trim('\\')
    }

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
