package com.qtjay.syncscope.channel

import android.util.Log
import android.util.Xml
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 文件通道：读写目标设备上的 `.stignore`，并删除取消勾选的文件。
 *
 * <p>和 FTP/SMB 一样属于"动作面"通道 —— 能看到磁盘上真实存在的东西，也能删，
 * 但看不到"远端有、本机没下载"的内容，也不能主动触发 Syncthing 重扫。
 *
 * <p>实现上的两个关键点：
 * <ol>
 *   <li><b>必须用 OkHttp</b>：Java 自带的 HttpURLConnection 只放行标准方法，
 *       调用 PROPFIND 会直接抛 ProtocolException，列目录根本做不了；</li>
 *   <li>XML 用 Android 自带的 XmlPullParser 解析，不引第三方解析库。
 *       把命名空间处理关掉，只认元素本地名 —— 因为各服务端返回的命名空间前缀
 *       （D:、DAV:、lp1:……）并不统一。</li>
 * </ol>
 */
class WebdavChannel(
    private val host: String,
    private val port: Int = 80,
    private val path: String = "",
    private val user: String = "",
    private val password: String = "",
    private val useHttps: Boolean = false,
) : SyncChannel {

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = false,
        canTriggerRescan = false,
        canDeleteTargetFiles = true,
    )

    override fun test(): TestResult {
        return try {
            val entries = propfind("")
            TestResult.ok("WebDAV 已连接：${urlOf("")}（${entries.size} 项）")
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 连接测试失败", e)
            TestResult.fail(describe(e))
        }
    }

    override fun folders(): List<Folder> {
        return listOf(
            Folder(id = "webdav", label = "$host${normPath(path)}", path = path),
        )
    }

    override fun tree(folderId: String, prefix: String): List<BrowseEntry> {
        // try 作为表达式：catch 分支直接给值，不要再写 return
        return try {
            propfind(prefix)
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 列目录失败: $prefix", e)
            emptyList()
        }
    }

    override fun remoteInfo(folderId: String, path: String): RemoteInfo? {
        return try {
            // Depth 0 + 只问自己：拿得到就是存在，404 就是不存在
            val self = propfindOne(path)
            if (self == null) {
                RemoteInfo(exists = false)
            } else {
                RemoteInfo(exists = true, directory = self.second)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 查询远端状态失败: $path", e)
            null
        }
    }

    override fun remoteInfoBatch(folderId: String, paths: List<String>): Map<String, RemoteInfo?> {
        if (paths.isEmpty()) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, RemoteInfo?>()
        for (item in paths) {
            out[item] = null
        }
        try {
            for (item in paths) {
                out[item] = try {
                    val response = request("PROPFIND", urlOf(item), depth = 0)
                    val body = response.second
                    if (response.first == 404) {
                        RemoteInfo(exists = false)
                    } else {
                        val parsed = parseMultistatus(body)
                        val self = parsed.firstOrNull { it.href == item }
                        if (self == null) {
                            RemoteInfo(exists = false)
                        } else {
                            RemoteInfo(exists = true, directory = self.dir)
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 批量核对失败", e)
        }
        return out
    }

    override fun ignores(folderId: String): List<String> {
        return try {
            readFile(join(path, STIGNORE))?.split("\n") ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 读取 .stignore 失败", e)
            emptyList()
        }
    }

    override fun writeIgnores(folderId: String, lines: List<String>): Boolean {
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        return try {
            writeFile(join(path, STIGNORE), text)
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 写入 .stignore 失败", e)
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
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        val reasons = LinkedHashMap<String, String>()
        try {
            // 先摊平：WebDAV 的 DELETE 对非空目录由服务端自己递归，
            // 但各服务端行为不一，摊平后逐个删最稳，也便于逐条报进度。
            val flat = LinkedHashMap<String, ArrayList<String>>()
            for (relative in paths) {
                flat[relative] = ArrayList()
                collect(join(base, relative), flat[relative]!!)
            }
            val totalCount = flat.values.sumOf { it.size + 1 }
            var done = 0
            for ((relative, children) in flat) {
                var broken: String? = null
                // 深的先删，再删父目录
                for (child in children.sortedByDescending { it.length }) {
                    val error = try {
                        request("DELETE", urlOf(child))
                        null
                    } catch (e: Exception) {
                        e.message ?: e.javaClass.simpleName
                    }
                    done++
                    if (done % 50 == 0 || done == totalCount) {
                        onProgress?.invoke(done, totalCount)
                    }
                    if (error != null) {
                        broken = "$child → $error"
                        break
                    }
                }
                if (broken != null) {
                    failed.add(relative)
                    reasons[relative] = "子树里有没删掉的条目，已跳过该目录：$broken"
                    done++
                    onProgress?.invoke(done, totalCount)
                    continue
                }
                val error = try {
                    request("DELETE", urlOf(join(base, relative)))
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
                done++
                if (done % 50 == 0 || done == totalCount) {
                    onProgress?.invoke(done, totalCount)
                }
                if (error == null) {
                    deleted.add(relative)
                } else {
                    failed.add(relative)
                    reasons[relative] = error
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 删除失败", e)
        }
        return DeleteResult(deleted, failed, reasons)
    }

    override fun diagnoseBatch(folder: Folder, paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        try {
            for (item in paths) {
                val full = urlOf(join(folder.path.ifBlank { path }, item))
                out[item] = try {
                    val pair = request("PROPFIND", full, depth = 0)
                    if (pair.first == 404) {
                        "$full\n看不到这一项，删除会失败：检查基础路径是否就是同步文件夹"
                    } else {
                        val self = parseMultistatus(pair.second).firstOrNull { it.href == item }
                        if (self == null) {
                            "$full\n看不到这一项，删除会失败：检查基础路径是否就是同步文件夹"
                        } else if (self.dir) {
                            "$full\n（目录，能看到）"
                        } else {
                            "$full\n（文件，能看到）"
                        }
                    }
                } catch (e: Exception) {
                    "$full\n查询失败：${e.message ?: e.javaClass.simpleName}"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 自检失败", e)
        }
        return out
    }

    override fun rescan(folderId: String) {
        Log.d(TAG, "WebDAV 通道无法主动触发 rescan，等待目标客户端的 watcher 发现变化")
    }

    // ---------- 内部实现 ----------

    /** 列一层目录。 */
    private fun propfind(prefix: String): List<BrowseEntry> {
        try {
            val target = join(path, prefix)
            val pair = request("PROPFIND", urlOf(target), depth = 1)
            if (pair.first == 404) {
                return emptyList()
            }
            return parseMultistatus(pair.second)
                .mapNotNull { (href, isDir, size, name) ->
                    if (href == target || href == target + "/") {
                        null
                    } else {
                        BrowseEntry().apply {
                            this.name = name
                            type = if (isDir) {
                                BrowseEntry.TYPE_DIRECTORY
                            } else {
                                "FILE_INFO_TYPE_FILE"
                            }
                            this.size = size
                        }
                    }
                }
                .sortedWith(compareBy({ !it.isDirectory() }, { it.name.orEmpty() }))
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 列目录失败: $prefix", e)
            return emptyList()
        }
    }

    /** 只问自己这一项。 */
    private fun propfindOne(relative: String): Pair<String, Boolean>? {
        try {
            val target = join(path, relative)
            val pair = request("PROPFIND", urlOf(target), depth = 0)
            if (pair.first == 404) {
                return null
            }
            return parseMultistatus(pair.second)
                .firstOrNull { it.href == target || it.href == target + "/" }
                ?.let { it.href to it.dir }
        } catch (e: Exception) {
            // catch 里访问不到 try 块内的 target，只能用函数参数
            Log.w(TAG, "WebDAV 查询失败: $relative", e)
            return null
        }
    }

    /** 递归收集某个路径下的所有后代（不含自身）。列不出来就当作没有子项。 */
    private fun collect(target: String, out: ArrayList<String>) {
        val children = try {
            val pair = request("PROPFIND", urlOf(target), depth = 1)
            if (pair.first == 404) {
                emptyList()
            } else {
                parseMultistatus(pair.second)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 摊平失败: $target", e)
            emptyList()
        }
        for ((href, isDir, _, _) in children) {
            if (href == target || href == target + "/") {
                continue
            }
            out.add(href)
            if (isDir) {
                collect(href, out)
            }
        }
    }

    private fun readFile(target: String): String? {
        try {
            val builder = Request.Builder().url(urlOf(target)).get()
            val response = http.newCall(builder.build()).execute()
            response.use {
                if (it.code == 404) {
                    return null
                }
                if (it.code !in 200..299) {
                    throw IllegalStateException("HTTP ${it.code}")
                }
                return it.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 读文件失败: $target", e)
            return null
        }
    }

    private fun writeFile(target: String, text: String): Boolean {
        try {
            val body = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
            val builder = Request.Builder().url(urlOf(target)).put(body)
            val response = http.newCall(builder.build()).execute()
            response.use {
                return it.code in 200..299 || it.code == 201 || it.code == 204
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV 写文件失败: $target", e)
            return false
        }
    }

    /** 发请求，返回 (状态码, 响应体)。非 2xx/3xx 时抛异常，除了 404 交给调用方判断。 */
    private fun request(
        method: String,
        url: String,
        depth: Int? = null,
        body: okhttp3.RequestBody? = null,
    ): Pair<Int, String> {
        val builder = Request.Builder().url(url)
        if (user.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(user, password))
        }
        if (depth != null) {
            builder.header("Depth", depth.toString())
        }
        if (method == "PROPFIND") {
            builder.header("Content-Type", "application/xml")
        }
        builder.method(method, body)
        val response = http.newCall(builder.build()).execute()
        return response.use {
            val text = if (it.code in 200..299) it.body?.string().orEmpty() else ""
            val code = it.code
            if (code !in 200..299 && code != 404) {
                throw IllegalStateException("HTTP $code")
            }
            code to text
        }
    }

    /**
     * 解析 207 Multi-Status。
     *
     * 命名空间前缀各服务端不一致（D: / DAV: / lp1: / 无前缀），
     * 所以关掉命名空间处理，只按元素本地名匹配。
     */
    private fun parseMultistatus(xml: String): List<Entry> {
        if (xml.isBlank()) {
            return emptyList()
        }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        val out = ArrayList<Entry>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var inResourceType = false
        var depthTag = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "response" -> {
                            href = null
                            isDir = false
                            size = 0L
                            depthTag = 1
                        }
                        "href" -> if (depthTag == 1) {
                            href = decode(parser.nextText())
                        }
                        "resourcetype" -> inResourceType = true
                        "collection" -> if (inResourceType) {
                            isDir = true
                        }
                        "getcontentlength" -> if (depthTag == 1) {
                            size = parser.nextText().trim().toLongOrNull() ?: 0L
                        }
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "resourcetype" -> inResourceType = false
                    "response" -> {
                        val raw = href
                        if (!raw.isNullOrBlank()) {
                            val clean = cleanHref(raw)
                            // 目录的 href 常带尾斜杠，去掉后取最后一段当名字
                            val name = clean.trimEnd('/').substringAfterLast('/')
                            if (name.isNotEmpty()) {
                                out.add(Entry(clean, isDir, size, name))
                            }
                        }
                        depthTag = 0
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** href 可能是完整 URL 或百分号编码过的路径，统一还原成相对路径。 */
    private fun cleanHref(raw: String): String {
        var value = decode(raw)
        // 有些服务端返回完整 URL，先剥掉 scheme://host:port
        val schemeAt = value.indexOf("://")
        if (schemeAt >= 0) {
            val rest = value.substring(schemeAt + 3)
            val slash = rest.indexOf('/')
            value = if (slash >= 0) rest.substring(slash) else ""
        }
        // 再剥掉配置里的基础路径，剩下的才是相对路径
        val base = normPath(path)
        if (base.isNotEmpty() && value.startsWith(base)) {
            value = value.substring(base.length)
        }
        return value.trim('/')
    }

    private fun decode(value: String): String {
        return try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
    }

    private fun normPath(value: String): String {
        val trimmed = value.trim().trim('/')
        return if (trimmed.isEmpty()) "" else "/$trimmed"
    }

    private fun join(base: String, child: String): String {
        val b = base.trim('/')
        val c = child.trim('/')
        return when {
            c.isEmpty() -> b
            b.isEmpty() -> c
            else -> "$b/$c"
        }
    }

    /** 拼出完整 URL；路径逐段编码，中文和空格才不会被服务端认错。 */
    private fun urlOf(relative: String): String {
        val scheme = if (useHttps) "https" else "http"
        val encoded = relative.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$scheme://$host:$port${normPath(path)}" +
            if (encoded.isEmpty()) "" else "/$encoded"
    }

    /** OkHttp 设计上就该共享一个实例；每次请求都 new 一个会漏掉它的线程池。 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private fun describe(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            e is UnknownHostException -> "找不到主机 $host，请检查地址或设备是否离线"
            e is ConnectException -> "连接被拒绝：请确认 WebDAV 服务已开启、端口 $port 正确"
            e is SocketTimeoutException -> "连接超时：地址不可达，或被防火墙拦住"
            raw.contains("401") -> "用户名或密码不对（HTTP 401）"
            raw.contains("403") -> "没有访问权限（HTTP 403）"
            raw.contains("404") -> "路径不存在：${normPath(path)}（HTTP 404）"
            else -> raw.ifBlank { "WebDAV 连接失败：${e.javaClass.simpleName}" }
        }
    }

    /** 一条目录项：相对路径、是否目录、字节数、显示名。 */
    private data class Entry(
        val href: String,
        val dir: Boolean,
        val size: Long,
        val name: String,
    )

    companion object {
        private const val TAG = "WebdavChannel"
        private const val STIGNORE = ".stignore"
    }
}
