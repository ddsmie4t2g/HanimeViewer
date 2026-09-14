package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CdnRelayInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CloudflareInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.GetchuInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.SpeedLimitInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavPlaybackInterceptor
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Retrofit
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:35
 */
object ServiceCreator {

    private val cache = Cache(
        directory = File(applicationContext.cacheDir, "http_cache"),
        maxSize = 10 * 1024 * 1024
    )

    private val downloadSpeedLimitInterceptor by unsafeLazy {
        SpeedLimitInterceptor(maxSpeed = SettingsRepository.downloadSpeedLimit)
    }

    private val dns = HDns()

    inline fun <reified T> create(baseUrl: String): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(hClient)
        .build()
        .create(T::class.java)

    inline fun <reified T> createGetchu(baseUrl: String): T = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(getchuClient)
        .build()
        .create(T::class.java)

    /**
     * OkHttpClient
     */
    var hClient: OkHttpClient = buildHClient()
        private set

    var downloadClient: OkHttpClient = buildDownloadClient()
        private set

    var getchuClient: OkHttpClient = buildGetchuClient()
        private set

    /**
     * Rebuild OkHttpClient
     */
    fun rebuildOkHttpClient() {
        hClient = buildHClient()
        getchuClient = buildGetchuClient()
    }

    /**
     * getchu（新番预告 / 发售表）专用 client。
     *
     * ⚠️ **必须**挂 [CdnRelay] 的信任链与 [CdnRelayInterceptor]：`www.getchu.com`
     * 从大陆直连必失败（TLS 握手就被打断），唯一可行路径是自建中转
     * （`getchu.com` 在 [CdnRelay.BLOCKED_HOSTS] 里）。
     *
     * 拦截器顺序有讲究：[CdnRelayInterceptor] 放在 [GetchuInterceptor] **之后**。
     * 请求头（UA / Referer / Cookie / Accept）由 GetchuInterceptor 补，
     * CdnRelayInterceptor 只换 URL。顺序反过来的话 URL 已经被换成中转地址，
     * 再按「原始 host」补头就会补错，中转侧也拿不到 getchu 要的 `Referer`。
     */
    private fun buildGetchuClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .sslSocketFactory(CdnRelay.sslContext.socketFactory, CdnRelay.trustManager)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(GetchuInterceptor())
            .addInterceptor(CdnRelayInterceptor())
            .cookieJar(CookieJar.NO_COOKIES)
            // 发售表一页 ~29 张封面全在同一个 host（中转）上，默认「5 并发」要排 6 轮。
            .connectionPool(NetworkTuning.connectionPool)
            .dispatcher(NetworkTuning.dispatcher)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(dns)
            .build()
    }

    /**
     * 下载专用 client。
     *
     * ⚠️ 这里**必须**挂 [HProxySelector]：它以前漏挂了，后果是「应用内其它地方都走代理、
     * 只有下载不走」—— 用户配了代理却下不动任何东西，而且没有任何报错。
     * 别因为「下载要走直连更快」就把它摘掉，用户配代理往往正是因为直连不通。
     */
    private fun buildDownloadClient(): OkHttpClient {
        return OkHttpClient.Builder()
            // 5 s → 15 s：这个值的语义是「建立连接」的上限。走代理时多了一跳
            // （握手 + SOCKS5 认证协商），弱网下 5 s 经常不够 —— 表现为还没开始下
            // 就报连接失败，用户看到的就是「网络连接中断」。与 hClient 对齐成 15 s。
            .connectTimeout(15, TimeUnit.SECONDS)
            // ⚠️ 必须显式给 readTimeout。不给的话是 OkHttp 默认的 10 s，
            // 而它的语义是「两次数据到达之间的最大间隔」：直链下载在 30–40 KB/s 的弱网
            // 出口下，一次网络抖动就可能超过 10 s，于是「明明能下完」被判定断流。
            // 30 s 与 HLS 下载链路（hlsClient）保持一致。
            .readTimeout(30, TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_1_1))
            .sslSocketFactory(CdnRelay.sslContext.socketFactory, CdnRelay.trustManager)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(downloadSpeedLimitInterceptor)
            // 放在限速之后：限速拦的是「读正文」的节奏，中转改写的是 URL，
            // 顺序反过来会让限速把那一次直连失败的重试也算进配额，纯属浪费。
            .addInterceptor(CdnRelayInterceptor())
            .addNetworkInterceptor(NjavPlaybackInterceptor())
            // 下载与 HLS 分片同样落在中转这一个 host 上，共用连接池才能真正复用 socket。
            .connectionPool(NetworkTuning.connectionPool)
            .dispatcher(NetworkTuning.dispatcher)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(dns)
            .build()
    }

    /**
     * Build OkHttpClient
     */
    private fun buildHClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .addInterceptor(CloudflareInterceptor(applicationContext))
            .cache(cache)
            .cookieJar(HCookieJar())
            // 首页一屏 ~30 张封面全走中转（同一个 host），默认 5 并发要排 6 轮。
            .connectionPool(NetworkTuning.connectionPool)
            .dispatcher(NetworkTuning.dispatcher)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(dns)
            .build()
    }

}
