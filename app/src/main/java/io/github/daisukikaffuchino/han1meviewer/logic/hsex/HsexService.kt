package io.github.daisukikaffuchino.han1meviewer.logic.hsex

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/**
 * 好色TV（hsex.tv）的页面接口。
 *
 * 纯 SSR 站点：列表 / 搜索 / 详情所需的字段全在首屏 HTML 里，
 * 所以一个「取 URL 返回 HTML」的方法就够，不必为每个栏目声明 endpoint。
 *
 * `Referer` 是必须的：站点会校验来源，裸请求列表页会拿到空壳。
 * （**视频 CDN 反而不校验** —— 见 [HsexNetwork] 的类注释。）
 */
interface HsexService {

    @Headers(
        "Referer: https://hsex.tv/",
        "Origin: https://hsex.tv",
        "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language: zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7",
    )
    @GET
    suspend fun get(@Url url: String): Response<ResponseBody>
}
