package com.animeow.app.ui.discovery

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.RemoteImportResult
import com.animeow.app.data.local.matchesExternalReference
import com.animeow.app.data.media.copyUriToCacheFile
import com.animeow.app.data.media.cleanupCacheUri
import com.animeow.app.data.remote.ImageSearchMatch
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

data class ImageSearchUiState(
    val imageUri: Uri? = null,
    val matches: List<ImageSearchMatch> = emptyList(),
    val isSearching: Boolean = false,
    val importingKey: String? = null,
    val error: String? = null,
    val duplicate: RemoteDuplicateRequest? = null,
    val hasSearched: Boolean = false,
    val importError: String? = null,
)

class ImageSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val _state = MutableStateFlow(ImageSearchUiState())
    val state: StateFlow<ImageSearchUiState> = _state.asStateFlow()
    private val _openAnime = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openAnime = _openAnime.asSharedFlow()
    private var searchJob: Job? = null
    private var ownedImage: Uri? = null

    fun search(uri: Uri) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(imageUri = uri, isSearching = true, matches = emptyList(), error = null, hasSearched = false, duplicate = null) }
            runCatchingCancellable {
                val cached = if (uri == ownedImage) uri else {
                    copyUriToCacheFile(app, uri, "search_img", maxBytes = 20L * 1024 * 1024).also { next ->
                        ownedImage?.let { cleanupCacheUri(app, it) }
                        ownedImage = next
                    }
                }
                _state.update { it.copy(imageUri = cached) }
                app.discoveryRepository.searchByImage(cached)
            }
                .onSuccess { matches ->
                    _state.update {
                        it.copy(
                            matches = matches,
                            isSearching = false,
                            error = null,
                            hasSearched = true,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isSearching = false, error = error.message ?: "识图失败，请稍后重试")
                    }
                }
        }
    }

    fun retry() {
        state.value.imageUri?.let(::search)
    }

    fun cancelSearch() {
        searchJob?.cancel()
        _state.update { it.copy(isSearching = false) }
    }

    fun dismissImportError() { _state.update { it.copy(importError = null) } }

    override fun onCleared() {
        searchJob?.cancel()
        ownedImage?.let { cleanupCacheUri(app, it) }
        super.onCleared()
    }

    fun import(anime: RemoteAnime, force: Boolean = false) {
        if (state.value.importingKey != null) return
        _state.update { it.copy(importingKey = anime.uniqueKey, importError = null) }
        viewModelScope.launch {
            runCatchingCancellable { app.libraryRepository.importRemoteAnime(anime, force) }
                .onSuccess { result ->
                    when (result) {
                        is RemoteImportResult.Added -> {
                            _state.update { it.copy(importingKey = null) }
                            _openAnime.emit(result.animeId)
                        }
                        is RemoteImportResult.Duplicate -> {
                            _state.update {
                                it.copy(
                                    importingKey = null,
                                    duplicate = RemoteDuplicateRequest(anime, result.existing),
                                )
                            }
                        }
                    }
                }
                .onFailure { error ->
                    _state.update { it.copy(importingKey = null, importError = error.message ?: "加入资料库失败") }
                }
        }
    }

    fun openExistingDuplicate() {
        val id = state.value.duplicate?.existing?.id ?: return
        _state.update { it.copy(duplicate = null) }
        _openAnime.tryEmit(id)
    }

    fun forceImportDuplicate() {
        val request = state.value.duplicate ?: return
        val exact = request.existing.matchesExternalReference(request.remote.importSource, request.remote.importId)
        _state.update { it.copy(duplicate = null) }
        if (exact) _openAnime.tryEmit(request.existing.id) else import(request.remote, force = true)
    }

    fun dismissDuplicate() {
        _state.update { it.copy(duplicate = null) }
    }
}
