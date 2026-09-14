package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.DESKTOP_USER_AGENT
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.NetworkTuning
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.CdnRelayInterceptor
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.util.concurrent.TimeUnit

internal fun currentGetchuDateCode(): String {
    val now = LocalDate.now()
    return "%04d%02d".format(now.year, now.monthValue)
}

internal fun shiftGetchuMonthCode(code: String, delta: Int): String {
    var year = code.substring(0, 4).toInt()
    var month = code.substring(4, 6).toInt() + delta
    while (month < 1) {
        month += 12
        year -= 1
    }
    while (month > 12) {
        month -= 12
        year += 1
    }
    return "%04d%02d".format(year, month)
}

internal fun getchuDateLabel(code: String): String {
    return "${code.substring(0, 4)}/${code.substring(4, 6).toInt()}"
}

internal fun getchuMonthOptions(centerCode: String): List<String> {
    return (-12..12).map { delta -> shiftGetchuMonthCode(centerCode, delta) }
}

@Composable
internal fun getchuImageRequest(url: String?): ImageRequest {
    val context = LocalContext.current
    return ImageRequest.Builder(context)
        .data(url)
        .build()
}

@Composable
internal fun rememberGetchuImageLoader(): ImageLoader {
    val context = LocalContext.current
    val isInspectionMode = LocalInspectionMode.current
    return remember(context, isInspectionMode) {
        if (isInspectionMode) {
            ImageLoader.Builder(context).build()
        } else {
            createGetchuImageLoader(context)
        }
    }
}

internal fun createGetchuImageLoader(context: Context): ImageLoader {
    // ⚠️ 与 GetchuService 一样必须挂中转：封面图也在 `www.getchu.com` 上，
    // 而那条直连从大陆不通 ⇒ 不挂的话就是「列表出来了、封面全是空白」。
    val imageClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(CdnRelay.sslContext.socketFactory, CdnRelay.trustManager)
        .dns(HDns())
        .proxySelector(HProxySelector())
        .proxyAuthenticator(HProxyAuthenticator.http)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url
            val builder = request.newBuilder()
            if (url.host == "www.getchu.com" && url.encodedPath.startsWith("/brandnew/")) {
                builder
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Referer", "https://www.getchu.com/")
                    .header("Cookie", "getchu_adalt_flag=getchu.com; gc=gc")
            }
            chain.proceed(builder.build())
        }
        // 放在补头之后：先按原始 host 补头，再由它把 URL 换成中转地址。
        .addInterceptor(CdnRelayInterceptor())
        // 一页 ~29 张封面全落在中转这一个 host 上，共用池与调度器才有并发。
        .connectionPool(NetworkTuning.connectionPool)
        .dispatcher(NetworkTuning.dispatcher)
        .build()
    return ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { imageClient }))
        }
        .build()
}
