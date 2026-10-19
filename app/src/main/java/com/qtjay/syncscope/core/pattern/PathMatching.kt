package com.qtjay.syncscope.core.pattern

/**
 * 把 .stignore 规则里的路径模式还原/匹配到真实目录名。
 *
 * <p>生成规则时，名称以 `(` 开头的目录无法原样写进 syncthing 的 pattern：
 * syncthing 的 glob 会把没转义的字符原样转成正则，`(` 变成分组括号，
 * 整份 .stignore 会编译失败 —— 那不是"这条规则失效"，而是**所有规则一起失效**。
 * 所以 `SelectiveSyncPatterns` 退而用 `?`（glob 的"任意单字符"）兜底。
 *
 * <p>代价是反解出来的路径和磁盘上的真实名字不一致：
 * `?‾◡◝)新入库` ≠ `(‾◡◝)新入库`。这里负责把这个差异补上 ——
 * 否则界面上会显示"没勾选"（而规则其实已生效），
 * 更糟的是校验文件模块会把整棵已选目录判成"多余"建议删掉。
 */
fun patternToRegex(pattern: String): Regex {
    val sb = StringBuilder()
    sb.append('^')
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            '?' -> {
                // glob 的 ? 匹配任意单个字符，但不跨目录
                sb.append("[^/]")
                i++
            }
            '\\' -> {
                // 规则里的 \ 是转义符，去掉它，后一个字符按字面量处理
                if (i + 1 < pattern.length) {
                    sb.append(Regex.escape(pattern[i + 1].toString()))
                    i += 2
                } else {
                    sb.append(Regex.escape("\\"))
                    i++
                }
            }
            else -> {
                sb.append(Regex.escape(c.toString()))
                i++
            }
        }
    }
    sb.append('$')
    return Regex(sb.toString())
}

/**
 * pattern 是否指向真实路径 path。
 *
 * @param pattern 规则里的路径，可能是退化的 `?` 形式
 * @param path    磁盘/目录树上的真实相对路径
 */
fun patternMatchesPath(pattern: String, path: String): Boolean =
    pattern == path || patternToRegex(pattern).matches(path)
