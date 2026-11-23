package com.animeow.app.data.remote

/**
 * 把用户随手输入的 Bangumi 标识归一化成官方接口能接受的形式。
 *
 * 站内提及、个人主页链接都是常见的输入方式，但它们都不能直接拼进
 * `/v0/users/{name}/collections`：
 *
 * - `@599157` → 会被编码成 `%40599157`，Bangumi 侧当成用户名 `@599157`，必然 404
 * - `https://bgm.tv/user/599157` → 整条链接当用户名，同样 404
 * - `599157/collections` → 复制链接时多带了尾巴
 *
 * 归一化只做"去掉多余的包装"，不猜用户想搜谁。
 */
internal fun normalizeBangumiUsername(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""

    PROFILE_URL.find(trimmed)?.let { match ->
        return match.groupValues[1].trim()
    }

    return trimmed
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .removePrefix("@")
        .trim()
}

/** `bgm.tv/user/<name>` / `bangumi.tv/user/<name>`，允许不带协议、允许后面跟子路径。 */
private val PROFILE_URL =
    Regex("""(?i)^(?:https?://)?(?:www\.)?(?:bgm\.tv|bangumi\.tv)/user/([^/?#\s]+)""")
