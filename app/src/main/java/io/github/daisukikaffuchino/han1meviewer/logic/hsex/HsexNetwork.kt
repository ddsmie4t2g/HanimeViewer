package io.github.daisukikaffuchino.han1meviewer.logic.hsex

import io.github.daisukikaffuchino.han1meviewer.logic.network.HCookieJar
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 好色TV（hsex.tv）站点入口 —— mod 26.5 新增的第三个数据源。
 *
 * ## 为什么选它
 *
 * 它是少数**把播放地址明摆在 HTML 里**的站：详情页就一行
 *
 * ```html
 * <source id="video-source" src="…/hls/1240261/index.m3u8" type="application/x-mpegURL" />
 * ```
 *
 * 不像 javmost / 7mmtv 那样把地址塞进第三方 embed（voe.sx / ninjastream / mmvh02…），
 * 每换一家就得再写一套解析器、还得跟着人家改版跑。这里只有一个静态标签，
 * 站点改版的风险面小得多。
 *
 * ## 三条 CDN 线路
 *
 * 站方的 `fastest_cdn.js` 里写死了三条线路，靠**换 URL 的 host** 切换：
 *
 * | 线路 | host |
 * |---|---|
 * | 1（默认） | `cdn.hdcdn.online` |
 * | 2 | `shark.hdcdn.online` |
 * | 3 | `fdc.hdcdn.online` |
 *
 * m3u8 里的分片是**相对路径**（`index0.ts`），播放器按主列表地址解析，
 * 所以换 host 等于整条线路一起换 —— [cdnVariants] 就是靠这一点，
 * 把同一个视频变成三个可选项（界面上的「清晰度」槽位拿来当线路开关用，
 * 因为这个站每个视频只有一档画质，那个槽位本来是空的）。
 *
 * ⚠️ **视频 CDN 不需要 `Referer`**（实测裸请求 200），所以
 * [playbackHeadersFor] 返回空表 —— 和 nJAV 那条 `surrit.com` 必须带防盗链头的
 * 情况刚好相反，别照抄 nJAV 的写法。证书是 Let's Encrypt，OkHttp 直接信任，
 * 不需要往 APK 里钉任何东西。
 */
object HsexNetwork {

    const val BASE_URL = "https://hsex.tv/"
    const val ORIGIN = "https://hsex.tv"
    const val REFERER = "https://hsex.tv/"

    /** 详情页 ID 的两端，形如 `video-1240261.htm`。 */
    private const val VIDEO_PREFIX = "video-"
    private const val VIDEO_SUFFIX = ".htm"

    /**
     * 视频 CDN 的三条线路，顺序即「线路 1/2/3」。
     *
     * ⚠️ 这三个 host 都是**裸 IP**（不是 Cloudflare），所以
     * [HDns] 里给它们钉了内置 IP —— 它们的 DNS 同样可能被投毒。
     */
    val CDN_HOSTS = listOf("cdn.hdcdn.online", "shark.hdcdn.online", "fdc.hdcdn.online")

    /** 列表 / 分类页第 [page] 页；`path` 形如 `list` / `top_list` / `long_list`。 */
    fun listUrl(path: String, page: Int): String =
        BASE_URL + path.trim('/') + "-" + page.coerceAtLeast(1) + VIDEO_SUFFIX

    /**
     * 搜索页第 [page] 页。
     *
     * ⚠️ 分页在**路径**里而不是 query：`search-2.htm?search=xxx`。
     * 第 1 页写成 `search.htm?…` 也能开，但统一走 `search-1.htm` 少一条分支。
     */
    fun searchUrl(keyword: String, page: Int): String {
        val encoded = URLEncoder.encode(keyword.trim(), "UTF-8")
        return BASE_URL + "search-" + page.coerceAtLeast(1) + VIDEO_SUFFIX + "?search=" + encoded
    }

    /**
     * 详情页地址。`videoCode` 是纯数字 id（[videoIdFrom] 的产物）；
     * 传进来一个绝对地址（分享链接 / 历史记录）时原样放行。
     */
    fun detailUrl(videoCode: String): String {
        val value = videoCode.trim()
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) return value
        val id = value.removePrefix(VIDEO_PREFIX).removeSuffix(VIDEO_SUFFIX)
        return BASE_URL + VIDEO_PREFIX + id + VIDEO_SUFFIX
    }

    /** 从 `video-1240261.htm`（或任意含它的字符串）里取出 `1240261`。 */
    fun videoIdFrom(href: String): String? = VIDEO_ID.find(href)?.groupValues?.get(1)

    private val VIDEO_ID = Regex("""video-(\d+)\.htm""", RegexOption.IGNORE_CASE)

    /**
     * 把一个 m3u8 地址扩展成三条线路的候选。
     *
     * 返回「标签 → 地址」，标签直接当清晰度菜单项显示（`线路 1` 等）。
     * 地址里**认不出**已知 CDN host 时返回空表 —— 调用方据此退回「原样一条」，
     * 免得把不相干的 host 也改写成线路。
     */
    fun cdnVariants(m3u8Url: String): List<Pair<String, String>> {
        val httpUrl = m3u8Url.toHttpUrlOrNull() ?: return emptyList()
        if (httpUrl.host.lowercase() !in CDN_HOSTS) return emptyList()
        return CDN_HOSTS.mapIndexed { index, host ->
            "线路 ${index + 1}" to httpUrl.newBuilder().host(host).build().toString()
        }
    }

    /**
     * 好色TV 的视频 CDN **不校验防盗链**，所以这里恒为空表。
     *
     * 保留这个函数是为了让 [io.github.daisukikaffuchino.han1meviewer.worker.HanimeDownloadWorker]
     * 与播放链路有一个统一的取头入口 —— 那边现在是按数据源分流的，
     * 将来这个站要是也上了防盗链，只改这里就够了。
     */
    fun playbackHeadersFor(url: String): Map<String, String> = emptyMap()

    private val dns = HDns()

    private val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .cookieJar(HCookieJar())
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(dns)
            .build()
    }

    val service: HsexService by unsafeLazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .build()
            .create(HsexService::class.java)
    }
}
