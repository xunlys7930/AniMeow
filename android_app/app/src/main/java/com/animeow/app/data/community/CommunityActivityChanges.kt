package com.animeow.app.data.community

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-memory notifications keep retained navigation pages in sync without discarding pagination. */
internal object CommunityActivityChanges {
    data class Change(val sessionToken: String, val postId: Long, val post: CommunityPost?)
    data class ProfileChange(val sessionToken: String, val profile: CommunityProfile)
    private val updates = MutableSharedFlow<Change>(extraBufferCapacity = 32)
    private val profiles = MutableSharedFlow<ProfileChange>(extraBufferCapacity = 16)
    val changes = updates.asSharedFlow()
    val profileUpdates = profiles.asSharedFlow()
    fun publish(sessionToken: String, post: CommunityPost) { updates.tryEmit(Change(sessionToken, post.id, post)) }
    fun delete(sessionToken: String, postId: Long) { updates.tryEmit(Change(sessionToken, postId, null)) }
    fun publishProfile(sessionToken: String, profile: CommunityProfile) {
        profiles.tryEmit(ProfileChange(sessionToken, profile))
    }
}
