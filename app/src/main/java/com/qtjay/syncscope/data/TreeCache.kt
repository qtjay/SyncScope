package com.qtjay.syncscope.data

import android.content.Context
import com.google.gson.Gson

/** 目录树里缓存下来的一行。 */
data class CachedRow(
    val name: String = "",
    val path: String = "",
    val dir: Boolean = false,
    val depth: Int = 0,
    val expanded: Boolean = false,
)

/**
 * 一个文件夹的目录树快照。
 *
 * 存三样东西：
 *  - 可见行结构（现在展开到了哪一层）；
 *  - 勾选状态，分三桶：整棵子树都同步的目录、只放行了部分子项的目录、同步的文件；
 *  - 更新时间戳，用来在界面上提示"这份目录树是多久之前取的"。
 *
 * 缓存只用于"进入时先把界面填出来"，真正的勾选仍以从目标端读回的 .stignore 为准。
 */
data class TreeSnapshot(
    val updatedAt: Long = 0L,
    val rows: List<CachedRow> = emptyList(),
    val fullDirs: List<String> = emptyList(),
    val partialDirs: List<String> = emptyList(),
    val files: List<String> = emptyList(),
)

/** 目录树缓存的本地持久化（SharedPreferences + Gson）。 */
object TreeCache {

    private const val PREFS = "syncscope_tree"
    private val gson = Gson()

    fun load(context: Context, folderId: String): TreeSnapshot? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(folderId, null) ?: return null
        return try {
            gson.fromJson(raw, TreeSnapshot::class.java)
        } catch (e: Exception) {
            // 缓存坏了就当没有，不能让文件夹打不开
            null
        }
    }

    fun save(context: Context, folderId: String, snapshot: TreeSnapshot) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(folderId, gson.toJson(snapshot)).apply()
    }
}
