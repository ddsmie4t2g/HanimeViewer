package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import io.github.daisukikaffuchino.utils.LogUtil
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.MySubscriptions
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionVideosItem
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class MySubscriptionsViewModel : ViewModel() {

    private val _subscriptionsState = MutableStateFlow<WebsiteState<MySubscriptions>>(WebsiteState.Loading)
    val subscriptionsState: StateFlow<WebsiteState<MySubscriptions>> = _subscriptionsState.asStateFlow()

    private var currentPage = 1
    private var hasMore = true
    private var isLoadingMore = false
    private val cachedVideos = mutableListOf<SubscriptionVideosItem>()
    private val cachedArtists = mutableListOf<SubscriptionItem>()

    private val _refreshCompleted = MutableSharedFlow<Unit>()
    val refreshCompleted: SharedFlow<Unit> = _refreshCompleted

    private var hasLoaded = false
    fun reset() {
        hasLoaded = false
        _subscriptionsState.value = WebsiteState.Loading
    }

    fun loadMySubscriptions(forceReload: Boolean = false) {
        if (isLoadingMore) return
        if (forceReload) {
            currentPage = 1
            hasMore = true
            cachedVideos.clear()
            cachedArtists.clear()
        }
        isLoadingMore = true

        viewModelScope.launch {
            NetworkRepo.getMySubscriptions(page = currentPage)
                .onStart {
                    if (currentPage == 1) {
                        _subscriptionsState.value = WebsiteState.Loading
                    }
                }
                .catch { e ->
                    _subscriptionsState.value = WebsiteState.Error(e)
                    _refreshCompleted.emit(Unit)
                    isLoadingMore = false
                }
                .collect { state ->
                    if (state is WebsiteState.Success) {
                        _refreshCompleted.emit(Unit)
                        val info = state.info
                        if (currentPage == 1) {
                            cachedArtists.clear()
                            cachedArtists.addAll(info.subscriptions)
                            // ⭐ 订到的作者顺带记进本机关注库 → 进而同步到用户自建账号。
                            recordSubscriptions(info.subscriptions)
                        }
                        if (info.subscriptionsVideos.isNotEmpty()) {
                            cachedVideos.addAll(info.subscriptionsVideos)
                            currentPage++
                            LogUtil.i("getMySubscriptions","currentPage:$currentPage")
                        } else {
                            hasMore = false
                        }
                        _subscriptionsState.value = WebsiteState.Success(
                            MySubscriptions(
                                subscriptions = cachedArtists.toList(),
                                subscriptionsVideos = cachedVideos.toList(),
                                maxPage = info.maxPage
                                )
                        )
                    } else if (state is WebsiteState.Error){
                        _subscriptionsState.value = WebsiteState.Error(state.throwable)
                    }
                    isLoadingMore = false
                }
        }
    }

    fun canLoadMore() = hasMore && !isLoadingMore

    /**
     * ⭐ 把 hanime 服务端订阅**记到本机**，并顺手推给用户自建账号。
     *
     * 解决的正是「不登录 hanime 就看不到订阅」这件事：
     *
     * ```
     * 登录 hanime → 拉到订阅 ─┬→ 界面显示（服务端那份，实时）
     *                        └→ 写进本机关注库 → 同步到自建账号 → 换机 / 退出 hanime 后仍可见
     * ```
     *
     * 只在**真的新增了条目**时才上传：每次翻页都传一遍账号数据既没必要也浪费流量，
     * 第一次订满之后后面基本都是 0 新增。上传失败静默忽略 —— 它是锦上添花，
     * 本机那份已经写好了，下次登录/同步还会再传一次。
     */
    private fun recordSubscriptions(items: List<SubscriptionItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val added = runCatching { FollowedArtistStore.mergeSubscriptionItems(items) }
                .getOrDefault(0)
            if (added <= 0) return@launch
            if (AccountRepository.isLoggedIn) AccountRepository.uploadLocal()
        }
    }
}
