package com.qtjay.syncscope.channel

import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
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

    override fun delete(
        folder: Folder,
        paths: List<String>,
        onProgress: ((Int, Int) -> Unit)?,
    ): DeleteResult {
        if (paths.isEmpty()) {
            return DeleteResult(emptyList(), emptyList())
        }
        val base = folder.path.ifBlank { path }
        return try {
            withShare<DeleteResult> { share -> deleteTrees(share, base, paths, onProgress) }
        } catch (e: Exception) {
            Log.w(TAG, "SMB 删除失败", e)
            val why = e.message ?: e.javaClass.simpleName
            DeleteResult(
                emptyList(),
                paths,
                paths.associateWith { "连接或会话失败：$why" },
            )
        }
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

    /**
     * 摊平整棵子树，再并发删文件、按深度倒序删空目录，最后删根。
     *
     * <p>实测依据：目标设备上一个含 8449 个文件的目录，串行逐个删要 113 秒
     * （实测单文件 13.4ms）。并发后能压到一个数量级以下。
     *
     * <p>不再使用 smbj 的 rmdir(recursive)：它内部就是串行遍历，
     * 而且中途任何一个条目失败都会让整次调用抛异常 —— 已经删掉的部分又不回滚，
     * 结果就是"界面报失败，目录却被删得残缺不全"。
     * 这里改成单条失败只记原因、不影响其余条目，删多少算多少。
     */
    private fun deleteTrees(
        share: DiskShare,
        base: String,
        relativeRoots: List<String>,
        onProgress: ((Int, Int) -> Unit)?,
    ): DeleteResult {
        val roots = relativeRoots.map { joinIn(base, it) }
        val items = LinkedHashMap<String, ArrayList<SmbNode>>()
        for (root in roots) {
            items[root] = ArrayList()
        }

        // 阶段 0：并发摊平。156 个目录串行 LIST 也要好几秒，并发摊平几乎瞬间完成。
        var level = roots
        while (level.isNotEmpty()) {
            val next = Collections.synchronizedList(ArrayList<String>())
            for (chunk in level.chunked(DELETE_PARALLELISM)) {
                runBlocking(Dispatchers.IO) {
                    chunk.map { dir ->
                        async {
                            val listed: List<FileIdBothDirectoryInformation> = try {
                                share.list(dir)
                            } catch (e: Exception) {
                                Log.w(TAG, "列出目录失败: $dir", e)
                                emptyList()
                            }
                            for (info in listed) {
                                val name = info.fileName
                                if (name.isNullOrEmpty() || name == "." || name == "..") {
                                    continue
                                }
                                val child = joinIn(dir, name)
                                val isDir = info.fileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0L
                                val owner = ownerOf(child, roots)
                                if (owner != null) {
                                    synchronized(items) {
                                        items[owner]?.add(SmbNode(child, isDir))
                                    }
                                }
                                if (isDir) {
                                    synchronized(next) { next.add(child) }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            level = next
        }

        val allFiles = ArrayList<SmbNode>()
        val allDirs = ArrayList<SmbNode>()
        for (list in items.values) {
            for (node in list) {
                if (node.dir) allDirs.add(node) else allFiles.add(node)
            }
        }

        // 键值：路径 → 删除结果（null 表示成功，否则是原因）
        val outcomes = Collections.synchronizedMap(LinkedHashMap<String, String?>())
        val total = allFiles.size + allDirs.size + roots.size
        var done = 0

        // 阶段 1：并发删文件 —— 数量级上文件占绝对多数，并发收益最大
        for (chunk in allFiles.chunked(DELETE_PARALLELISM)) {
            runBlocking(Dispatchers.IO) {
                chunk.map { node ->
                    async {
                        node.abs to try {
                            share.rm(node.abs)
                            null
                        } catch (e: Exception) {
                            e.message ?: e.javaClass.simpleName
                        }
                    }
                }.awaitAll()
            }.forEach { (abs, error) -> outcomes[abs] = error }
            done += chunk.size
            onProgress?.invoke(done, total)
        }

        // 阶段 2：删目录。数量少，且并发删可能撞上父子关系，所以串行 + 深的先删
        for (node in allDirs.sortedByDescending { it.abs.length }) {
            outcomes[node.abs] = try {
                share.rmdir(node.abs, false)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            done++
            onProgress?.invoke(done, total)
        }

        // 阶段 3：删根自身。子树没清干净就别硬删，否则留个残缺目录
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        val reasons = LinkedHashMap<String, String>()
        for ((index, root) in roots.withIndex()) {
            val relative = relativeRoots[index]
            val kids = items[root].orEmpty()
            val broken = kids.firstOrNull { outcomes[it.abs] != null }
            if (broken != null) {
                failed.add(relative)
                reasons[relative] = "子树里有没删掉的条目，已跳过该目录：" +
                    stripBase(broken.abs, base) + " → " + outcomes[broken.abs]
                done++
                onProgress?.invoke(done, total)
                continue
            }
            val error = try {
                if (share.folderExists(root)) {
                    share.rmdir(root, false)
                } else {
                    share.rm(root)
                }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            if (error == null) {
                deleted.add(relative)
            } else {
                failed.add(relative)
                reasons[relative] = error
            }
            done++
            onProgress?.invoke(done, total)
        }
        return DeleteResult(deleted, failed, reasons)
    }

    /** 这个条目归属于哪个待删根 —— 取路径匹配得最深的那个。 */
    private fun ownerOf(abs: String, roots: List<String>): String? {
        val parts = segments(abs)
        var best: String? = null
        var bestDepth = 0
        for (root in roots) {
            val prefix = segments(root)
            if (prefix.size < parts.size && prefix.size > bestDepth &&
                parts.subList(0, prefix.size) == prefix
            ) {
                best = root
                bestDepth = prefix.size
            }
        }
        return best
    }

    /** 绝对路径还原成相对路径，用来把服务端原因翻译回用户看得懂的路径。 */
    private fun stripBase(abs: String, base: String): String {
        val prefix = segments(base)
        val parts = segments(abs)
        return if (prefix.isEmpty() || parts.size <= prefix.size) {
            parts.joinToString("/")
        } else {
            parts.subList(prefix.size, parts.size).joinToString("/")
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

        /**
         * Windows 默认就开着的磁盘管理共享，探测共享名时的候选。
         * 只探这几个：它们连不上时服务端会立刻回 STATUS_BAD_NETWORK_NAME，
         * 不必等超时，所以探测很快；而猜普通共享名没有意义，猜不中还得等超时。
         */
        val DISK_SHARES: List<String> = listOf("C\$", "D\$", "E\$", "F\$", "G\$", "H\$")

        /**
         * 探测这台机器上哪些共享名能连上。
         *
         * smbj 没有枚举共享的 API（那要走 srvsvc 的 DCE/RPC），
         * 所以对候选名逐个试着连接，连得上就说明存在且有权限。
         *
         * @return 能连上的共享名，按顺序排列
         */
        fun probeShares(
            host: String,
            port: Int = 445,
            user: String = "",
            password: String = "",
            domain: String = "",
            candidates: List<String> = DISK_SHARES,
        ): List<String> {
            val client = SMBClient()
            var connection: Connection? = null
            return try {
                connection = client.connect(host, port)
                val session = connection.authenticate(
                    AuthenticationContext(user, password.toCharArray(), domain),
                )
                val found = ArrayList<String>()
                for (name in candidates) {
                    val share = try {
                        session.connectShare(name)
                    } catch (e: Exception) {
                        null
                    }
                    if (share != null) {
                        found.add(name)
                        runCatching { share.close() }
                    }
                }
                found
            } catch (e: Exception) {
                Log.w(TAG, "探测共享失败：$host", e)
                emptyList()
            } finally {
                runCatching { connection?.close() }
                runCatching { client.close() }
            }
        }

        /** FILE_ATTRIBUTE_DIRECTORY */
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x00000010L

        /** 摊平/删除时的并发上限：再高不是更快，而是会把 SMB 服务端和手机网络栈压垮。 */
        private const val DELETE_PARALLELISM = 8

        private data class SmbNode(val abs: String, val dir: Boolean)

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
