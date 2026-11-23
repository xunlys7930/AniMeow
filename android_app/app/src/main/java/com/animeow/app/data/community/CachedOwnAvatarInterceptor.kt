package com.animeow.app.data.community

import android.content.Context
import coil.disk.DiskCache
import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.CachePolicy
import coil.request.ImageResult
import coil.request.SuccessResult

/** Own identity is explicitly cached by account; other people's private media keeps normal revalidation. */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal class CachedOwnAvatarInterceptor(private val diskCache: () -> DiskCache?) : Interceptor {
    constructor(context: Context) : this({ context.applicationContext.imageLoader.diskCache })

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val key = request.diskCacheKey ?: return chain.proceed(request)
        if (request.parameters.value<Boolean>(CACHE_OWN_AVATAR_PARAMETER) != true || !key.startsWith("community:")) {
            return chain.proceed(request)
        }
        val cache = diskCache()
        val snapshot = cache?.openSnapshot(key)
        if (snapshot != null) {
            // Hold the snapshot while decoding. only-if-cached still fails on no-cache responses in Coil,
            // so read these already authorized bytes directly instead of creating another HTTP request.
            snapshot.use {
                val result = chain.proceed(request.newBuilder().data(it.data.toFile()).diskCachePolicy(CachePolicy.DISABLED).build())
                if (result is SuccessResult) return result
            }
            cache?.remove(key)
        }
        return chain.proceed(request)
    }
}
