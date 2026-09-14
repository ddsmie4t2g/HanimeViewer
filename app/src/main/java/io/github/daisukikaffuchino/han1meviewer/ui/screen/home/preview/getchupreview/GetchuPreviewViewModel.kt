package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview

import io.github.daisukikaffuchino.utils.LogUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.GetchuNetworkRepo.getGetchuPreview
import io.github.daisukikaffuchino.han1meviewer.logic.GetchuNetworkRepo.getGetchuPreviewDetail
import io.github.daisukikaffuchino.han1meviewer.logic.model.GetchuPreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.GetchuPreviewDetail
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GetchuPreviewViewModel : ViewModel() {

    private val previewCache = linkedMapOf<String, PageState<GetchuPreview>>()
    private val detailCache = linkedMapOf<String, PageState<GetchuPreviewDetail>>()

    private val _previewFlow = MutableStateFlow<PageState<GetchuPreview>>(PageState.Loading)
    val previewFlow = _previewFlow.asStateFlow()

    private val _detailStates =
        MutableStateFlow<Map<String, PageState<GetchuPreviewDetail>>>(emptyMap())

    fun detailState(id: String) = _detailStates
        .map { states -> states[id] ?: PageState.Loading }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), detailCache[id] ?: PageState.Loading)

    fun getPreview(date: String) {
        viewModelScope.launch {
            LogUtil.d("GetchuPreviewVM", "getPreview date=$date cacheHit=${previewCache.containsKey(date)}")
            previewCache[date]?.let {
                _previewFlow.value = it
                LogUtil.d("GetchuPreviewVM", "emit cached list date=$date state=${it.logSummary()}")
                return@launch
            }
            _previewFlow.value = PageState.Loading
            getGetchuPreview(date).collect { state ->
                val pageState = state.toPageState()
                LogUtil.d("GetchuPreviewVM", "emit list date=$date state=${pageState.logSummary()}")
                _previewFlow.value = pageState
                if (pageState is PageState.Success || pageState is PageState.NoMoreData) {
                    previewCache[date] = pageState
                }
            }
        }
    }

    /**
     * 后台预取相邻月份的发售表，**不推动当前画面**。
     *
     * 为什么值得做：翻月是这一页最常见的动作，而一次发售表请求经中转要
     * 500–700 ms（大陆↔洛杉矶往返 + getchu 自身响应）。提前一个月取回来，
     * 用户点「下月」时数据已在缓存里，整个翻月接近瞬时。
     *
     * 与 [getPreview] 的关键差别：**绝不写 `_previewFlow`**。写的话预取结果会
     * 直接覆盖用户正在看的那一页 —— 这正是 MEMORY 里「周期性刷新不得覆盖事件型状态」
     * 那条铁律的同类错误。这里只在成功时落缓存。
     */
    fun preloadPreview(date: String) {
        if (previewCache.containsKey(date)) return
        viewModelScope.launch {
            runCatching {
                getGetchuPreview(date)
                    .catch { emit(WebsiteState.Error(it)) }
                    .first { it !is WebsiteState.Loading }
            }.getOrNull()?.let { state ->
                val pageState = state.toPageState()
                if (pageState is PageState.Success || pageState is PageState.NoMoreData) {
                    previewCache[date] = pageState
                    LogUtil.d("GetchuPreviewVM", "preloaded list date=$date state=${pageState.logSummary()}")
                }
            }
        }
    }

    fun getDetail(id: String) {
        viewModelScope.launch {
            LogUtil.d("GetchuPreviewVM", "getDetail id=$id cacheHit=${detailCache.containsKey(id)}")
            detailCache[id]?.let { cachedState ->
                setDetailState(id, cachedState)
                LogUtil.d("GetchuPreviewVM", "emit cached detail id=$id state=${cachedState.logSummary()}")
                return@launch
            }
            setDetailState(id, PageState.Loading)
            getGetchuPreviewDetail(id).collect { state ->
                val pageState = state.toPageState()
                LogUtil.d("GetchuPreviewVM", "emit detail id=$id state=${pageState.logSummary()}")
                setDetailState(id, pageState)
                if (pageState is PageState.Success || pageState is PageState.NoMoreData) {
                    detailCache[id] = pageState
                }
            }
        }
    }

    private fun setDetailState(id: String, state: PageState<GetchuPreviewDetail>) {
        _detailStates.value += (id to state)
    }

    private fun <T> WebsiteState<T>.toPageState(): PageState<T> {
        return when (this) {
            is WebsiteState.Loading -> PageState.Loading
            is WebsiteState.Error -> PageState.Error(throwable)
            is WebsiteState.Success -> PageState.Success(info)
        }
    }

    private fun PageState<*>.logSummary(): String {
        return when (this) {
            is PageState.Loading -> "Loading"
            is PageState.Empty -> "Empty"
            is PageState.Error -> "Error(${throwable::class.simpleName}: ${throwable.message})"
            is PageState.NoMoreData<*> -> "NoMoreData"
            is PageState.Success<*> -> when (val value = info) {
                is GetchuPreview -> "Success(GetchuPreview groups=${value.groups.size} totalItems=${value.groups.sumOf { it.items.size }})"
                is GetchuPreviewDetail -> "Success(GetchuPreviewDetail title=${value.title.take(60)} samples=${value.sampleImages.size})"
                else -> "Success(${value?.let { it::class.simpleName }})"
            }
        }
    }
}
