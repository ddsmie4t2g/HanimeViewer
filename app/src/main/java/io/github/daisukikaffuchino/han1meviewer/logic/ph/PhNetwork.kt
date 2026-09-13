package io.github.daisukikaffuchino.han1meviewer.logic.ph

import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.han1meviewer.logic.network.HCookieJar
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CdnRelayInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Pornhub（pornhub.com）站点入口 —— mod 26.6 新增的第三个数据源。
 *
 * ## 它和另外两个数据源最大的不同：**全程走自建 TLS 中转**
 *
 * 实测 2026-09-13（大陆）：`www.pornhub.com` 与 `*.phncdn.com` 都是 **SNI 阻断** ——
 * 用真实 IP 直连也在 TLS 握手阶段被 RST（0.1 s / 0.3 s），普通 SOCKS5/HTTP 代理同样无效
 * （SNI 是明文，墙在「客户端 → 代理」那一段就能读到）。所以这个数据源**没有直连模式**，
 * 所有请求都由 [CdnRelayInterceptor] 改写到自建中转（见 [CdnRelay.mustRelay]）。
 *
 * 好处是解析层完全不用关心这件事：这里照常产出带 `phncdn.com` 的绝对地址，
 * 由网络栈统一改写。缺点也明确 —— **这条数据源依赖那台 VPS 活着**，
 * 且视频要经过两跳（VPS → CDN 实测 17 Mbps，本机 → VPS 约 2.4 Mbps），
 * 所以默认清晰度取 480P 而不是 1080P。
 *
 * ## 列表用 JSON 接口，不用 HTML
 *
 * 站点的列表页有 1.2–1.6 MB HTML，而 `/webmasters/search` 只有约 140 KB 的结构化数据
 * （30 条/页，含时长、观看数、评分、标签、演员、缩略图）。同样是走中转取字节，
 * 差 10 倍的流量与解析成本，所以列表一律走 JSON（见 [apiUrl]）；
 * 只有详情页没有等价的轻量接口，才去抓 HTML（1.6 MB，播放地址在其中）。
 */
object PhNetwork {

    const val BASE_URL = "https://www.pornhub.com/"

    /** 列表 / 搜索 / 首页都用它。 */
    private const val API_PATH = "webmasters/search"

    /** 详情页。播放地址（`mediaDefinitions`）只在这里。 */
    private const val VIEW_PATH = "view_video.php"

    /** 详情页与列表都用的 key 名。 */
    const val VIEWKEY_PARAM = "viewkey"

    // ────────────────────────────────────────────────────────────── 首页栏目

    /**
     * 首页栏目的键。与 [PhParser.homePage] 的取值一一对应，
     * 也是 [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo] 往
     * `HomePage` 各槽位里填数的依据。
     */
    const val SEC_LATEST = "latest"
    const val SEC_POPULAR = "popular"
    const val SEC_TOP_RATED = "top_rated"
    const val SEC_JAPANESE = "japanese"

    /**
     * 栏目 → 检索条件。前三个用 `ordering`，最后一个用标签 ——
     * 实测 `ordering` 只有 `newest` / `mostviewed` / `rating` 真正生效
     * （`longest`、`hotness`、`toprated`、`recentlyfeatured` 都会**静默退回默认排序**，
     * 也就是和 `newest` 返回同一批数据，所以不能用）。
     */
    val HOME_SECTIONS: List<Pair<String, PhQuery>> = listOf(
        SEC_LATEST to PhQuery(ordering = "newest"),
        SEC_POPULAR to PhQuery(ordering = "mostviewed"),
        SEC_TOP_RATED to PhQuery(ordering = "rating"),
        SEC_JAPANESE to PhQuery(tag = "japanese"),
    )

    /** 一次检索的条件。三者可组合，但列表页目前只用其中一种。 */
    data class PhQuery(
        val keyword: String? = null,
        val ordering: String? = null,
        val tag: String? = null,
    )

    /** 每页条数（站点固定 30，用来判断「还有没有下一页」）。 */
    const val PAGE_SIZE = 30

    /**
     * 拼 `/webmasters/search` 的绝对地址。
     *
     * 用 [okhttp3.HttpUrl.Builder] 而不是字符串拼接，是因为 `tags[]` 这个参数名里的方括号
     * 必须编码成 `%5B%5D`（实测服务端两套都不认时才需要调整，编码后的形式是能用的），
     * 手拼很容易在某层被规范化掉。
     *
     * ⚠️ `search` 参数即使为空也要带上：不带 `search` 的 `/webmasters/search` 会 400，
     * 而 `search=`（空值）是能正常返回的 —— 「浏览」就靠这个空值。
     */
    fun apiUrl(page: Int, query: PhQuery = PhQuery()): String {
        val builder = (BASE_URL + API_PATH).toHttpUrl().newBuilder()
        builder.addQueryParameter("search", query.keyword?.trim().orEmpty())
        if (page > 1) builder.addQueryParameter("page", page.toString())
        query.ordering?.takeIf { it.isNotBlank() }
            ?.let { builder.addQueryParameter("ordering", it) }
        query.tag?.takeIf { it.isNotBlank() }
            ?.let { builder.addQueryParameter("tags[]", it) }
        return builder.build().toString()
    }

    /**
     * 详情页地址。
     *
     * 既然站点已经给了绝对地址（JSON 里的 `url` 字段），**原样放行** ——
     * 与 nJAV 那条「别自己拼详情地址」是同一个道理（见 scraper-site-adaptation 坑 17）。
     */
    fun detailUrl(videoCode: String): String {
        val v = videoCode.trim()
        if (v.startsWith("http://", ignoreCase = true) ||
            v.startsWith("https://", ignoreCase = true)
        ) return v
        val builder = (BASE_URL + VIEW_PATH).toHttpUrl().newBuilder()
        builder.addQueryParameter(VIEWKEY_PARAM, v.trimStart('/'))
        return builder.build().toString()
    }

    /** 从各种形态的地址里抠出 `viewkey`；抠不到返回 null。 */
    fun videoIdFrom(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return VIEWKEY.find(raw)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /** `viewkey=` 后面是一段大小写字母 + 数字（实测长度 13 / 15 / 21 都有，别写死长度）。 */
    private val VIEWKEY = Regex("""[?&]viewkey=([A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)

    /**
     * 播放 / 下载请求头。
     *
     * **故意返回空**：Pornhub 的视频 CDN 确实要 `Referer`（实测 `.ts` 分片不带会 404），
     * 但那个 Referer 必须由**中转服务器**来填 —— 分片请求是播放器发出的，
     * 它只知道自己请求的是中转地址，客户端这边无论加什么头都会被换成中转自己的值。
     * 真正生效的机制是 [CdnRelay.refererFor] + 中转 URL 上的 `?ref=`，见那里的注释。
     *
     * 保留这个函数（而不是让调用方直接写 `emptyMap()`）是为了和另外两个数据源保持同一形状，
     * 将来若某个 CDN 真的需要客户端头，改一处即可。
     */
    fun playbackHeadersFor(url: String): Map<String, String> {
        // 只为「这个域名确实属于本数据源」的断言留一个入口；对结果没有影响。
        url.toHttpUrlOrNull() ?: return emptyMap()
        return emptyMap()
    }

    private val dns = HDns()

    /**
     * 专用的 [OkHttpClient]。
     *
     * 三项必须是它自己的：
     *
     * 1. **带 [CdnRelayInterceptor]** —— 这是本数据源能工作的前提（直连必被 RST）。
     * 2. **带中转的自签证书信任链**（[CdnRelay.sslContext] / [CdnRelay.trustManager]）——
     *    否则每个请求都会因证书校验失败而挂掉，且报错看起来像「中转坏了」。
     * 3. **继承用户的代理 / DoH / 自定义 DNS / UA**（[HProxySelector] / [HDns] /
     *    [UserAgentInterceptor]）—— 与 [io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork]
     *    同一套组合，别另起一套。
     *
     * 超时给得比别的数据源宽松：中转是两跳，首字节要等服务器自己去取。
     */
    private val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .cookieJar(HCookieJar())
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(dns)
            .sslSocketFactory(CdnRelay.sslContext.socketFactory, CdnRelay.trustManager)
            .addInterceptor(CdnRelayInterceptor())
            .build()
    }

    val service: PhService by unsafeLazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .build()
            .create(PhService::class.java)
    }
}
