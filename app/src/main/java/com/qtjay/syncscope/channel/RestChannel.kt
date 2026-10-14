package com.qtjay.syncscope.channel

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 通过 Syncthing REST API 读写配置与忽略规则。
 *
 * <p>前提：目标客户端开启了 Web UI 远程访问，并提供 API key。
 * 这是功能最完整的通道 —— 既能看到远端未下载的文件，也能主动触发重新扫描。
 */
class RestChannel(
    private val host: String,
    private val port: Int,
    private val apiKey: String,
    private val scheme: String = "http",
    private val gson: Gson = Gson(),
) : SyncChannel {

    private val baseUrl = scheme + "://" + host + ":" + port

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = true,
        canTriggerRescan = true,
        // 官方 REST API 没有删除文件的端点，删除必须交给 SMB/FTP/本地通道
        canDeleteTargetFiles = false,
    )

    override fun test(): TestResult {
        return try {
            val parsed = gson.fromJson(get("/rest/system/status"), Map::class.java)
            val id = parsed?.get("myID") as? String
            if (id.isNullOrBlank()) {
                TestResult.fail("连上了，但响应里没有 myID，可能不是 Syncthing 的 GUI 端口")
            } else {
                TestResult.ok("设备已连接，ID ${id.take(7)}…")
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
    override fun delete(folder: Folder, paths: List<String>): DeleteResult {
        if (folder.path.isBlank() || !File(folder.path).exists()) {
            Log.w(TAG, "目标路径不在本机，REST 通道无法删除：${folder.path}")
            return DeleteResult(emptyList(), paths)
        }
        // 父路径优先：先删目录，子项随之消失，
        // 避免把"已被父目录带走"误报成失败。
        val sorted = paths.sortedBy { it.length }
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        for (path in sorted) {
            val file = File(folder.path, path)
            if (!file.exists()) {
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
            }
        }
        return DeleteResult(deleted, failed)
    }

    override fun rescan(folderId: String) {
        try {
            request("POST", "/rest/db/scan?folder=" + enc(folderId))
        } catch (e: Exception) {
            Log.w(TAG, "触发 rescan 失败", e)
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

    private fun request(method: String, path: String, body: String? = null): String {
        val url = URL(baseUrl + path)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.setRequestProperty("X-API-Key", apiKey)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(body)
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP " + code + ": " + text.take(200))
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "RestChannel"
    }
}
