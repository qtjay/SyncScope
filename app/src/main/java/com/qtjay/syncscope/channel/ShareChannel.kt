package com.qtjay.syncscope.channel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.qtjay.syncscope.core.model.BrowseEntry
import com.qtjay.syncscope.core.model.Folder

/**
 * 通过"共享目录"访问目标文件夹：用户自己在目标设备上开好 SMB/FTP 服务，
 * 再用系统的文档树选择器选中该目录，本通道按本地目录的方式读写。
 *
 * <p>能力受限：只能看到本机已经存在的文件，看不到远端有、本机没下载的内容；
 * 也无法主动触发 Syncthing 重新扫描，只能等它自己的 watcher 发现变化。
 */
class ShareChannel(
    private val context: Context,
    private val treeUri: Uri,
) : SyncChannel {

    override val capabilities = Capabilities(
        canSeeRemoteOnlyFiles = false,
        canTriggerRescan = false,
    )

    private fun root(): DocumentFile? = DocumentFile.fromTreeUri(context, treeUri)

    override fun test(): String? {
        return if (root()?.exists() == true) "" else null
    }

    override fun folders(): List<Folder> {
        val dir = root() ?: return emptyList()
        return listOf(Folder(id = "shared", label = dir.name ?: "共享目录"))
    }

    override fun tree(folderId: String, prefix: String): List<BrowseEntry> {
        var dir = root() ?: return emptyList()
        if (prefix.isNotEmpty()) {
            for (segment in prefix.split('/')) {
                dir = dir.findFile(segment) ?: return emptyList()
            }
        }
        val files = dir.listFiles()
        return files.mapNotNull { file ->
            BrowseEntry().apply {
                name = file.name ?: return@mapNotNull null
                type = if (file.isDirectory) {
                    BrowseEntry.TYPE_DIRECTORY
                } else {
                    "FILE_INFO_TYPE_FILE"
                }
            }
        }
    }

    override fun ignores(folderId: String): List<String> {
        val dir = root() ?: return emptyList()
        val file = dir.findFile(".stignore") ?: return emptyList()
        return readText(file).split("\n")
    }

    override fun writeIgnores(folderId: String, lines: List<String>): Boolean {
        val dir = root() ?: return false
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        val existing = dir.findFile(".stignore")
        return try {
            if (existing != null) {
                context.contentResolver.openOutputStream(existing.uri, "wt")?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: return false
            } else {
                val created = dir.createFile("*/*", ".stignore") ?: return false
                context.contentResolver.openOutputStream(created.uri, "wt")?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "写入 .stignore 失败", e)
            false
        }
    }

    private fun readText(file: DocumentFile): String {
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "读取 .stignore 失败", e)
            ""
        }
    }

    override fun delete(folder: Folder, paths: List<String>): DeleteResult {
        val dir = root() ?: return DeleteResult(emptyList(), paths)
        val sorted = paths.sortedBy { it.length }
        val deleted = ArrayList<String>()
        val failed = ArrayList<String>()
        for (path in sorted) {
            var target: DocumentFile? = dir
            for (segment in path.split('/')) {
                target = target?.findFile(segment)
                if (target == null) break
            }
            val ok = try {
                target?.delete() == true
            } catch (e: Exception) {
                Log.w(TAG, "删除失败: $path", e)
                false
            }
            if (ok) deleted.add(path) else failed.add(path)
        }
        return DeleteResult(deleted, failed)
    }

    /**
     * 共享目录通道无法主动触发 Syncthing 重新扫描，
     * 只能等目标客户端自己的 watcher 发现 .stignore 变化后生效。
     */
    override fun rescan(folderId: String) {
        Log.d(TAG, "共享目录通道不支持主动 rescan，等待目标客户端自行扫描")
    }

    companion object {
        private const val TAG = "ShareChannel"
    }
}
