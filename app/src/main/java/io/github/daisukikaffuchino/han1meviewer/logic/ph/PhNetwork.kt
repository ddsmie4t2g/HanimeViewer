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
     *
     * 数量刻意压到 **10** 个：每个栏目都是一次独立请求（约 140 KB，约 30 条），
     * 全部并发发出。实测并行总吞吐约 1 MB/s，10 个栏目首屏约 1–2 秒，
     * 再多就会把中转那条链路压满。
     */
    const val SEC_LATEST = "latest"
    const val SEC_POPULAR = "popular"
    const val SEC_TOP_RATED = "top_rated"
    const val SEC_WEEKLY = "weekly"
    const val SEC_JAPANESE = "japanese"
    const val SEC_CHINESE = "chinese"
    const val SEC_AMATEUR = "amateur"
    const val SEC_HENTAI = "hentai"
    const val SEC_COSPLAY = "cosplay"
    const val SEC_EXCLUSIVE = "exclusive"

    /**
     * 栏目 → 检索条件。
     *
     * ## 这些取值都是**实测过的**，不是照着站点文案猜的
     *
     * `tags[]` 传一个**不存在的** slug 时接口会返回**空列表**（实测 `tags[]=qqqqqq`
     * → 0 条），所以“能出数据”本身就能证明 slug 存在。更严格的判据是
     * **命中率**：取回的一页里有多大比例的视频真的带这个标签 —— 只有命中率
     * 过半才算这个栏目的内容真的是它标的那样（见下面的批注）。
     *
     * ## `ordering` 只有三个值生效
     *
     * `newest` / `mostviewed` / `rating` 会真的换内容；
     * `longest`、`hotness`、`toprated`、`recentlyfeatured` 会**静默退回默认排序**
     * （返回的就是 `newest` 那批），所以不能用。
     *
     * ## `period` 是有效的，且必须配合 `ordering`
     *
     * `period=weekly` + `ordering=mostviewed` 与不带的相比，一页 30 条**零重合**；
     * `daily` 与 `weekly` 也是零重合（`alltime` 则与不带 `period` 完全相同）。
     * 所以「本週熱門」是真的换了一批内容，不是换个名字。
     */
    val HOME_SECTIONS: List<Pair<String, PhQuery>> = listOf(
        SEC_LATEST to PhQuery(ordering = "newest"),
        SEC_POPULAR to PhQuery(ordering = "mostviewed"),
        SEC_TOP_RATED to PhQuery(ordering = "rating"),
        SEC_WEEKLY to PhQuery(ordering = "mostviewed", period = "weekly"),
        SEC_JAPANESE to PhQuery(tag = "japanese"),
        // 命中率实测：chinese 18/30 偏弱但确实是真标签（中文/华语内容本来就少）；
        // 下面几个都在 20/30 以上，属于站点的强分类。
        SEC_CHINESE to PhQuery(tag = "chinese"),
        SEC_AMATEUR to PhQuery(tag = "verified-amateurs"),
        SEC_HENTAI to PhQuery(tag = "hentai"),
        SEC_COSPLAY to PhQuery(tag = "cosplay"),
        SEC_EXCLUSIVE to PhQuery(tag = "exclusive"),
    )

    /** 一次检索的条件。三者可组合，但列表页目前只用其中一种。 */
    data class PhQuery(
        val keyword: String? = null,
        val ordering: String? = null,
        val tag: String? = null,
        /** 与 [ordering] 搭配的时间窗（`daily` / `weekly` / `monthly` / `alltime`）。 */
        val period: String? = null,
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
        // period 只在有 ordering 时才有意义（单独传它会被忽略）。
        query.period?.takeIf { it.isNotBlank() && !query.ordering.isNullOrBlank() }
            ?.let { builder.addQueryParameter("period", it) }
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
     * ⭐ **Pornhub 必须用桌面 UA —— 这一条是「详情页有标签/观看数/演员」的前提。**
     *
     * 站点按 UA 发**两套完全不同的 DOM**。全应用默认发的是移动 UA
     * （见 [io.github.daisukikaffuchino.han1meviewer.USER_AGENT]），实测拿到的详情页里：
     *
     * | 关键节点 | 移动 UA | 桌面 UA |
     * |---|---|---|
     * | `div.video-detailed-info` | **0 个** | 1 个 |
     * | `div.tagsWrapper` | **0 个** | 1 个 |
     * | `div.ratingInfo`（观看数） | **0 个** | 1 个 |
     * | `div.pornstarsWrapper` | **0 个** | 有 |
     * | 页面体积 | 1.07 MB | 1.55 MB |
     *
     * 也就是说移动版详情页**整块「简介 + 标签 + 演员」都不存在**（不是选择器变了，
     * 是根本没有这些区块）。26.6 发布版正是栽在这里：解析器全对，但一个字段也拿不到，
     * 表现是「详情页没有作者、没有标签、没有观看数」。
     *
     * 代价是每次详情多约 0.5 MB，只走一次详情页，可以接受。
     *
     * ⚠️ 必须用 [okhttp3.Request.Builder.header]（**替换**）而不是 `addHeader`（追加）：
     * [UserAgentInterceptor] 已经用 `addHeader` 塞过一个 UA 了，追加会得到两个
     * `User-Agent` 头，服务端取哪个不确定。
     */
    private object DesktopUserAgentInterceptor : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val request = chain.request().newBuilder()
                .header("User-Agent", io.github.daisukikaffuchino.han1meviewer.DESKTOP_USER_AGENT)
                .build()
            return chain.proceed(request)
        }
    }

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
            // ⚠️ 必须排在 UserAgentInterceptor **之后** —— 它负责把移动 UA 换成桌面 UA，
            //    顺序反了就等于没换（见 DesktopUserAgentInterceptor 的注释）。
            .addInterceptor(DesktopUserAgentInterceptor)
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
