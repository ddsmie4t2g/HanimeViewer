package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CdnRelayInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.ImageRelayInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.ImageRetryInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 图片专用网络栈（封面 / 缩略图 / 图标）。
 *
 * 存在的意义是**让两个 Coil 版本共用同一份配置** —— 这个工程同时引了
 * Coil 2（`io.coil-kt:coil`，[io.github.daisukikaffuchino.han1meviewer.util.HImageMeower]）
 * 和 Coil 3（`io.coil-kt.coil3`，各处的 `AsyncImage`），如果各配一份，
 * 很容易出现「首页封面通了、下载列表封面不通」这类一半好一半坏的问题。
 *
 * 配置要点：
 * - [HProxySelector] / [HProxyAuthenticator]：漏挂代理的表现是「文字能加载、图一张不出」，
 *   用户完全看不出是代理没生效（这条经验来自 [io.github.daisukikaffuchino.han1meviewer.util.HImageMeower]）。
 * - [HDns]：系统 DNS 对本站系域名是**投毒**的，图片也得走同一套解析。
 *
 * ## ⚠️ 拦截器顺序（由外到内 = 添加顺序，动它会改变行为）
 *
 * ```
 * ImageRetryInterceptor（最外） → ImageRelayInterceptor → CdnRelayInterceptor（最内） → 网络
 * ```
 *
 * - [ImageRetryInterceptor]（9.0）：跨洋链路抖一次不至于让这张封面永久停在 `loadfailed`。
 *   放最外层是有意的：重试会重新走一遍「直连还是中转」的判定。
 * - [CdnRelayInterceptor]：`hembed` / `fourhoi` / `phncdn` 走**自建** TLS 中转（自己的
 *   服务器，URL 不外泄给第三方）。放**最内层**，这样它失败时外层的 wsrv.nl 还能接手。
 * - [ImageRelayInterceptor]：`wsrv.nl` 兜底。不依赖任何自有设施，自建中转哪天挂了封面
 *   还不至于全黑。
 *
 * ⚠️⚠️ 26.9.18 把 [ImageRelayInterceptor] 从最内层提到 [CdnRelayInterceptor] **外面**。
 * 原来顺序是 `CdnRelay → ImageRelay`，而 CdnRelay 在「直连和中转都拿不到」时会直接
 * **抛异常**（见 `CdnRelayInterceptor.directThenRelay` 的 `throw cause`），异常在它那一层
 * 就短路了 —— 内层的 wsrv.nl 兜底**根本没有机会执行**（它 `runCatching` 的是自己内层的
 * `chain.proceed`，外层抛的异常它看不见）。同时 `phncdn.com` 当时也不在它的兜底名单里。
 * 两条加起来 = **Pornhub 封面在自建中转下线后完全没有任何兜底**，
 * 表现就是「挂不挂代理都有一批封面 loadfailed」。
 */
object ImageNetworkClient {

    val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // ⭐ 26.9.17：首页一屏要铺十几到几十张封面，而它们的 host 集中在少数几台 CDN 上
            //    （`vdownload.hembed.com` / `fourhoi.com` / `*.phncdn.com`）。
            //    OkHttp 默认每 host 只放 5 个并发，滚动时后面的图只能排队 —— 走代理带宽充裕时
            //    这个默认值就是瓶颈。放宽到 8，只动同 host 并发数，不动总并发。
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 8 })
            .sslSocketFactory(CdnRelay.sslContext.socketFactory, CdnRelay.trustManager)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(HDns())
            .addInterceptor(ImageRetryInterceptor())
            .addInterceptor(ImageRelayInterceptor())
            .addInterceptor(CdnRelayInterceptor())
            .build()
    }
}
