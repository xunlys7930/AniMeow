package com.animeow.app.data.analysis

import java.net.URI

internal data class CustomModelProvider(val name: String, val endpoint: String)

internal val customModelProviders = listOf(
    CustomModelProvider("DeepSeek", DEFAULT_ENDPOINT),
    CustomModelProvider("硅基流动", "https://api.siliconflow.cn/v1"),
    CustomModelProvider("火山方舟", "https://ark.cn-beijing.volces.com/api/v3"),
    CustomModelProvider("阿里云百炼（北京）", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    CustomModelProvider("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
    CustomModelProvider("OpenAI", "https://api.openai.com/v1"),
    CustomModelProvider("自定义 / 中转服务", ""),
)

internal fun customModelBaseUrl(rawEndpoint: String): String {
    val clean = rawEndpoint.trim().trimEnd('/').removeSuffix("/chat/completions")
    require(clean.isNotEmpty()) { "请填写模型接口地址" }
    val uri = runCatching { URI(clean) }.getOrNull() ?: error("模型接口地址格式不正确")
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "请填写完整服务地址，地址中请勿包含账号、查询参数或片段"
    }
    val localHost = uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
    require(uri.scheme.equals("https", true) || (uri.scheme.equals("http", true) && localHost)) {
        "自定义模型需使用 HTTPS；本机调试地址除外"
    }
    val authority = uri.host.lowercase() + if (uri.port == -1) "" else ":${uri.port}"
    return URI("${uri.scheme.lowercase()}://$authority${uri.rawPath}").toASCIIString()
}

internal fun customModelProviderFor(endpoint: String): CustomModelProvider {
    val base = runCatching { customModelBaseUrl(endpoint) }.getOrNull()
    return customModelProviders.firstOrNull { it.endpoint.isNotEmpty() && it.endpoint == base }
        ?: customModelProviders.last()
}
