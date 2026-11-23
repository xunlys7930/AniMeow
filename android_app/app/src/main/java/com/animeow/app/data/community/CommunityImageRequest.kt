package com.animeow.app.data.community

import android.content.Context
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.animeow.app.BuildConfig
import java.net.URI
import java.security.MessageDigest

internal fun sameCommunityOrigin(url: String, base: String): Boolean = runCatching {
    val image = URI(url)
    val server = URI(base)
    fun URI.effectivePort() = if (port >= 0) port else if (scheme.equals("https", true)) 443 else 80
    image.userInfo == null && image.scheme.equals(server.scheme, true) && image.host != null &&
        image.host.equals(server.host, true) && image.effectivePort() == server.effectivePort()
}.getOrDefault(false)

internal fun communityImageCacheKey(url: String, token: String?): String {
    val scope = token?.takeIf(String::isNotBlank)?.let { value -> MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) } } ?: "public"
    return "community:$scope:$url"
}

internal const val CACHE_OWN_AVATAR_PARAMETER = "animeow.cache_own_avatar"

@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal fun communityImageRequest(context: Context, url: String, token: String?, cacheOwnAvatar: Boolean = false): ImageRequest {
    val authorized = token?.takeIf { it.isNotBlank() && sameCommunityOrigin(url, BuildConfig.CLOUD_API_BASE) }
    val key = communityImageCacheKey(url, authorized)
    val avatar = runCatching { URI(url).path.startsWith("/api/community/avatar/") }.getOrDefault(false)
    val persistAvatar = cacheOwnAvatar && authorized != null && avatar
    return ImageRequest.Builder(context).data(url).apply {
        if (authorized != null) addHeader("Authorization", "Bearer $authorized")
    }.memoryCacheKey(key).diskCacheKey(key)
        .diskCachePolicy(if (persistAvatar) CachePolicy.ENABLED else CachePolicy.DISABLED)
        .setParameter(CACHE_OWN_AVATAR_PARAMETER, persistAvatar)
        .apply { if ("/image-reviews/" in url) memoryCachePolicy(CachePolicy.DISABLED) }
        .crossfade(!avatar).build()
}
