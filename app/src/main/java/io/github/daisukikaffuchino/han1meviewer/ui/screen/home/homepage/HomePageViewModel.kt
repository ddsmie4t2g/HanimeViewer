package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.applicationContext
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateChecker
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateDownloader
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateState
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.entity.HKeyframeEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.WatchHistoryEntity
import io.github.daisukikaffuchino.han1meviewer.logic.exception.LoginStateExpiredException
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.ph.PhCarouselBatches
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.logout
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.AppViewModel
import io.github.daisukikaffuchino.utils.SonnerToast
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.HanimeScreen
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.HomeRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.TopLevelBackStack
import io.github.daisukikaffuchino.han1meviewer.worker.AppUpdateWorker
import io.github.daisukikaffuchino.han1meviewer.worker.AppUpdateWorkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File

class HomePageViewModel: ViewModel() {
    val mainBackStack = TopLevelBackStack<HanimeScreen>(HomeRoute)

    private companion object {
        const val TAG = "HomePageViewModel"
    }

    data class SessionExpiredMessage(
        val message: String?,
        @param:StringRes val fallbackResId: Int,
    )

    /**
     * 应用内更新下载的 UI 状态。
     *
     * 之前点「立即更新」是 `uriHandler.openUri(downloadUrl)` → 跳浏览器，
     * 现在改为应用内下载 + 拉起安装器，这里承载进度。
     */
    sealed interface UpdateDownloadState {
        /** 未开始 / 已结束 */
        data object Idle : UpdateDownloadState

        /** 已入队等网络 */
        data object Pending : UpdateDownloadState

        /** 正在下载。`progress` 为 null = 未知总大小；`bytes` 是「确实在下」的硬证据。 */
        data class Downloading(val progress: Int?, val bytes: Long) : UpdateDownloadState

        /** 包已就绪、可以安装 */
        data class ReadyToInstall(val apkFile: File) : UpdateDownloadState

        data class Failed(val message: String?) : UpdateDownloadState
    }

    private val _homePageFlow = MutableStateFlow<PageState<HomeData>>(PageState.Loading)
    val homePageFlow = _homePageFlow.asStateFlow()

    private val _sessionExpiredMessage = MutableSharedFlow<SessionExpiredMessage>()
    val sessionExpiredMessage = _sessionExpiredMessage

    private val _appUpdateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Checking)
    val appUpdateState = _appUpdateState.asStateFlow()

    private val _updateDownloadState =
        MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateDownloadState = _updateDownloadState.asStateFlow()

    private val _updateAnnouncement = MutableStateFlow<Announcement?>(null)
    val updateAnnouncement = _updateAnnouncement.asStateFlow()

    private val _phCarouselShuffling = MutableStateFlow(false)

    /**
     * 首页那块大轮播是否正在「换一批」。
     *
     * 必须显式暴露：换一批要真的去站点要下一批（推荐是 ~1 MB HTML、
     * 主页热门是 ~1.25 MB HTML，见 `PhNetwork.homeUrl`），**没有反馈的按钮
     * 会被当成没反应**，然后用户会连着点几下 —— 那几下都会变成重复请求。
     * UI 拿它禁用按钮 + 换文案。
     */
    val phCarouselShuffling = _phCarouselShuffling.asStateFlow()

    private val _phCarouselTitleRes = MutableStateFlow(R.string.ph_recommended)

    /**
     * 大轮播那一行现在该显示什么标题。
     *
     * 两个来源的内容不是一回事（「推荐」是站点推荐引擎给的，「热门」是主页那个大网格），
     * 换到热门那几批时标题还写「推荐」就是**在骗用户**。所以标题跟着来源走。
     *
     * 初值 = 「推荐」：首页首次加载填的正是推荐第 1 页。
     */
    val phCarouselTitleRes = _phCarouselTitleRes.asStateFlow()

    /**
     * 当前展示的是**第几批**（见 [PhCarouselBatches]）。首页首次加载填的是第 0 批
     * （推荐第 1 页），所以「换一批」从 1 起算。
     */
    private var phCarouselBatch = 0

    /**
     * 主页「热门色情视频」那 61 条的缓存。
     *
     * ⚠️ 必须缓存：那一趟是 **1.25 MB 整页 HTML**，而热门那 3 批**来自同一份响应**
     * （主页没有分页，见 `PhParser.homepageHotList`）。不缓存的话，用户在热门那几批之间
     * 来回切一次就重下一遍整页。
     */
    private var phHomepageHotCache: MutableList<HanimeInfo>? = null

    private var homePageJob: Job? = null
    private var initializationJob: Job? = null
    private var updateDownloadJob: Job? = null
    private var phCarouselJob: Job? = null

    init {
        viewModelScope.launch {
            // 初始化默认已下载分组，防止[FOREIGN KEY constraint failed]
            DatabaseRepo.HanimeDownload.insertDefaultGroup()
        }
        observeUpdateDownload()
    }

    /**
     * 订阅下载任务。
     *
     * 关键判断：任务处于 Finished 时，先看**当前版本是否已经追上下载的那个 versionCode**。
     * 装着旧版本时下载完成 → 弹安装器；装完重启后同一个 WorkManager 记录仍是 SUCCEEDED，
     * 此时 `BuildConfig.VERSION_CODE >= targetVersionCode`，说明已经装上去了（或用户用别的方式
     * 更新过），就顺手把残留的 APK 清掉，不再骚扰用户。这样不需要额外持久化「已处理」标记。
     */
    private fun observeUpdateDownload() {
        updateDownloadJob = viewModelScope.launch {
            AppUpdateWorker.observe(applicationContext)
                .catch { e -> LogUtil.e(TAG, "观察更新下载任务失败", e) }
                .collect { state ->
                    _updateDownloadState.value = when (state) {
                        is AppUpdateWorkState.Idle -> UpdateDownloadState.Idle
                        is AppUpdateWorkState.Pending -> UpdateDownloadState.Pending
                        is AppUpdateWorkState.Running ->
                            UpdateDownloadState.Downloading(state.progress, state.bytes)

                        is AppUpdateWorkState.Finished -> {
                            if (state.targetVersionCode in 1..BuildConfig.VERSION_CODE) {
                                AppUpdateDownloader.clearApkFile()
                                UpdateDownloadState.Idle
                            } else {
                                UpdateDownloadState.ReadyToInstall(state.apkFile)
                            }
                        }

                        is AppUpdateWorkState.Failed ->
                            UpdateDownloadState.Failed(state.message)
                    }
                }
        }
    }

    /** 点「立即更新」：应用内开始下载（已经是下载好的包就什么都不做，由 UI 直接走安装）。 */
    fun startUpdateDownload(url: String, versionCode: Int) {
        if (url.isBlank() || versionCode <= 0) return
        when (_updateDownloadState.value) {
            is UpdateDownloadState.Downloading, is UpdateDownloadState.Pending -> return
            is UpdateDownloadState.ReadyToInstall -> return
            else -> Unit
        }
        _updateDownloadState.value = UpdateDownloadState.Pending
        AppUpdateWorker.enqueue(applicationContext, url, versionCode)
    }

    /** 安装完成后这条任务就没意义了，清掉，避免清缓存后 UI 还停在「安装」。 */
    fun clearUpdateDownloadState() {
        AppUpdateWorker.cancel(applicationContext)
        AppUpdateDownloader.clearApkFile()
        _updateDownloadState.value = UpdateDownloadState.Idle
    }

    /**
     * 首屏初始化：**首页内容与更新检查同时开始**。
     *
     * ## 为什么不再「先检查更新、再加载首页」
     *
     * 老顺序是 `checkForUpdate()` 回来之后才 `getHomePage()`，而更新检查要并发问 5 条更新源
     * （部分网络下其中几条是黑洞，只能靠 connectTimeout 兜底）。于是「打开 App」实际是
     * **先等更新检查**——界面上那段时间就是一句「正在检查更新」的转圈（见 `HomePageScreen`
     * 的 `updateState is Checking` 分支）。用户报的「检测更新时间有点长」正是这一段。
     *
     * 现在两件事互不依赖，各跑各的：
     * - 首页内容立刻开始加载（数据一到就画出来）；
     * - 更新检查在后台跑，回来时把「更新卡片 / 公告」插进去。
     *
     * ⚠️ **强制更新**的语义没变：它照样整页接管（`HomePageScreen` 的 `forcedUpdate` 分支），
     * 只是首页那点数据已经在后台加载好了 —— 先加载没有副作用。
     *
     * ⚠️ 首页这条路**不查上游版本**（`includeUpstream = false`）：它只消费 `updateInfo`
     * 与 `announcement`，上游那条支线（`AppUpdateCheckResult.upstream`）只有「关于」页的
     * 手动检查弹窗才用得上，带着它只是白等一个请求。
     */
    fun initializeHomePage() {
        // 【自用构建】原来这里要求「使用须知已接受 + 应用来源已验证」才放行，
        // 那是给公开分发用的门禁。本构建已去掉这两个对话框，门禁一并移除，
        // 免得旧版本残留的 false 把首页数据挡在门外。
        if (initializationJob != null || _appUpdateState.value !is AppUpdateState.Checking) return
        loadHomePage(isRefresh = false)
        initializationJob = viewModelScope.launch {
            val updateResult = AppUpdateChecker.checkForUpdate(includeUpstream = false)
            _updateAnnouncement.value = updateResult.announcement
            val updateInfo = updateResult.updateInfo
            _appUpdateState.value = updateInfo
                ?.let { AppUpdateState.Available(it) }
                ?: AppUpdateState.NoUpdate
        }
    }

    fun ignoreUpdate(versionCode: Int) {
        val available = _appUpdateState.value as? AppUpdateState.Available ?: return
        if (available.info.forceUpdate || available.info.versionCode != versionCode) return
        viewModelScope.launch {
            AppUpdateChecker.ignoreUpdate(versionCode)
            _appUpdateState.value = AppUpdateState.NoUpdate
        }
    }

    /**
     * 首页内容入口（下拉刷新 / 重试 / 首屏都走它）。
     *
     * 更新检查还在跑时**不再把整个首页挡回去**：首屏交给 [initializeHomePage]
     * （它已经会把内容加载起来），已经初始化过就直接加载 —— 否则「检查更新还在跑」
     * 会让下拉刷新、错误重试全部变成点了没反应。
     */
    fun getHomePage(isRefresh: Boolean = false) {
        when (val updateState = _appUpdateState.value) {
            AppUpdateState.Checking -> if (initializationJob == null) {
                initializeHomePage()
                return
            }

            is AppUpdateState.Available -> if (updateState.info.forceUpdate) return
            AppUpdateState.NoUpdate -> Unit
        }
        loadHomePage(isRefresh)
    }

    private fun loadHomePage(isRefresh: Boolean) {
        homePageJob?.cancel()
        // 首页重来一次，轮播也回到「第 0 批」（推荐第 1 页）—— 与页面上真正显示的内容对齐。
        // ⚠️ 只重置**批次号**，不清 `phHomepageHotCache`：那一趟是 1.25 MB 整页 HTML，
        //    而主页内容按出口 IP 固定，下拉刷新没理由让它白下一次。
        phCarouselBatch = 0
        homePageJob = viewModelScope.launch {
            val current = _homePageFlow.value
            if (isRefresh && current is PageState.Success) {
                _homePageFlow.value = current.copy(isRefreshing = true)
            } else if (isRefresh && current is PageState.Error && current.cachedInfo != null) {
                _homePageFlow.value = PageState.Success(info = current.cachedInfo, isRefreshing = true)
            } else if (!isRefresh && current !is PageState.Success){
                _homePageFlow.value = PageState.Loading
            }
            NetworkRepo.getHomePage().collect { networkState ->
                when (networkState){
                    is WebsiteState.Error -> {
                        if (networkState.throwable is LoginStateExpiredException) {
                            logout()
                            _sessionExpiredMessage.emit(
                                SessionExpiredMessage(
                                    message = networkState.throwable.message,
                                    fallbackResId = R.string.login_state_expired,
                                )
                            )
                        }
                        val previousData = (_homePageFlow.value as? PageState.Success)?.info
                        _homePageFlow.value = PageState.Error(networkState.throwable, cachedInfo = previousData)
                    }
                    is WebsiteState.Success -> {
                        AppViewModel.csrfToken = networkState.info.csrfToken
                        networkState.info.userId.takeIf { it.isNotEmpty() }?.let { userId ->
                            SettingsRepository.setSavedUserId(userId)
                        }
                        val homeData = HomeData(page = networkState.info)
                        _homePageFlow.value = PageState.Success(info = homeData, isRefreshing = false)
                    }
                    is WebsiteState.Loading -> { }
                }
            }
        }
    }

    fun dismissAnnouncements(){
        val current = _homePageFlow.value
        if (current is PageState.Success) {
            _homePageFlow.value = current.copy(info = current.info.copy(announcements = emptyList()))
        }
    }

    /**
     * 首页那块大轮播的**「换一批」**（26.9.7）。
     *
     * ## 为什么必须真去问站点
     *
     * 「推荐」那一批**不是随机的、也不是每次新算的**：实测同一出口 IP 连抓 13 次
     * （含换 cookie / Referer / 加随机串）**21/21 完全一致**；换一台中转机才拿到
     * 0/21 重合的另一批 —— 即**内容按出口 IP 固定**。所以「在本地 21 条里打乱」
     * 是假的，必须去要**另一批**。批次表见 [PhCarouselBatches]。
     *
     * ## 两个来源的能力不一样
     *
     * - 「推荐」：21 条/页，**能翻 18+ 页**（越界回 404 ⇒ 这里回卷到第 1 页）；
     * - 主页「热门色情视频」：**61 条一次性**，主页没有分页也没有加载更多接口 ⇒
     *   本地按 21 条切成 3 批轮换，且**结果缓存**（那一趟是 1.25 MB 整页 HTML）。
     *
     * ⚠️ 全程互斥（`phCarouselJob`）：连点几下不能变成几个并发的大请求。
     * 同时把 [phCarouselShuffling] 抛给 UI 去禁用按钮。
     */
    fun shufflePhCarousel() {
        if (phCarouselJob?.isActive == true) return
        phCarouselJob = viewModelScope.launch {
            _phCarouselShuffling.value = true
            try {
                val next = phCarouselBatch + 1
                val batch = PhCarouselBatches.batchAt(next)
                var items = loadPhCarouselBatch(batch)

                if (items.isEmpty() && batch is PhCarouselBatches.Batch.Recommended) {
                    // 「推荐」翻过头了 —— 站点对越界页回 404（不是空页）。
                    // 回卷第 1 页重来，**别把按钮点死**：这是正常的循环，不是错误。
                    val restart = loadPhCarouselBatch(PhCarouselBatches.Batch.Recommended(1))
                    if (restart.isNotEmpty()) {
                        phCarouselBatch = 0
                        _phCarouselTitleRes.value = R.string.ph_recommended
                        applyPhCarouselItems(restart)
                        return@launch
                    }
                }

                if (items.isEmpty()) {
                    // 真拿不到（站点改版 / 被限流）。保持原样并如实提示，
                    // 不要静默什么都不发生 —— 那会让用户以为按钮坏了。
                    SonnerToast.error(R.string.ph_shuffle_failed)
                    return@launch
                }

                phCarouselBatch = next
                // 标题跟着来源走（推荐 / 主页热门），别让内容与标题对不上。
                _phCarouselTitleRes.value = when (batch) {
                    is PhCarouselBatches.Batch.Recommended -> R.string.ph_recommended
                    is PhCarouselBatches.Batch.HomepageHot -> R.string.ph_hot
                }
                applyPhCarouselItems(items)
            } catch (e: CancellationException) {
                // 主动取消不是失败，原样抛（见 26.9.3 那条日志教训）。
                throw e
            } catch (e: Exception) {
                LogUtil.e(TAG, "换一批失败", e)
                SonnerToast.error(R.string.ph_shuffle_failed)
            } finally {
                _phCarouselShuffling.value = false
            }
        }
    }

    /**
     * 取一批轮播内容。[PhCarouselBatches.Batch.HomepageHot] 的几批来自**同一份缓存**，
     * 所以第二次轮到热门时不会再下那 1.25 MB。
     */
    private suspend fun loadPhCarouselBatch(
        batch: PhCarouselBatches.Batch,
    ): List<HanimeInfo> = when (batch) {
        is PhCarouselBatches.Batch.Recommended -> NetworkRepo.getPhRecommendedPage(batch.page)

        is PhCarouselBatches.Batch.HomepageHot -> {
            val all = phHomepageHotCache ?: NetworkRepo.getPhHomepageHot()
                .takeIf { it.isNotEmpty() }
                ?.also { phHomepageHotCache = it }
                .orEmpty()
            val from = batch.index * PhCarouselBatches.BATCH_SIZE
            all.drop(from).take(PhCarouselBatches.BATCH_SIZE)
        }
    }

    /**
     * 把新的一批塞进已经显示出来的首页数据里。
     *
     * ⚠️ **只动 `newAnimeTrailer` 这一个槽位**（Pornhub 用它装那一行大轮播，
     * 见 `HomePageMappers`），**不重新加载整个首页** —— 重新加载会让别的 10 行
     * 一起闪一下、还会把用户滚动位置顶掉。
     *
     * ⚠️ 首页还没加载出来时直接放弃：那时轮播上根本没有按钮可点，
     * 能走到这里说明是竞态，丢掉这一批比拼出一个半成品首页好。
     */
    private fun applyPhCarouselItems(items: List<HanimeInfo>) {
        val current = _homePageFlow.value
        if (current !is PageState.Success) return
        _homePageFlow.value = current.copy(
            info = current.info.copy(
                page = current.info.page.copy(newAnimeTrailer = items.toMutableList())
            )
        )
    }

    fun deleteWatchHistory(history: WatchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.delete(history)
            LogUtil.d("delete_watch_hty", "$history DONE!")
        }
    }

    fun deleteAllWatchHistories() {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.deleteAll()
            LogUtil.d("del_all_watch_hty", "DONE!")
        }
    }

    fun loadAllWatchHistories() =
        DatabaseRepo.WatchHistory.loadAll()
            .catch { e -> e.printStackTrace() }
            .flowOn(Dispatchers.IO)
    private val _modifyHKeyframeFlow = MutableSharedFlow<Boolean>()
    fun removeHKeyframe(videoCode: String, hKeyframe: HKeyframeEntity.Keyframe) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.removeKeyframe(videoCode, hKeyframe)
            LogUtil.d("HKeyframe", "removeHKeyframe:$hKeyframe DONE!")
            _modifyHKeyframeFlow.emit(true)
        }
    }
    fun modifyHKeyframe(
        videoCode: String,
        oldKeyframe: HKeyframeEntity.Keyframe, keyframe: HKeyframeEntity.Keyframe,
    ) {
        viewModelScope.launch {
            DatabaseRepo.HKeyframe.modifyKeyframe(videoCode, oldKeyframe, keyframe)
            LogUtil.d("HKeyframe", "modifyHKeyframe:$keyframe DONE!")
            _modifyHKeyframeFlow.emit(true)
        }
    }
    fun deleteHKeyframes(entity: HKeyframeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.delete(entity)
        }
    }

    fun updateHKeyframes(entity: HKeyframeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.update(entity)
        }
    }
}
