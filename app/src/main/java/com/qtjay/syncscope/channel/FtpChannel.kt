package com.qtjay.syncscope.channel

import android.util.Log
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * FTP 文件通道：直接读写目标设备上的 `.stignore`，并删除取消勾选的文件。
 *
 * 这是"动作面"通道 —— Syncthing 的 REST API 没有删除文件的接口，
 * 所以真正把文件从磁盘上抹掉必须靠它（或 SMB、本地目录）。
 * 代价是只能看到实际存在的文件，看不到远端有、本地没下载的内容。
 */
class FtpChannel(
    private val host: String,
    private val port: Int = 21,
    private val user: String,
    private val password: String,
    private val remotePath: String = "",
) : SyncChannel {

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = false,
        canTriggerRescan = false,
        canDeleteTargetFiles = true,
    )

    override fun test(): TestResult {
        return try {
            withClient<TestResult> { c ->
                val pwd = runCatching { c.printWorkingDirectory() }.getOrNull()
                val where = pwd?.takeIf { it.isNotBlank() } ?: remotePath.ifBlank { "/" }
                TestResult.ok("FTP 已连接：$where")
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTP 连接测试失败", e)
            TestResult.fail(describe(e))
        }
    }

    override fun folders(): List<Folder> {
        val path = remotePath.ifBlank { "/" }
        return listOf(Folder(id = "ftp", label = "$host$path", path = remotePath))
    }

    override fun tree(folderId: String, prefix: String): List<BrowseEntry> {
        return try {
            withClient<List<BrowseEntry>> { c ->
                val target = join(remotePath, prefix).ifBlank { "." }
                val listed: Array<FTPFile> = c.listFiles(target) ?: emptyArray()
                listed.mapNotNull { file ->
                    val name = file.name ?: return@mapNotNull null
                    if (name.isBlank() || name == "." || name == "..") {
                        null
                    } else {
                        BrowseEntry().apply {
                            this.name = name
                            type = if (file.isDirectory) {
                                BrowseEntry.TYPE_DIRECTORY
                            } else {
                                "FILE_INFO_TYPE_FILE"
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTP 列目录失败: $prefix", e)
            emptyList()
        }
    }

    override fun ignores(folderId: String): List<String> {
        return try {
            withClient<List<String>> { c ->
                val stream = c.retrieveFileStream(join(remotePath, STIGNORE))
                if (stream == null) {
                    // 目标上还没有 .stignore，等同于空规则
                    emptyList()
                } else {
                    val text: String = try {
                        stream.bufferedReader(Charsets.UTF_8).readText()
                    } finally {
                        runCatching { stream.close() }
                        c.completePendingCommand()
                    }
                    text.split("\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTP 读取 .stignore 失败", e)
            emptyList()
        }
    }

    override fun writeIgnores(folderId: String, lines: List<String>): Boolean {
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        return try {
            withClient<Boolean> { c ->
                val stream = c.storeFileStream(join(remotePath, STIGNORE))
                    ?: return@withClient false
                try {
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                } finally {
                    runCatching { stream.close() }
                    c.completePendingCommand()
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTP 写入 .stignore 失败", e)
            false
        }
    }

    override fun delete(folder: Folder, paths: List<String>): DeleteResult {
        val base = folder.path.ifBlank { remotePath }
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        try {
            withClient { c ->
                // 短路径优先：先删父目录，子项随之消失，
                // 避免把"已被父目录带走"误报成失败。
                for (path in paths.sortedBy { it.length }) {
                    if (deletePath(c, join(base, path))) {
                        deleted.add(path)
                    } else {
                        failed.add(path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTP 删除失败", e)
            failed.addAll(paths)
        }
        return DeleteResult(deleted, failed)
    }

    private fun deletePath(c: FTPClient, remote: String): Boolean {
        // 先当文件删；失败则说明是目录，递归删完再删空目录。
        if (c.deleteFile(remote)) {
            return true
        }
        val kids: Array<FTPFile> = c.listFiles(remote) ?: return false
        for (kid in kids) {
            val name = kid.name ?: continue
            if (name.isBlank() || name == "." || name == "..") {
                continue
            }
            if (!deletePath(c, "$remote/$name")) {
                return false
            }
        }
        return c.removeDirectory(remote)
    }

    override fun rescan(folderId: String) {
        Log.d(TAG, "FTP 通道无法主动触发 rescan，等待目标客户端的 watcher 发现变化")
    }

    /** 每次操作独立开连接，用完即断：FTP 长连接很容易被服务器踢掉。 */
    private fun <T> withClient(block: (FTPClient) -> T): T {
        val c = FTPClient()
        try {
            c.controlEncoding = "UTF-8"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.connect(host, port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) {
                throw IOException("FTP 服务器拒绝连接，响应码 " + c.replyCode)
            }
            if (!c.login(user, password)) {
                throw IOException("FTP 登录失败：用户名或密码不正确")
            }
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            // 尽量让服务端用 UTF-8 传文件名；不支持的服务器会报错，忽略即可
            runCatching { c.sendCommand("OPTS", "UTF8") }
            if (remotePath.isNotBlank() && !c.changeWorkingDirectory(remotePath)) {
                throw IOException("无法进入目录 $remotePath")
            }
            return block(c)
        } finally {
            runCatching { if (c.isConnected) c.logout() }
            runCatching { c.disconnect() }
        }
    }

    private fun join(base: String, child: String): String {
        return when {
            child.isBlank() -> base
            base.isBlank() -> child
            else -> base.trimEnd('/') + "/" + child
        }
    }

    private fun describe(e: Exception): String {
        return when {
            e is UnknownHostException -> "找不到主机 $host，请检查地址或设备是否离线"
            e is ConnectException -> "连接被拒绝：请确认 FTP 服务已开启、端口 $port 正确"
            e is SocketTimeoutException -> "连接超时：地址不可达，或被防火墙拦住"
            e.message?.contains("登录失败") == true -> e.message ?: "登录失败"
            e.message?.contains("无法进入目录") == true -> e.message ?: "目录不存在"
            else -> e.message?.ifBlank { null } ?: "FTP 连接失败：${e.javaClass.simpleName}"
        }
    }

    companion object {
        private const val TAG = "FtpChannel"
        private const val STIGNORE = ".stignore"
        private const val CONNECT_TIMEOUT_MS = 15000
    }
}
