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

    fun setFilter(filter: GalleryFilter) {
        _filter.value = filter
    }

    fun deleteItem(id: String) {
        captureHistoryStore.deleteMedia(id)
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
