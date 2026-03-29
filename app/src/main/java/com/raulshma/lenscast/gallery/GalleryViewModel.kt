package com.raulshma.lenscast.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.raulshma.lenscast.capture.model.CaptureHistory
import com.raulshma.lenscast.capture.model.CaptureType
import com.raulshma.lenscast.data.CaptureHistoryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GalleryFilter {
    ALL, PHOTOS, VIDEOS
}

class GalleryViewModel(
    private val captureHistoryStore: CaptureHistoryStore,
) : ViewModel() {

    init {
        captureHistoryStore.refreshFromMediaStore()
    }

    private val _filter = MutableStateFlow(GalleryFilter.ALL)
    val filter: StateFlow<GalleryFilter> = _filter.asStateFlow()

    val galleryItems: StateFlow<List<CaptureHistory>> = combine(
        captureHistoryStore.history,
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
            captureHistoryStore.deleteMediaBatch(ids)
            _selectedIds.value = emptySet()
            _batchDeleting.value = false
        }
    }

    fun getItemById(id: String): CaptureHistory? {
        return captureHistoryStore.history.value.find { it.id == id }
    }

    class Factory(
        private val captureHistoryStore: CaptureHistoryStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return GalleryViewModel(captureHistoryStore) as T
        }
    }
}
