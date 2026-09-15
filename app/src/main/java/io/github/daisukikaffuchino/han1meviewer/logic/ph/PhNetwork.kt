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

    /**
     * 站点自己的「推荐」页 —— 26.9.5 新增，与 [HOME_SECTIONS] 那 10 个栏目不是一回事。
     *
     * ## 为什么它单独一格
     *
     * `/webmasters/search` 只能表达「检索条件」（关键词 / 排序 / 标签），而
     * 「推荐」是站点推荐引擎的输出 —— 不是又一个排序。实测（2026-09-15）：
     * 它自己的第 1 页与第 2 页零重合、页码条能翻到 18+ 页，响应头里带
     * `x-dd-experiments: {video_recommendation: …}`，首页导航里也挂着
     * `Recommended Videos → /recommended`。用户的原话是
     * 「现在只有最新/最多观看/本周热门这些都不带变的，加点它自己的首页推荐」。
     *
     * ## 代价（务必知情）
     *
     * 它**没有 JSON 版本**，只有整页 HTML：约 **1 MB**（对比：一个 JSON 栏目约 140 KB），
     * 21 条/页，卡片在文档第 433–676 KB 处，页码条在 683 KB 处。
     * 每开一次首页就多这么一趟 —— 作者页也是这个量级（1.2 MB/页），属于既有做法。
     *
     * ⚠️ **越界的页码返回 404**（实测 `?page=999`），不是空页。调用方必须把它当成
     * 「到底了」（见 [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo.phListFlow]），
     * 否则滚到底会弹错误 —— 与 26.9.4 那个「末页 404」是同一类坑。
     */
    private const val RECOMMENDED_PATH = "recommended"

    /**
     * 「推荐」页地址。`page = 1` 时不带参数（站点自己的形式就是 `/recommended`）。
     *
     * 与 [apiUrl] 一样交给 [okhttp3.HttpUrl.Builder] 拼，别手拼字符串。
     */
    fun recommendedUrl(page: Int): String {
        val builder = (BASE_URL + RECOMMENDED_PATH).toHttpUrl().newBuilder()
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return builder.build().toString()
    }

    /**
     * 这个地址是不是「推荐」页 —— 用来决定列表页该用 JSON 解析还是 HTML 解析
     * （两条路的 [io.github.daisukikaffuchino.han1meviewer.logic.ph.PhParser] 入口不同）。
     */
    fun isRecommendedUrl(url: String): Boolean {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return false
        return path.trim('/') == RECOMMENDED_PATH
    }

    /**
     * 这个地址是不是**站点主页** —— 与 [isRecommendedUrl] 同一个用途：
     * 决定列表页该用哪个解析器（主页要用 [PhParser.homepageHotList]）。
     *
     * ⭐ 26.9.8 新增这条判据，是为了让「热门色情视频」那一行也能有列表页：
     * 首页大轮播的「更多」必须**跟着当前批次的数据源**走（原来是写死的「推荐」，
     * 所以轮播上放着主页热门、点「更多」却进推荐列表 —— 用户报的「点进去更多还是上一批」）。
     *
     * ⚠️ 用 `encodedPath.trim('/').isEmpty()` 而不是拿字符串比 BASE_URL：
     * 站点会发 `https://www.pornhub.com`（无尾斜杠）与 `.../`（有）两种形态，
     * 字符串相等会漏掉一种。
     */
    fun isHomepageUrl(url: String): Boolean {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: return false
        return path.trim('/').isEmpty()
    }

    /**
     * 站点**主页** —— 「热门色情视频」那一节的正文（`ul#singleFeedSection`）只在它上面。
     *
     * ## 为什么必须单独一趟（26.9.7）
     *
     * 用户截图里那个带红点的「热门色情视频」（英文站叫 `Hot Porn Videos`），
     * 正文是主页里的 `ul#singleFeedSection`：实测 **61 条**卡片（另有 1 张广告卡）。
     *
     * ⚠️⚠️ **它只存在于主页 HTML**（~1.25 MB）：
     * - `/video`、`/video?o=ht`、`/video?o=tr`、`/video?o=mv` 上**都没有**这个容器，
     *   它们的 h1 分别是「最新精选色情片」/「Hottest … Seychelles」/「本月评价最好的」…
     *   ⇒ 不是「同一个列表换个门」。
     * - `/webmasters/search` 试了 7 种 `ordering`（`hot` / `hottest` / `trending` /
     *   `featured` / `mostviewed` / `rating` / `newest`），与主页那批 **全部 0 重合**
     *   ⇒ **没有 JSON 等价接口**。（`ordering` 只有 newest/mostviewed/rating 真生效，
     *   其余会静默退回默认排序 —— 见 [HOME_SECTIONS] 的注释。）
     * - 主页也**没有**「加载更多 / 无限滚动」接口（`/front/` 下面只有登录那几个）。
     *   ⇒ 这 61 条是**固定的一批，不能翻页**；这与「推荐」能翻 18+ 页不同。
     *
     * ⚠️ 别把域名换成 `cn.pornhub.com`：内容一样，但那样要在中转白名单里多挂一个
     * 域名（见 [CdnRelay.mustRelay]）。应用里所有请求都走 [BASE_URL]，
     * 界面上的栏目名是我们自己的字符串，与站点用哪种语言无关。
     */
    fun homeUrl(): String = BASE_URL

    /**
     * embed 页。**播放地址的保底来源** —— 只有 48 KB、且签名形态稳定可取，
     * 但只有 480P 一档。详见 [embedUrl]。
     */
    private const val EMBED_PATH = "embed/"

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
     *
     * ⚠️ 26.9.5 加的「推荐」**不在这个表里**：它不是一个检索条件（见 [recommendedUrl]），
     * 而且比这里每一个都重（约 1 MB 的整页 HTML），所以单独一趟、晚一档发出。
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

    /**
     * ⭐ **作者页（作品列表）地址 —— 修「点作者进去全是别人的视频」。**
     *
     * 只对 `/pornstar/<slug>` 与 `/model/<slug>` 有效（这两类站点真的维护了可分页的作品列表）；
     * 站点上还有 `/users/<name>`、`/channels/<name>` 之类的上传者主页，实测它们的
     * `/videos` 是**游客受限**的（缩略图链接被换成 `triggerGatewayModal` 的登录引导），
     * 拿不到可靠列表 —— 这时返回 null，由调用方退回「按名字搜索」。
     *
     * 为什么不能只靠按名字搜索：`/webmasters/search` 没有「按作者筛」的能力，
     * `stars[]=` 也是模糊匹配（见 [PhParser.artistPage] 的注释），
     * 搜出来的结果里会混进同名的别人。
     */
    fun artistVideosUrl(artistUrl: String, page: Int): String? {
        val path = artistPath(artistUrl) ?: return null
        val builder = (BASE_URL.trimEnd('/') + path + "/videos").toHttpUrl().newBuilder()
        if (page > 1) builder.addQueryParameter("page", page.toString())
        return builder.build().toString()
    }

    /**
     * 把作者地址规整成站内路径，只认 `/pornstar/` 与 `/model/`（其余返回 null）。
     *
     * 详情页给的是**相对地址**（`/pornstar/tru-kait`），而本地关注表里存的可能是
     * 带域名的绝对地址（用户手填或跨站跳转过），两种都要能吃下。
     */
    fun artistPath(artistUrl: String): String? {
        val raw = artistUrl.trim()
        if (raw.isEmpty()) return null
        val path = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw.toHttpUrlOrNull()?.encodedPath ?: return null
        } else {
            raw.substringBefore('?').substringBefore('#')
        }
        val normalized = "/" + path.trim('/')
        return normalized.takeIf {
            it.startsWith("/pornstar/") || it.startsWith("/model/")
        }
    }

    /**
     * ⭐ **embed 页地址 —— 播放地址的「保底来源」。**
     *
     * 详情页给的播放地址有两种签名形态，其中 `?h=…&e=…&f=1` 那种**必然 410**
     * （详见 [PhParser.PhMedia.hasTimeWindow]），而且站点是**随机**发哪种的：
     * 实测同一分钟连抓 8 次，可取 2–5 次不等；遇上不可取的那次，
     * 表现就是「视频明明在，一播就 410」。
     *
     * 这一页（`/embed/<viewkey>`）实测 **10/10 次都是可取形态**，而且只有 **48 KB**
     * （详情页 1.5 MB），代价是**只给 480P 一档**（详情页有 240/480/720/1080）。
     * 所以顺序是「详情页优先、不可取才退到这里」，见 [NetworkRepo.phVideoFlow]。
     */
    fun embedUrl(videoCode: String): String {
        val id = videoIdFrom(videoCode)
            ?: videoCode.trim().trimStart('/').substringAfterLast('/').substringBefore('?')
        return BASE_URL + EMBED_PATH + id
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
