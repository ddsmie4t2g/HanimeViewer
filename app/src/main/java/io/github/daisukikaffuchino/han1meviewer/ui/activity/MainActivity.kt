package io.github.daisukikaffuchino.han1meviewer.ui.activity

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.daisukikaffuchino.utils.LogUtil
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants.ANIME_URL
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logout
import io.github.daisukikaffuchino.han1meviewer.ui.bridge.VideoPageHost
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.AccountRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.HanimeScreen
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.LoginRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.MyAccountRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.TopLevelBackStack
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.VideoRoute
import io.github.daisukikaffuchino.han1meviewer.ui.screen.main.MainActivityContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.HomePageViewModel
import io.github.daisukikaffuchino.utils.ActivityManager
import io.github.daisukikaffuchino.utils.isX86_64Device
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : BaseActivity() {

    val viewModel by viewModels<HomePageViewModel>()

    val mainBackStack: TopLevelBackStack<HanimeScreen>
        get() = viewModel.mainBackStack
    private var showAuthGuard by mutableStateOf(true)
    private val pendingNavigationRequests = MutableSharedFlow<Intent>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    private var currentVideoHost: VideoPageHost? = null
    private var showSiteSwitchPicker by mutableStateOf(false)
    private var logoutDialogCloseCurrentPage by mutableStateOf<Boolean?>(null)

    companion object {
        const val ACTION_TOGGLE_PLAY = "io.github.daisukikaffuchino.han1meviewer.ACTION_TOGGLE_PLAY"
    }

    private var hasAuthenticated = false
    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LogUtil.i("pipmode", "✅ onReceive called with action: ${intent?.action}")
            when (intent?.action) {
                ACTION_TOGGLE_PLAY -> {
                    LogUtil.i("pipmode", "🎬 ACTION_TOGGLE_PLAY triggered")
                    togglePlayPause()
                }
            }
        }
    }

    private fun initData() {
        setHanimeContent {
            MainActivityContent(
                activity = this,
                viewModel = viewModel,
                pendingNavigationRequests = pendingNavigationRequests,
                showAuthGuard = showAuthGuard,
                // 抽屉头部的账号区点进去的是**自建账号**（三站统筹）；
                // hanime 站点账号改从抽屉「账号」分区里进（26.7.1）。
                onOpenAccount = { mainBackStack.add(MyAccountRoute) },
                showSiteSwitchPicker = showSiteSwitchPicker,
                // 当前站点：让弹层里那一项是选中态，用户一眼知道自己在哪一站。
                currentSiteSource = SettingsRepository.siteSource.value,
                logoutDialogCloseCurrentPage = logoutDialogCloseCurrentPage,
                onLogoutClick = { showLogoutConfirmDialog() },
                onRequireLogin = { openLogin() },
                onSwitchSiteClick = { showSiteSwitchPicker = true },
                onDismissSiteSwitch = { showSiteSwitchPicker = false },
                onSelectSite = { switchSite(SiteSource.fromValue(it)) },
                onDismissLogout = { logoutDialogCloseCurrentPage = null },
                onConfirmLogout = ::confirmLogout,
                onOpenClipboardVideo = ::showVideoDetailFragment,
            )
        }
    }

    override fun beforeSuperOnCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installSplashScreen().apply {
                setKeepOnScreenCondition { !hasAuthenticated }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val useLock = SettingsRepository.current.useLockScreen

        if (useLock && isDeviceSecureCompat(this)) {
            authenticate(
                this,
                onSuccess = {
                    hasAuthenticated = true
                    showAuthGuard = false
                    initData()
                },
                onFailed = {
                    finish()
                }
            )
        } else {
            hasAuthenticated = true
            showAuthGuard = false
            initData()
        }
        pendingNavigationRequests.tryEmit(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNavigationRequests.tryEmit(intent)
    }

    private fun isDeviceSecureCompat(context: Context): Boolean {
        val km = context.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        return km.isDeviceSecure
    }

    private fun authenticate(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailed: () -> Unit
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationFailed() {
                    // 指纹被识别但不匹配（单次）
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // 取消、锁定、连续失败后触发
                    onFailed()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.auth_request))
            .setSubtitle(getString(R.string.unlock_method))
            .setDescription(getString(R.string.unlock_desc))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onStart() {
        super.onStart()
        registerPipReceiver()
    }

    private fun registerPipReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_PLAY)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipActionReceiver, filter, RECEIVER_NOT_EXPORTED)
            LogUtil.i("pipmode", "✅ registerReceiver with RECEIVER_NOT_EXPORTED")
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(pipActionReceiver, filter)
            LogUtil.i("pipmode", "✅ registerReceiver (legacy)")
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(pipActionReceiver)
    }

    override fun onSupportNavigateUp(): Boolean {
        return if (mainBackStack.removeLast()) {
            true
        } else {
            super.onSupportNavigateUp()
        }
    }

    /**
     * 抽屉头部「切换站点」：**直接切到用户点的那一站**（弹层由
     * [MainActivityContent] 里的 `ChoiceDialog` 渲染）。
     *
     * 以前是「三站循环 + 二次确认」：只有两个站点时还凑合，到三个就成了
     * 「点一下 → 确认 → 发现不是想去的那站 → 再点一下 → 确认」，最多按四次。
     * 现在点哪个去哪个，一次到位。
     *
     * 目标 == 当前站点时**什么都不做**（连重启都不做）—— 用户点错了自己那站，
     * 不该被强制重启一次。
     *
     * ⚠️ **必须同时写 `siteSource` 与 `domainName`**：只改域名不写数据源，网络层
     * 仍会按旧站点分流（mod.5 的历史根因）。判据一律读 [SettingsRepository.siteSource]，
     * 不去比 URL 猜。
     */
    private fun switchSite(target: SiteSource) {
        showSiteSwitchPicker = false
        if (SettingsRepository.siteSource == target) return

        // 回 hanime 时用哪个镜像：优先用户之前记下的那个，但必须真的是 hanime 镜像
        // （selectedBaseUrl 有可能是历史遗留值，否则就回不到「里番」了）。
        val comebackSite = SettingsRepository.selectedBaseUrl
            .takeIf { it in ANIME_URL }
            ?: ANIME_URL[0]

        lifecycleScope.launch {
            SettingsRepository.update {
                when (target) {
                    // 回 hanime 里番（含自定义镜像 → 关掉，因为自定义只指向某一个站点）
                    SiteSource.Hanime1 -> it.copy(
                        domainName = comebackSite,
                        selectedBaseUrl = comebackSite,
                        siteSource = SiteSource.Hanime1,
                        useCustomMirrorSite = false,
                    )

                    // nJAV：**保留 selectedBaseUrl** —— 它是「回 hanime 时用哪个镜像」的
                    // 备忘，在 AV 数据源之间来回切不该把它冲掉。
                    SiteSource.Njav -> it.copy(
                        domainName = HanimeConstants.NJAV_URL,
                        selectedBaseUrl = comebackSite,
                        siteSource = SiteSource.Njav,
                        useCustomMirrorSite = false,
                    )

                    // Pornhub：同上保留 selectedBaseUrl。
                    SiteSource.Pornhub -> it.copy(
                        domainName = HanimeConstants.PORN_HUB_URL,
                        selectedBaseUrl = comebackSite,
                        siteSource = SiteSource.Pornhub,
                        useCustomMirrorSite = false,
                    )
                }
            }
            delay(500)
            ActivityManager.restart(killProcess = true)
        }
    }

    fun openLogin() {
        mainBackStack.add(LoginRoute, launchSingleTop = true)
    }

    fun showLogoutConfirmDialog(closeCurrentPageOnConfirm: Boolean = false) {
        logoutDialogCloseCurrentPage = closeCurrentPageOnConfirm
    }

    private fun confirmLogout() {
        val closeCurrentPage = logoutDialogCloseCurrentPage ?: return
        logoutDialogCloseCurrentPage = null
        if (closeCurrentPage) {
            mainBackStack.removeLast()
        }
        logoutWithRefresh()
    }

    fun logoutWithRefresh() {
        lifecycleScope.launch {
            logout()
            viewModel.getHomePage()
        }
    }

    fun showVideoDetailFragment(videoCode: String, fileUri: String? = null) {
        mainBackStack.add(VideoRoute(videoCode, fileUri))
    }

    fun registerCurrentVideoHost(host: VideoPageHost?) {
        currentVideoHost = host
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val currentFragment = currentVideoHost

        val allowPip = SettingsRepository.current.allowPipMode

        LogUtil.i("pipmode", "enter pip mode?\n$currentFragment\nallowpip:$allowPip\n")

        if (currentFragment?.shouldEnterPip() == true && allowPip) {
            LogUtil.i("pipmode", "enter pip mode")
            currentFragment.enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)

        val currentFragment = currentVideoHost

        currentFragment?.onPipModeChanged(isInPictureInPictureMode)
    }

    fun togglePlayPause() {
        currentVideoHost?.togglePlayPause()
    }

    init {
        if (!(BuildConfig.DEBUG && isX86_64Device)) {
            System.loadLibrary("chino")
        }
    }
}
