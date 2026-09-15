package io.github.daisukikaffuchino.han1meviewer

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.datastore.DataStoreManager
import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.ImageNetworkClient
import io.github.daisukikaffuchino.han1meviewer.ui.crash.CrashHandler
import io.github.daisukikaffuchino.han1meviewer.util.AnimeShaders
import io.github.daisukikaffuchino.han1meviewer.util.AppLanguageManager
import io.github.daisukikaffuchino.han1meviewer.worker.FollowUpdateWorker
import io.github.daisukikaffuchino.utils.ActivityManager
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.applicationContext as globalApplicationContext
import `is`.xyz.mpv.MPVLib
import okio.Path.Companion.toOkioPath
import java.lang.ref.WeakReference
import java.net.ProxySelector

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 17:32
 */
class HanimeApplication : Application(), Application.ActivityLifecycleCallbacks,
    SingletonImageLoader.Factory {

    companion object {
        const val TAG = "HanimeApplication"

        /**
         * Coil 磁盘缓存目录名。**保持 Coil 默认值**，改动等于让老用户丢掉整份缓存。
         */
        private const val IMAGE_DISK_CACHE_DIR = "image_cache"

        /** 封面磁盘缓存上限。一张封面 50–70 KB，512 MiB 足够放下几万张。 */
        private const val IMAGE_DISK_CACHE_BYTES = 512L * 1024 * 1024

        /** 内存缓存占可用堆的比例。Coil 默认也是 0.25，这里显式写出来免得被误改。 */
        private const val IMAGE_MEMORY_CACHE_PERCENT = 0.25
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        globalApplicationContext = this
    }

    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
        DataStoreManager.initialize(this)
        SettingsRepository.install(DataStoreManager)
        AppLanguageManager.applyStoredLanguage(this)
        registerActivityLifecycleCallbacks(this)
        ProxySelector.setDefault(HProxySelector())
        HProxySelector.rebuildNetwork()
        initNotificationChannel()
        // 【9.0】关注作者新作提醒：排上 12 小时一次的检查（幂等，KEEP）。
        // Worker 自己会看设置，关闭时连网络都不发。
        FollowUpdateWorker.schedule(this)
        MPVLib.create(applicationContext)
        MPVLib.init()

        // SOCKS5 的用户名/密码认证只能通过全局 java.net.Authenticator 提供
        // （那段协商发生在 Socket 建连内部，OkHttp 看不到），必须在任何建连之前装好。
        HProxyAuthenticator.installSocksAuthenticator()

        // 【8.1】预热一次中转探活：这样第一个视频请求失败后，能立刻知道「该不该绕中转」，
        // 不必先等一次探活超时。不阻塞启动，失败也无所谓。
        CdnRelay.warmUp()

        if (AnimeShaders.copyShaderAssets(applicationContext) <= 0) {
            LogUtil.w(TAG, "Shader 复制失败")
        }
        if (AnimeShaders.copyCertAssets(applicationContext) <= 0) {
            LogUtil.w(TAG, "cert 复制失败")
        }
        val selected = SettingsRepository.fakeLauncherIcon
        switchLauncher(selected)
    }

    private fun initNotificationChannel() {
        val nm = NotificationManagerCompat.from(this)

        val hanimeDownloadChannel = NotificationChannelCompat.Builder(
            DOWNLOAD_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("Hanime Download").build()
        nm.createNotificationChannel(hanimeDownloadChannel)

        val appUpdateChannel = NotificationChannelCompat.Builder(
            UPDATE_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_HIGH
        ).setName("App Update").build()
        nm.createNotificationChannel(appUpdateChannel)

        // 关注作者新作提醒（9.0）。⚠️ 用 DEFAULT 而不是 HIGH：
        // 它是「有空来看看」级别的信息，不该像下载/更新那样弹横幅打断用户。
        val followUpdateChannel = NotificationChannelCompat.Builder(
            FOLLOW_UPDATE_NOTIFICATION_CHANNEL,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        ).setName("Followed Artist Updates").build()
        nm.createNotificationChannel(followUpdateChannel)
    }
    fun switchLauncher(alias: String) {
        val pm = packageManager

        val allAliases = listOf(
            "io.github.daisukikaffuchino.han1meviewer.LauncherAliasDefault",
            "io.github.daisukikaffuchino.han1meviewer.LauncherFakeCalc",
            "io.github.daisukikaffuchino.han1meviewer.LauncherFakeCornhub",
            "io.github.daisukikaffuchino.han1meviewer.LauncherFakeXxt"
        )

        allAliases.forEach { a ->
            val state = if (a == alias)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED

            pm.setComponentEnabledSetting(
                ComponentName(this, a),
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    /**
     * Coil 3 的全局 [ImageLoader]。
     *
     * 各处的 `AsyncImage` / `SingletonImageLoader.get(context)` 最终都会走到这里，
     * 所以**只在这一处**换掉 OkHttp 栈，全应用的图片就都带上了
     * [ImageNetworkClient]（代理 + [HDns] + 封面图中转兜底 + 失败重试），
     * 不必去改几十个调用点。
     *
     * ⚠️ 别忘了 Coil **2** 那份（[io.github.daisukikaffuchino.han1meviewer.util.HImageMeower]）
     * 也要用同一个 client —— 这个工程两个版本并存，只配一处会出现
     * 「首页封面通了、下载列表封面不通」这类难查的问题。
     *
     * ## 磁盘缓存（9.0 显式配置）
     *
     * 封面/头像都要**绕一趟美国中转**才拿得到（一张 50–70 KB），所以「加载成功过一次
     * 就长期留在本机」比任何网络优化都值钱：
     *
     * - 第二次进同一个页面变成**纯本地读取**，不会因为线路抖动又出现 `loadfailed`；
     * - 省下的是跨境流量，而不是本地磁盘。
     *
     * 目录名保持 Coil 默认的 `image_cache`（**别改名**）：改了等于让升级上来的用户
     * 丢掉已经攒下的整份缓存，白白重新跨境拉一遍。
     * 上限给到 [IMAGE_DISK_CACHE_BYTES]（512 MiB）—— 单张封面几十 KB，
     * 这个量级足够放下几万张，又不会把用户的存储吃出问题。
     */
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            // 具名传参，且只传 callFactory：
            // OkHttpNetworkFetcherFactory 有 3 个重载，只差后面的默认参数
            // （cacheStrategy / connectivityChecker / ...），写成尾随 lambda 会让
            // 编译器在三者之间产生 Overload resolution ambiguity。
            // 只用具名 callFactory 时，编译器会挑「最少用默认参数」的那个重载。
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { ImageNetworkClient.client })) }
            .diskCache {
                DiskCache.Builder()
                    // ⚠️ Coil 3 的 directory 收的是 okio 的 Path，不是 java.io.File。
                    .directory(context.cacheDir.resolve(IMAGE_DISK_CACHE_DIR).toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, IMAGE_MEMORY_CACHE_PERCENT)
                    .build()
            }
            .build()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityResumed(activity: Activity) {
        ActivityManager.currentActivity = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
