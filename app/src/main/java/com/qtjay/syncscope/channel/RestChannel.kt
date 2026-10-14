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
    private val baseUrl: String,
    private val apiKey: String,
    private val gson: Gson = Gson(),
) : SyncChannel {

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = true,
        canTriggerRescan = true,
    )

    override fun test(): String? {
        return try {
            val raw = get("/rest/system/status")
            val parsed = gson.fromJson(raw, Map::class.java)
            parsed?.get("myID") as? String
        } catch (e: Exception) {
            Log.w(TAG, "连接测试失败", e)
            null
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
        val body = gson.toJson(mapOf("ignore" to lines))
        return try {
            request("POST", "/rest/db/ignores?folder=" + enc(folderId), body = body)
            // 回读校验：确认规则确实写进去了，避免"看起来成功、实际没生效"
            val written = ignores(folderId)
            written.any { it.isNotBlank() } || lines.none { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "写入忽略规则失败", e)
            false
        }
    }

    override fun delete(folder: Folder, paths: List<String>): DeleteResult {
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
                // 可能正被 Syncthing 占用，稍等后重试一次
                try {
                    Thread.sleep(300L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                ok = deleteRecursively(file)
            }
            if (!ok && file.exists()) {
                failed.add(path)
            } else {
                deleted.add(path)
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

    private fun enc(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    private fun get(path: String): String {
        return request("GET", path)
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val url = URL(baseUrl.trimEnd('/') + path)
        val conn = (url.openConnection() as? HttpURLConnection)
            ?: throw IllegalStateException("无法建立连接: $url")
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
                throw IllegalStateException("HTTP $code: ${text.take(200)}")
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
