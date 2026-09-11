package com.raulshma.lenscast.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.DecryptedPhotoCache
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class GalleryFilter {
    ALL, PHOTOS, VIDEOS
}

class GalleryViewModel(
    private val captureHistoryStore: CaptureHistoryStore,
    /**
     * The decrypted-photo cache behind Coil when media encryption is on;
     * null (tests, or encryption never relevant) disables the warm loop.
     */
    private val photoCache: DecryptedPhotoCache? = null,
) : ViewModel() {

    init {
        captureHistoryStore.refreshFromMediaStore()
        // The decryption warm loop: encrypted photos become cache files the
        // grid/viewer can hand Coil; encrypted videos are collected into the
        // placeholder set (they play through the decrypting stream, but their
        // thumbnails cannot be framed). All sniff/decrypt IO is off-main.
        if (photoCache != null) {
            viewModelScope.launch(Dispatchers.IO) {
                captureHistoryStore.history.collect { items -> warmDecryption(items) }
            }
        }
    }

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()
    val allItems: StateFlow<List<CaptureHistory>> = captureHistoryStore.history

    private val _decryptedPhotos = MutableStateFlow<Map<String, File>>(emptyMap())

    /**
     * History id → decrypted cache file, for photos that are encrypted at
     * rest. Absent ids load through their normal model.
     */
    val decryptedPhotos: StateFlow<Map<String, File>> = _decryptedPhotos.asStateFlow()

    private val _encryptedVideoIds = MutableStateFlow<Set<String>>(emptySet())

    /** Videos encrypted at rest: the grid/viewer show a placeholder thumbnail. */
    val encryptedVideoIds: StateFlow<Set<String>> = _encryptedVideoIds.asStateFlow()

    private suspend fun warmDecryption(items: List<CaptureHistory>) {
        val cache = photoCache ?: return
        val photos = mutableMapOf<String, File>()
        val videos = mutableSetOf<String>()
        for (item in items) {
            when (item.type) {
                CaptureType.PHOTO ->
                    cache.ensure(item.id, item.fileName, item.filePath)?.let { photos[item.id] = it }
                CaptureType.VIDEO ->
                    if (cache.sniffEncrypted(item.filePath)) videos.add(item.id)
            }
        }
        _decryptedPhotos.value = photos
        _encryptedVideoIds.value = videos
    }

    val galleryItems: StateFlow<List<CaptureHistory>> = combine(
        allItems,
        _filter,
    ) { history, filter ->
        when (filter) {
            GalleryFilter.ALL -> history
            GalleryFilter.PHOTOS -> history.filter { it.type == CaptureType.PHOTO }
            GalleryFilter.VIDEOS -> history.filter { it.type == CaptureType.VIDEO }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectMode = MutableStateFlow(false)
    val selectMode: StateFlow<Boolean> = _selectMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _batchDeleting = MutableStateFlow(false)
    val batchDeleting: StateFlow<Boolean> = _batchDeleting.asStateFlow()

    fun setFilter(filter: GalleryFilter) {
        _filter.value = filter
    }

    fun refresh() {
        captureHistoryStore.refreshFromMediaStore()
    }

    fun setSelectMode(enabled: Boolean) {
        _selectMode.value = enabled
        if (!enabled) {
            _selectedIds.value = emptySet()
        }
    }

    fun toggleSelect(id: String) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) {
            current - id
        } else {
            current + id
        }
    }

    fun selectAll() {
        _selectedIds.value = galleryItems.value.mapTo(mutableSetOf()) { it.id }
    }

    fun selectNone() {
        _selectedIds.value = emptySet()
    }

    fun deleteItem(id: String) {
        captureHistoryStore.deleteMedia(id)
    }

    fun deleteSelected() {
        val ids = _selectedIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            _batchDeleting.value = true
            captureHistoryStore.deleteAll(ids)
            _selectedIds.value = emptySet()
            _batchDeleting.value = false
        }
    }

    class Factory(
        private val captureHistoryStore: CaptureHistoryStore,
        private val photoCache: DecryptedPhotoCache? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(captureHistoryStore, photoCache) as T
        }
    }
}
