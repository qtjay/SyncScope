package com.qtjay.syncscope.data

import android.content.Context
import android.net.Uri
import com.qtjay.syncscope.channel.FtpChannel
import com.qtjay.syncscope.channel.RestChannel
import com.qtjay.syncscope.channel.ShareChannel
import com.qtjay.syncscope.channel.SmbChannel
import com.qtjay.syncscope.channel.SyncChannel
import java.util.UUID

/** 连接类型。 */
enum class ProfileKind { REST, FTP, SMB, LOCAL }

/**
 * 按端口判断连接类型 —— 省掉表单里那排类型按钮。
 * 认不出来的端口沿用原来的类型（编辑旧配置时不会误改）。
 */
fun kindFromPort(port: String, fallback: ProfileKind = ProfileKind.REST): ProfileKind =
    when (port.trim().toIntOrNull()) {
        21, 2121 -> ProfileKind.FTP
        445, 139 -> ProfileKind.SMB
        8384 -> ProfileKind.REST
        else -> fallback
    }

/**
 * 通道角色。
 *  - INFO：信息面（REST），负责看目录、读写规则、触发重扫
 *  - ACTION：动作面（FTP/SMB/本地），负责真正删除文件
 */
enum class ProfileRole { INFO, ACTION }

/**
 * 一个已保存的连接配置。
 *
 * 字段按四种类型取并集：用不到的留空即可，
 * 这样一份配置能用 Gson 直接序列化，不必为每种类型写子类。
 *
 * 注意：secret 里存的是 REST 的 API key 或 FTP/SMB 的密码，
 * 以明文存在本应用私有的 SharedPreferences 里（其它应用读不到，但 root 后可读）。
 */
data class ClientProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: ProfileKind = ProfileKind.REST,
    val role: ProfileRole = ProfileRole.INFO,
    val host: String = "",
    val port: String = "",
    val user: String = "",
    val secret: String = "",
    val shareName: String = "",
    val domain: String = "",
    val path: String = "",
    val useHttps: Boolean = false,
    val treeUri: String = "",
    /** ACTION 配置归属于哪个 INFO 配置；为空表示独立使用。 */
    val linkedTo: String = "",
    /**
     * ACTION 配置服务于哪个共享文件夹（文件夹 id）。
     * 文件通道是按"客户端 + 文件夹"配的 —— 不同文件夹可能落在不同的机器上。
     */
    val folderId: String = "",
    /**
     * 名称是否已由本应用按"设备名 · 短ID"自动填过。
     * 填过之后不再自动改写 —— 否则用户自己起的名字每次重连都会被覆盖掉。
     */
    val autoNamed: Boolean = false,
) {
    /** 按配置现场构造通道实例。 */
    fun build(context: Context): SyncChannel = when (kind) {
        ProfileKind.REST -> RestChannel(
            scheme = if (useHttps) "https" else "http",
            host = host,
            port = port.toIntOrNull() ?: 8384,
            // 填了用户名 → secret 是 GUI 密码，走 Basic 认证 + CSRF；
            // 没填用户名 → secret 是 API key，只发 X-API-Key。
            apiKey = secret,
            user = user,
            password = secret,
        )

        ProfileKind.FTP -> FtpChannel(
            host = host,
            port = port.toIntOrNull() ?: 21,
            user = user,
            password = secret,
            remotePath = path,
        )

        ProfileKind.SMB -> SmbChannel(
            host = host,
            shareName = shareName,
            user = user,
            password = secret,
            domain = domain,
            path = path,
            port = port.toIntOrNull() ?: 445,
        )

        ProfileKind.LOCAL -> ShareChannel(context, Uri.parse(treeUri))
    }

    /** 抽屉里显示在主标题下的小字。 */
    fun subtitle(): String = when (kind) {
        ProfileKind.REST -> "REST · $host:$port"
        ProfileKind.FTP -> "FTP · $host:$port$path"
        ProfileKind.SMB -> "SMB · $host\\$shareName"
        ProfileKind.LOCAL -> "本地目录"
    }

    fun roleLabel(): String = if (role == ProfileRole.INFO) "客户端" else "文件通道"
}
