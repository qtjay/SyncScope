package com.qtjay.syncscope.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 连接配置的本地持久化（SharedPreferences + Gson）。
 *
 * 只存配置本身，不缓存目录树 —— 目录结构以目标客户端的实际情况为准。
 */
object ProfileStore {

    private const val PREFS = "syncscope_profiles"
    private const val KEY = "profiles"

    fun load(context: Context): List<ClientProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ClientProfile>>() {}.type
            Gson().fromJson<List<ClientProfile>>(raw, type) ?: emptyList()
        } catch (e: Exception) {
            // 配置读不出来就当空列表，不能让整个应用起不来
            emptyList()
        }
    }

    fun save(context: Context, profiles: List<ClientProfile>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY, Gson().toJson(profiles)).apply()
    }
}
