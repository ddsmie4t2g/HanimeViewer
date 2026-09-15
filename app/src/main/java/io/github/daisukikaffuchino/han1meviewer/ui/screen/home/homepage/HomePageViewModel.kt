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
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.logout
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.AppViewModel
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.HanimeScreen
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.HomeRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.TopLevelBackStack
import io.github.daisukikaffuchino.han1meviewer.worker.AppUpdateWorker
import io.github.daisukikaffuchino.han1meviewer.worker.AppUpdateWorkState
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

    private var homePageJob: Job? = null
    private var initializationJob: Job? = null
    private var updateDownloadJob: Job? = null

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
