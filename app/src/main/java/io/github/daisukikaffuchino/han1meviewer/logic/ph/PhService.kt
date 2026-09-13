package io.github.daisukikaffuchino.han1meviewer.logic.ph

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/**
 * Pornhub（pornhub.com）的取数接口。
 *
 * 站点是纯服务端渲染 + 一个公开的 JSON 接口，所以只需要「给一个绝对地址、拿回文本」这一个方法：
 *
 * | 用途 | 地址 |
 * |---|---|
 * | 首页 / 列表 / 搜索 | `/webmasters/search?search=…&page=…&ordering=…`（**JSON**） |
 * | 详情 | `/view_video.php?viewkey=…`（HTML，播放地址在 `flashvars_*` 里） |
 *
 * ⚠️ 不要给这个接口加 `Referer` / `Origin` 之类的固定头：这条链路的请求最终由
 * [io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CdnRelayInterceptor]
 * 换成自建中转地址，而**上游真正需要的 `Referer` 是服务器侧按中转 URL 上的 `?ref=` 补的**
 * （Pornhub 的 `.ts` 分片不带 Referer 会 404）。客户端这边加了也带不过去，只会误导后来人。
 */
interface PhService {

    @Headers("Accept-Language: en-US,en;q=0.9")
    @GET
    suspend fun get(@Url url: String): Response<ResponseBody>
}
