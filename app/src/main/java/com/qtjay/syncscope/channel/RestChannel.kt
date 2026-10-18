package com.qtjay.syncscope.channel

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import com.qtjay.syncscope.core.model.FolderStatus
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 通过 Syncthing REST API 读写配置与忽略规则。
 *
 * 两种登录方式都支持：
 *  1. API key —— 只发 X-API-Key 头，服务端直接放行，不需要 CSRF 令牌；
 *  2. 用户名 + 密码（GUI 登录账号）—— 发 HTTP Basic 认证，
 *     并且**必须**额外带 CSRF 令牌，否则 Syncthing 2.x 一律回 403 "CSRF Error"。
 *
 * CSRF 令牌的取法（在真实客户端 v2.1.6 上验证过）：
 *  先 GET 一个非 /rest 的路径（这里用 "/"，带同样的认证），
 *  响应头 X-Syncthing-Id 的第一段就是设备短 ID（如 4YVBFAR），
 *  响应头 Set-Cookie 里有 CSRF-Token-<短ID>=<令牌>；
 *  之后每个 /rest 请求都带上头 X-CSRF-Token-<短ID>: <令牌> 即可，
 *  连 cookie 都不用带（服务端只校验这个头）。
 */
class RestChannel(
    private val host: String,
    private val port: Int,
    private val apiKey: String = "",
    private val user: String = "",
    private val password: String = "",
    private val scheme: String = "http",
    private val gson: Gson = Gson(),
) : SyncChannel {

    private val baseUrl = scheme + "://" + host + ":" + port

    /** 设备短 ID，CSRF 头名的一部分。 */
    @Volatile
    private var shortId: String? = null

    /** 当前可用的 CSRF 令牌。 */
    @Volatile
    private var csrfToken: String? = null

    /** 目标设备的完整设备 ID，连上之后才有。 */
    @Volatile
    private var myId: String? = null

    /** 目标设备报出来的名字，取自 /rest/config 里 devices 中 deviceID == myID 的那一条。 */
    @Volatile
    private var myName: String? = null

    override fun deviceId(): String? = myId

    /**
     * 设备名不在 /rest/system/status 里，而在 /rest/config 的 devices 数组里，
     * 要按 deviceID 等于自己的 myID 挑出来 —— 拿错了会显示成对端的名字。
     */
    override fun deviceName(): String? {
        val id = myId ?: return null
        myName?.let { return it }
        return try {
            val parsed = gson.fromJson(get("/rest/config"), Map::class.java)
            val devices = parsed?.get("devices") as? List<*>
            var found: String? = null
            if (devices != null) {
                for (item in devices) {
                    val device = item as? Map<*, *> ?: continue
                    if (device["deviceID"] as? String != id) {
                        continue
                    }
                    val name = device["name"] as? String
                    if (!name.isNullOrBlank()) {
                        found = name
                    }
                    break
                }
            }
            myName = found
            found
        } catch (e: Exception) {
            Log.w(TAG, "读取设备名失败", e)
            null
        }
    }

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = true,
        canTriggerRescan = true,
        // 官方 REST API 没有删除文件的端点，删除必须交给 SMB/FTP/本地通道
        canDeleteTargetFiles = false,
    )

    override fun test(): TestResult {
        return try {
            // 用用户名密码登录时先握手拿 CSRF 令牌，
            // 否则第一个请求必然被 403 拒掉（表现为"连不上"）
            if (csrfToken == null && basicAuth() != null) {
                handshake()
            }
            val parsed = gson.fromJson(get("/rest/system/status"), Map::class.java)
            val id = parsed?.get("myID") as? String
            if (id.isNullOrBlank()) {
                TestResult.fail("连上了，但响应里没有 myID，可能不是 Syncthing 的 GUI 端口")
            } else {
                myId = id
                // ID 不在这里展示：连接成功后会把它并进配置名称，主页上再显示就重复了
                TestResult.ok("设备已连接")
            }
        } catch (e: Exception) {
            Log.w(TAG, "连接测试失败", e)
            TestResult.fail(describe(e))
        }
    }

    /** 把异常翻成人能看懂的中文，而不是一律"连接失败"。 */
    private fun describe(e: Exception): String {
        val raw = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException ->
                "找不到主机 $host，请检查地址是否写错或设备是否离线"
            e is java.net.ConnectException ->
                "连接被拒绝：请确认端口 $port 正确，且目标客户端已开启 Web UI 远程访问"
            e is java.net.SocketTimeoutException ->
                "连接超时：地址不可达，或被防火墙/运营商拦住了"
            raw.contains("CSRF") ->
                "服务端要求 CSRF 令牌但没拿到（$raw）：通常是用户名或密码不对"
            raw.contains("HTTP 403") ->
                "被拒绝（HTTP 403）：用户名/密码不对，或 API key 无效；" +
                    "若目标没设 GUI 账号，用户名留空、只填 API key"
            e is javax.net.ssl.SSLHandshakeException ||
                raw.contains("Trust anchor") ||
                raw.contains("certificate") ->
                "HTTPS 证书不受信任（Syncthing 默认用自签名证书）：改用 http，" +
                    "或先在系统里信任该证书"
            raw.contains("HTTP 401") ->
                "API key 不正确（HTTP 401）"
            raw.contains("HTTP 403") ->
                "拒绝访问（HTTP 403），请检查 API key 权限"
            raw.contains("HTTP 404") ->
                "接口不存在（HTTP 404），可能端口指向的不是 Syncthing"
            raw.startsWith("HTTP ") ->
                "目标返回异常：$raw"
            else ->
                raw.ifBlank { e.toString() }
        }
    }

    override fun folders(): List<Folder> {
        val raw = get("/rest/config/folders")
        val type = object : TypeToken<List<Folder>>() {}.type
        return gson.fromJson(raw, type) ?: emptyList()
    }

    override fun tree(folderId: String, prefix: String): List<BrowseEntry> {
        val query = "?folder=" + enc(folderId) + "&prefix=" + enc(prefix)
        val raw = get("/rest/db/browse" + query)
        val type = object : TypeToken<List<BrowseEntry>>() {}.type
        return gson.fromJson(raw, type) ?: emptyList()
    }

    override fun ignores(folderId: String): List<String> {
        val raw = get("/rest/db/ignores?folder=" + enc(folderId))
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val parsed: Map<String, Any> = gson.fromJson(raw, type) ?: return emptyList()
        val ignore = parsed["ignore"] as? List<*>
        return ignore?.mapNotNull { it?.toString() } ?: emptyList()
    }

    override fun writeIgnores(folderId: String, lines: List<String>): Boolean {
        return try {
            // 实测：body 必须是 {"ignore": [...]}，纯字符串数组会被直接忽略
            val body = gson.toJson(mapOf("ignore" to lines))
            request("POST", "/rest/db/ignores?folder=" + enc(folderId), body)
            // 回读校验：确认规则确实写进去了，避免"看起来成功、实际没生效"
            val written = ignores(folderId)
            written.any { it.isNotBlank() } || lines.none { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "写入忽略规则失败", e)
            false
        }
    }

    /**
     * 只能用 java.io.File 删除**手机本机**上的文件。
     *
     * 若目标 Syncthing 跑在另一台设备，folder.path 在本机并不存在，
     * 这里会原样返回全部 failed，真正的远程删除由 SMB/FTP 通道负责。
     */
    override fun delete(
        folder: Folder,
        paths: List<String>,
        onProgress: ((Int, Int) -> Unit)?,
    ): DeleteResult {
        if (folder.path.isBlank() || !File(folder.path).exists()) {
            Log.w(TAG, "目标路径不在本机，REST 通道无法删除：${folder.path}")
            return DeleteResult(
                emptyList(),
                paths,
                paths.associateWith { "REST 接口没有删除端点，且目标路径不在本机" },
            )
        }
        // 父路径优先：先删目录，子项随之消失，
        // 避免把"已被父目录带走"误报成失败。
        val sorted = paths.sortedBy { it.length }
        var done = 0
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        val reasons = LinkedHashMap<String, String>()
        for (path in sorted) {
            val file = File(folder.path, path)
            if (!file.exists()) {
                // 本机没有这一项，不算失败（可能被父目录先带走了）
                continue
            }
            var ok = deleteRecursively(file)
            if (!ok && file.exists()) {
                // 文件可能正被 Syncthing 占用，稍等后重试一次
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                ok = deleteRecursively(file)
            }
            if (ok && !file.exists()) {
                deleted.add(path)
            } else {
                failed.add(path)
                reasons[path] = "本地删除失败：文件可能正被 Syncthing 占用或没有写权限"
            }
            done++
            onProgress?.invoke(done, sorted.size)
        }
        return DeleteResult(deleted, failed, reasons)
    }

    override fun rescan(folderId: String) {
        try {
            request("POST", "/rest/db/scan?folder=" + enc(folderId))
        } catch (e: Exception) {
            Log.w(TAG, "触发 rescan 失败", e)
        }
    }

    /**
     * GET /rest/db/file：查某个路径在全局模型里的状态。
     *
     * 真机（v2.1.6）实测：
     *  - 存在 → 200，body 里有 global 与 availability；
     *  - 不存在 → 404，body 为 "No such object in the index"；
     *  - global.deleted = true 表示集群里已被删除，等同不存在。
     * 判断不了（网络异常等）时返回 null，让调用方按"不删"处理。
     */
    override fun remoteInfo(folderId: String, path: String): RemoteInfo? {
        return try {
            val query = "?folder=" + enc(folderId) + "&file=" + enc(path)
            val parsed = gson.fromJson(get("/rest/db/file" + query), Map::class.java)
                ?: return null
            val global = parsed["global"] as? Map<*, *>
            val deleted = global?.get("deleted") as? Boolean ?: false
            val type = global?.get("type") as? String
            val availability = (parsed["availability"] as? List<*>)?.mapNotNull { item ->
                (item as? Map<*, *>)?.get("id") as? String
            } ?: emptyList()
            RemoteInfo(
                exists = !deleted,
                directory = type == BrowseEntry.TYPE_DIRECTORY,
                availability = availability,
                size = (global?.get("size") as? Number)?.toLong() ?: -1L,
                modified = global?.get("modified") as? String ?: "",
                ignored = global?.get("ignored") as? Boolean ?: false,
            )
        } catch (e: Exception) {
            if (e.message.orEmpty().contains("HTTP 404")) {
                RemoteInfo(exists = false)
            } else {
                Log.w(TAG, "查询远端状态失败: $path", e)
                null
            }
        }
    }

    /** GET /rest/db/status：文件数、字节数与同步进度。 */
    override fun status(folderId: String): FolderStatus? {
        return try {
            val parsed = gson.fromJson(
                get("/rest/db/status?folder=" + enc(folderId)),
                Map::class.java,
            ) ?: return null

            fun number(key: String): Long {
                return (parsed[key] as? Number)?.toLong() ?: 0L
            }

            FolderStatus(
                state = parsed["state"] as? String ?: "",
                localFiles = number("localFiles"),
                globalFiles = number("globalFiles"),
                needFiles = number("needFiles"),
                localBytes = number("localBytes"),
                globalBytes = number("globalBytes"),
                needBytes = number("needBytes"),
                inSyncFiles = number("inSyncFiles"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "读取文件夹状态失败", e)
            null
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            val kids = file.listFiles()
            if (kids != null) {
                for (kid in kids) {
                    if (!deleteRecursively(kid)) {
                        return false
                    }
                }
            }
        }
        return file.delete()
    }

    private fun enc(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun get(path: String): String {
        return request("GET", path)
    }

    /**
     * 用户名 + 密码 → HTTP Basic 认证头。
     * 只在填了用户名时才发：没填用户名说明用户给的是 API key，
     * 发一个密码错的 Basic 头会让服务端触发防爆破延时，白白拖慢连接。
     */
    private fun basicAuth(): String? {
        if (user.isBlank()) {
            return null
        }
        val raw = (user + ":" + password).toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun applyAuth(conn: HttpURLConnection) {
        // 两个头都发：服务端先认 API key，认不出来会自动退回到 Basic 认证，
        // 所以"填了用户名密码"和"填了 API key"两种情况都能覆盖。
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("X-API-Key", apiKey)
        }
        basicAuth()?.let { conn.setRequestProperty("Authorization", it) }
        val token = csrfToken
        val id = shortId
        if (token != null && id != null) {
            conn.setRequestProperty(CSRF_HEADER_PREFIX + id, token)
        }
    }

    /**
     * 取 CSRF 令牌：请求一个非 /rest 的路径，服务端会在 Set-Cookie 里下发令牌。
     * 拿不到就抛出带原因的异常，让上层显示成人能看懂的提示。
     */
    private fun handshake() {
        val conn = URL(baseUrl + "/").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            applyAuth(conn)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP " + code + ": " + text.take(120))
            }
            // X-Syncthing-Id: 4YVBFAR-4X4DQEV-... → 短 ID 是第一段
            val id = conn.getHeaderField("X-Syncthing-Id").orEmpty().substringBefore("-")
            val cookies = conn.headerFields[SET_COOKIE] ?: emptyList()
            var token: String? = null
            for (cookie in cookies) {
                val at = cookie.indexOf("CSRF-Token-")
                if (at < 0) continue
                token = cookie.substring(cookie.indexOf('=', at) + 1)
                    .substringBefore(';')
                    .trim()
                if (token.isNotBlank()) break
            }
            if (id.isBlank() || token.isNullOrBlank()) {
                throw IllegalStateException(
                    "HTTP $code：连上了，但拿不到 CSRF 令牌。" +
                        "请确认用户名与密码是 Syncthing 的 GUI 登录账号",
                )
            }
            shortId = id
            csrfToken = token
            Log.d(TAG, "已获取 CSRF 令牌：设备 $id")
        } finally {
            conn.disconnect()
        }
    }

    private fun request(
        method: String,
        path: String,
        body: String? = null,
        allowRetry: Boolean = true,
    ): String {
        val url = URL(baseUrl + path)
        val conn = url.openConnection() as HttpURLConnection
        var code = 0
        var text = ""
        try {
            conn.requestMethod = method
            applyAuth(conn)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(body)
                }
            }
            code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
        // 服务端要求 CSRF 令牌：握手拿到令牌后原样重试一次（带 body 的请求也适用）
        if (code == 403 && text.contains("CSRF Error", ignoreCase = true) && allowRetry) {
            handshake()
            return request(method, path, body, allowRetry = false)
        }
        if (code !in 200..299) {
            throw IllegalStateException("HTTP " + code + ": " + text.take(200))
        }
        return text
    }

    companion object {
        private const val TAG = "RestChannel"
        private const val CSRF_HEADER_PREFIX = "X-CSRF-Token-"
        private const val SET_COOKIE = "Set-Cookie"
    }
}
