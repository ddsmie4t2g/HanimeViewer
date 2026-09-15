package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.han1meviewer.HorizontalCardCountConfig
import io.github.daisukikaffuchino.han1meviewer.SearchGridColumnsConfig
import io.github.daisukikaffuchino.han1meviewer.logic.model.AppLanguage
import io.github.daisukikaffuchino.han1meviewer.logic.model.AppSettings
import io.github.daisukikaffuchino.han1meviewer.logic.model.DisplayDensity
import io.github.daisukikaffuchino.han1meviewer.logic.model.PaletteStyle
import io.github.daisukikaffuchino.han1meviewer.logic.model.PlayerKernel
import io.github.daisukikaffuchino.han1meviewer.logic.model.SettingsStore
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.logic.model.ThemeAccent
import io.github.daisukikaffuchino.han1meviewer.logic.model.ThemeMode
import io.github.daisukikaffuchino.han1meviewer.logic.model.VideoLandscapeLayoutStyle
import io.github.daisukikaffuchino.han1meviewer.logic.model.DOWNLOAD_SPEED_BYTES
import io.github.daisukikaffuchino.han1meviewer.logic.model.MAX_DOWNLOAD_SEGMENTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.net.URI

object SettingsRepository : SettingsStore {
    private lateinit var store: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun install(store: SettingsStore) {
        check(!::store.isInitialized) { "SettingsRepository is already installed" }
        this.store = store
    }

    override val settings: StateFlow<AppSettings> get() = store.settings
    override suspend fun update(transform: (AppSettings) -> AppSettings) = store.update(transform)
    val current: AppSettings get() = settings.value

    val loginStateFlow by lazy { settings.map { it.isAlreadyLogin }.stateIn(scope, SharingStarted.Eagerly, current.isAlreadyLogin) }
    val checkInEnabledFlow by lazy { settings.map { it.checkInEnabled }.stateIn(scope, SharingStarted.Eagerly, current.checkInEnabled) }

    /** 「关注作者新作提醒」方式（9.0）。取值见 `ArtistUpdateChecker.MODE_*`。 */
    val followUpdateAlertFlow by lazy {
        settings.map { it.followUpdateAlert }.stateIn(scope, SharingStarted.Eagerly, current.followUpdateAlert)
    }

    /**
     * ⭐ 详情页的「喜欢 / 我的清单」状态该从哪读：**本机库** 还是 hanime 服务端。
     *
     * 判据不是「有没有登录 hanime」，而是**这部片子有没有 hanime 服务端存储可用**：
     * - 当前数据源是 Pornhub / nJAV → 片子根本不在 hanime 上，服务端存储不存在 ⇒ 本机库；
     * - hanime 数据源但未登录 → 同理 ⇒ 本机库。
     *
     * 只有「hanime 数据源 + 已登录」才读服务端。以前只判登录态，于是在 Pornhub 下
     * 登录了 hanime 就会去 hanime 服务端收藏一个它不认识的 videoCode（白等几秒然后失败）。
     */
    val useLocalVideoStateFlow by lazy {
        settings.map { !it.isAlreadyLogin || it.siteSource.isAvSite }
            .distinctUntilChanged()
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                !current.isAlreadyLogin || current.siteSource.isAvSite,
            )
    }

    /** [useLocalVideoStateFlow] 的同步版本（点击那一瞬间做判断用，避免读到上一帧的值）。 */
    val useLocalVideoState get() = !current.isAlreadyLogin || current.siteSource.isAvSite

    val isAlreadyLogin get() = current.isAlreadyLogin
    val localListNoticeDismissed get() = current.localListNoticeDismissed
    val usageNoticeAccepted get() = current.usageNoticeAccepted
    val usageSourceVerified get() = current.usageSourceVerified
    val usageSourcePending get() = current.usageSourcePending
    val savedUserId get() = current.savedUserId
    val cloudFlareCookieHost get() = current.cloudFlareCookieHost.lowercase()
    val switchPlayerKernel get() = current.playerKernel.value
    val enableGoogleCast get() = current.enableGoogleCast
    val showBottomProgress get() = current.showBottomProgress
    val playerSpeed get() = current.playerSpeed
    val slideSensitivity get() = current.slideSensitivity
    val longPressSpeedTime get() = current.longPressSpeedTime
    val videoLanguage get() = current.videoLanguage
    val videoQuality get() = current.videoQuality
    val showPlayedIndicator get() = current.showPlayedIndicator
    val isCheckInEnabled get() = current.checkInEnabled

    /** 「关注作者新作提醒」方式（9.0）的同步读取版本。 */
    val followUpdateAlert get() = current.followUpdateAlert
    val fakeLauncherIcon get() = current.fakeLauncherIcon
    /**
     * 当前站点根地址。自定义镜像优先。
     *
     * ⚠️ 最后那道 [`sanitizeDomain`] 不是多余的：`domainName` 是**持久化**的，
     * mod 7.0 移除了 javchu.com，老用户的设置里还留着 `https://javchu.com/`。
     * 不做兜底的话，升级后 baseUrl 会指向一个已经不存在的站，表现为首页/详情全线
     * 加载失败，而设置页里还显示着一个列表里根本没有的域名。
     */
    val baseUrl: String get() {
        if (current.useCustomMirrorSite && current.customMirrorSite.isNotBlank()) {
            val value = if (current.appendCustomMirrorPath) current.customMirrorSite else rootUrl(current.customMirrorSite)
            return value.withTrailingSlash()
        }
        return sanitizeDomain(current.domainName)
    }
    val homeUrl get() = if (current.useCustomMirrorSite && current.customMirrorSite.isNotBlank()) current.customMirrorSite else baseUrl
    /**
     * ⭐ **hanime 站点根地址 —— 与当前数据源无关**。
     *
     * 和 [baseUrl] 的区别就是「切到 Pornhub / nJAV 之后会怎样」：[baseUrl] 会跟着数据源
     * 变成 `pornhub.com` / `njavtv.com`，而 hanime 的登录页、服务端订阅、我的清单这些
     * **只存在于 hanime 的功能**，地址绝不能跟着变。
     *
     * 之前它们都读 [baseUrl]，于是在 Pornhub / nJAV 下点「登录 hanime 账号」会去加载
     * `https://www.pornhub.com/login` —— 表现为「登录出错 / 加载失败请重试」（用户 2026-09-14 报的）。
     *
     * 取值规则：
     * 1. 当前就在 hanime → 直接用 [baseUrl]（含自定义镜像）；
     * 2. 当前在 AV 站点 → 用 [AppSettings.selectedBaseUrl]，它是切站时特意留下的
     *    「下次回 hanime 用哪个镜像」备忘（见 `MainActivity.switchSite`）；
     * 3. 备忘也不是 hanime 镜像（历史遗留值）→ 退回默认镜像 `hanime1.com`。
     */
    val hanimeBaseUrl: String get() {
        if (!current.siteSource.isAvSite) return baseUrl
        val remembered = current.selectedBaseUrl.trim().trimEnd('/')
        return HanimeConstants.HANIME_URL.firstOrNull { it.trimEnd('/') == remembered }
            ?: HanimeConstants.HANIME_URL[0]
    }
    val useCustomMirrorSite get() = current.useCustomMirrorSite
    /** 当前数据源（hanime1.me / nJAV / Pornhub）。 */
    val siteSource: SiteSource get() = current.siteSource
    /** 便捷判断：当前是否走 nJAV 数据源。 */
    val isNjavSite get() = current.siteSource.isNjav
    /** 便捷判断：当前是否走 Pornhub（pornhub.com）数据源。 */
    val isPornhubSite get() = current.siteSource.isPornhub
    /**
     * 当前是否是非 hanime 的「AV 型」站点（nJAV 或 Pornhub）。
     *
     * 首页栏目名、筛选条件这些**站点无关**的界面文案按它切换，
     * 具体走哪一家再由各自的网络层分流。
     */
    val isAvSite get() = current.siteSource.isAvSite
    val customMirrorSite get() = current.customMirrorSite
    val appendCustomMirrorPath get() = current.appendCustomMirrorPath
    /** 用户自建镜像池（JSON）。内置镜像不在这里，见 [io.github.daisukikaffuchino.han1meviewer.logic.network.MirrorStore]。 */
    val extraMirrorsJson get() = current.extraMirrorsJson
    /** 置顶搜索词（JSON）。 */
    val pinnedSearchesJson get() = current.pinnedSearchesJson
    /** 本地关注作者（JSON）。见 [FollowedArtistStore]。 */
    val followedArtistsJson get() = current.followedArtistsJson
    /** 自建账号的本地状态（JSON）。见 [io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository]。 */
    val accountJson get() = current.accountJson
    /** 是否按影片记住倍速与画质。 */
    val rememberPerVideoPlayback get() = current.rememberPerVideoPlayback
    /** 按影片的播放记忆（JSON）。 */
    val perVideoPlaybackJson get() = current.perVideoPlaybackJson
    val selectedBaseUrl get() = current.selectedBaseUrl
    val useBuiltInHosts get() = current.useBuiltInHosts
    val customHostsData get() = current.customHostsData
    val useDoH get() = current.useDoH
    val dohPreset get() = current.dohPreset
    val dohCustomUrl get() = current.dohCustomUrl
    val dohBootstrapIps get() = current.dohBootstrapIps
    val dohTimeoutSeconds get() = current.dohTimeoutSeconds
    val whenCountdownRemind get() = current.whenCountdownRemindSeconds * 1_000
    val showCommentWhenCountdown get() = current.showCommentWhenCountdown
    val hKeyframesEnable get() = current.hKeyframesEnable
    val sharedHKeyframesEnable get() = current.sharedHKeyframesEnable
    val sharedHKeyframesUseFirst get() = current.sharedHKeyframesUseFirst
    val proxyType get() = current.proxyType.id
    val proxyIp get() = current.proxyIp
    val proxyPort get() = current.proxyPort
    val proxyUsername get() = current.proxyUsername
    val proxyPassword get() = current.proxyPassword
    val downloadCountLimit get() = current.downloadCountLimit
    /** 单个文件下载的连接数（分片并行）。见 [AppSettings.downloadSegments]。 */
    val downloadSegments get() = current.downloadSegments
    val collapseDownloadedGroup get() = current.collapseDownloadedGroup
    val isUsePrivateStorage get() = current.usePrivateStorage
    val safDownloadPath get() = current.safDownloadPath
    val useDarkMode get() = current.themeMode.value
    val useDynamicColor get() = current.useDynamicColor
    val allowResumePlayback get() = current.allowResumePlayback
    val searchArtistIgnoreVideoType get() = current.searchArtistIgnoreVideoType
    val disableMobileDataWarning get() = current.disableMobileDataWarning
    val disablePredictiveBack get() = current.disablePredictiveBack
    val tabletMode get() = current.tabletMode
    val videoLandscapeLayoutStyle get() = current.videoLandscapeLayoutStyle
    val hapticFeedbackEnabled get() = current.hapticFeedbackEnabled
    val funLoadingHints get() = current.funLoadingHints
    val secureMode get() = current.secureMode
    val mpvProfile get() = current.mpvProfile
    val enableGPUNextRenderer get() = current.enableGpuNextRenderer
    val mpvInterpolation get() = current.mpvInterpolation
    val mpvDeband get() = current.mpvDeband
    val mpvFramedrop get() = current.mpvFramedrop
    val mpvHwdec get() = current.mpvHwdec
    val mpvCacheSecs get() = current.mpvCacheSecs
    val mpvTlsVerify get() = current.mpvTlsVerify
    val mpvNetworkTimeout get() = current.mpvNetworkTimeout
    val customMpvParams get() = current.customMpvParams
    val downloadSpeedLimit get() = DOWNLOAD_SPEED_BYTES[current.downloadSpeedLimitIndex]
    val searchGridColumnsConfig get() = SearchGridColumnsConfig(current.searchGridColumnsCompact, current.searchGridColumnsMedium, current.searchGridColumnsExpanded, current.searchGridColumnsLarge)
    val horizontalCardCountConfig get() = HorizontalCardCountConfig(current.horizontalCardCountNarrow, current.horizontalCardCountCompact, current.horizontalCardCountMedium, current.horizontalCardCountExpanded)
    val subscriptionArtistRows get() = current.subscriptionArtistRows
    val alwaysShowUpdateCard get() = current.alwaysShowUpdateCard
    val displayDensity get() = current.displayDensity
    /**
     * 是否允许封面图在直连失败时走第三方中转。
     *
     * 读取处是 [io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.ImageRelayInterceptor]，
     * 它在每次图片请求时读取，所以**改设置立即生效，不用重启**。
     */
    val allowImageRelay get() = current.allowImageRelay
    /**
     * 是否允许被封 CDN（hanime 视频/封面、nJAV 封面）走自建 TLS 中转。
     *
     * 读取处是 [io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CdnRelayInterceptor]，
     * 每次请求都读，所以改设置立即生效、不用重启。
     */
    val allowCdnRelay get() = current.allowCdnRelay
    /**
     * 用户自建的中转节点（JSON）。内置节点不在这里，见
     * [io.github.daisukikaffuchino.han1meviewer.logic.network.RelayNodeStore]。
     */
    val relayNodesJson get() = current.relayNodesJson
    /** 手动指定的中转节点 id；空 = 自动优选。 */
    val activeRelayNodeId get() = current.activeRelayNodeId
    val autoSelectRelayNode get() = current.autoSelectRelayNode

    suspend fun setRelayNodesJson(value: String) = update { it.copy(relayNodesJson = value) }
    /** 本地关注列表（JSON）。只有 Pornhub / nJAV 这类没有订阅接口的站点会用到。 */
    suspend fun setFollowedArtistsJson(value: String) = update { it.copy(followedArtistsJson = value) }

    /**
     * nJAV 女优索引缓存（见 `logic/njav/NjavActressCache`）。
     *
     * ⚠️ 读写都包了一层「没装 store 就当空」——`SettingsRepository.store` 是 `lateinit`，
     * 在 **JVM 单测**里没装过，直接读会抛 `UninitializedPropertyAccessException`。
     */
    val njavActressCacheJson get() = runCatching { current.njavActressCacheJson }.getOrDefault("")

    suspend fun setNjavActressCacheJson(value: String) =
        runCatching { update { it.copy(njavActressCacheJson = value) } }.getOrNull()

    /** 自建账号本地状态（JSON）：token / 用户名 / 云端 revision / 上次同步时间。 */
    suspend fun setAccountJson(value: String) = update { it.copy(accountJson = value) }
    suspend fun setActiveRelayNodeId(value: String) = update { it.copy(activeRelayNodeId = value) }
    suspend fun setAutoSelectRelayNode(value: Boolean) = update { it.copy(autoSelectRelayNode = value) }

    suspend fun setLoginState(value: Boolean) = update { it.copy(isAlreadyLogin = value) }
    suspend fun dismissLocalListNotice() = update { it.copy(localListNoticeDismissed = true) }
    suspend fun setCloudFlareCookie(value: String, host: String = current.cloudFlareCookieHost) = update { it.copy(cloudFlareCookie = value, cloudFlareCookieHost = host.lowercase()) }
    suspend fun setSavedUserId(value: String) = update { it.copy(savedUserId = value) }
    suspend fun setUsageNoticeAccepted(value: Boolean) = update { it.copy(usageNoticeAccepted = value) }
    suspend fun setUsageSourcePending(value: Boolean) = update { it.copy(usageSourcePending = value) }
    suspend fun setLanguage(value: AppLanguage) = update { it.copy(appLanguage = value) }
    suspend fun setThemeMode(value: ThemeMode) = update { it.copy(themeMode = value) }
    suspend fun setDynamicColor(value: Boolean) = update { it.copy(useDynamicColor = value) }
    suspend fun setThemeAccent(value: ThemeAccent) = update { it.copy(themeAccent = value) }
    suspend fun setPaletteStyle(value: PaletteStyle) = update { it.copy(paletteStyle = value) }
    suspend fun setLauncherIcon(value: String) = update { it.copy(fakeLauncherIcon = value) }
    suspend fun setHapticFeedback(value: Boolean) = update { it.copy(hapticFeedbackEnabled = value) }
    suspend fun setCheckInEnabled(value: Boolean) = update { it.copy(checkInEnabled = value) }

    /** 「关注作者新作提醒」方式（9.0）。取值收敛到 0..2，见 `ArtistUpdateChecker.MODE_*`。 */
    suspend fun setFollowUpdateAlert(value: Int) =
        update { it.copy(followUpdateAlert = value.coerceIn(0, 2)) }
    suspend fun setUsePrivateStorage(value: Boolean) = update { it.copy(usePrivateStorage = value) }
    suspend fun setDownloadStorage(usePrivate: Boolean, path: String?) = update { it.copy(usePrivateStorage = usePrivate, safDownloadPath = path) }
    suspend fun setDownloadCountLimit(value: Int) = update { it.copy(downloadCountLimit = value) }
    suspend fun setDownloadSegments(value: Int) =
        update { it.copy(downloadSegments = value.coerceIn(1, MAX_DOWNLOAD_SEGMENTS)) }
    suspend fun setDownloadSpeedLimitIndex(value: Int) = update { it.copy(downloadSpeedLimitIndex = value.coerceIn(DOWNLOAD_SPEED_BYTES.indices)) }
    suspend fun setSlideSensitivity(value: Int) = update { it.copy(slideSensitivity = value.coerceIn(1, 7)) }
    suspend fun setSubscriptionArtistRows(value: Int) = update { it.copy(subscriptionArtistRows = value.coerceIn(1, 3)) }
    suspend fun setHomeCategories(order: List<String>, hidden: Set<String>) = update { it.copy(homeCategoryOrder = order, hiddenHomeCategoryKeys = hidden) }
    suspend fun setCachedUpdateJson(value: String?) = update { it.copy(cachedUpdateJson = value) }
    suspend fun setIgnoredVersionCode(value: Int) = update { it.copy(ignoredVersionCode = value) }
    suspend fun setAlwaysShowUpdateCard(value: Boolean) = update { it.copy(alwaysShowUpdateCard = value) }
    suspend fun setDisplayDensity(value: DisplayDensity) = update { it.copy(displayDensity = value) }
    suspend fun setVideoLandscapeLayoutStyle(value: VideoLandscapeLayoutStyle) =
        update { it.copy(videoLandscapeLayoutStyle = value) }

    private fun String.withTrailingSlash() = if (endsWith('/')) this else "$this/"
    private fun rootUrl(value: String) = runCatching { URI(value).let { "${it.scheme}://${it.rawAuthority}" } }.getOrDefault(value)

    /**
     * 校准持久化的域名：不在已知站点集合里就退回默认镜像。
     *
     * 目前唯一会命中的场景是老用户设置里残留的 `javchu.com`（mod 7.0 已下线整站），
     * 以及用户手改过的非法值。**只做只读兜底，不改写存储** —— 这样即使用户哪天
     * 想切回去（比如我们自己又加回来），原值还在。
     */
    private fun sanitizeDomain(value: String): String {
        if (value.isBlank()) return HanimeConstants.HANIME_URL[0]
        if ((value.trimEnd('/') + "/") in HanimeConstants.ALL_URLS) return value
        return HanimeConstants.HANIME_URL[0]
    }
}
