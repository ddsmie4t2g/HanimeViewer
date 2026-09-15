package io.github.daisukikaffuchino.han1meviewer.ui.screen

import io.github.daisukikaffuchino.utils.LogUtil
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.theme.SpacingLarge
import io.github.daisukikaffuchino.han1meviewer.ui.theme.SpacingNormal
import io.github.daisukikaffuchino.han1meviewer.ui.theme.VideoNormalCardMinWidth
import kotlinx.coroutines.delay

/**
 * 会自动重试的封面 / 头像。
 *
 * ## 9.0 修了什么（老实现的重试其实是**坏的**）
 *
 * 老实现失败后把 URL 改成 `"$model?retry=$n"` 再请求一次。可是本站系封面 URL
 * **自带签名查询串** —— `hembed` 的封面长这样：
 * `https://vdownload.hembed.com/.../xxx.jpg?secure=AbCd==,1699999999`。
 * 再拼一个 `?` 得到的是 `...?secure=AbCd==,1699999999?retry=1` ——
 * **非法 URL**，于是「重试」每次都是必失败，用户在界面上看到的就是
 * 「一部分封面 / 头像显示 loadfailed」，而它们其实再取一次就能出来。
 *
 * ## 正确的重试方式
 *
 * Coil 会把**失败结果也写进内存缓存**，所以「同一个 key 再请求一次」会直接命中
 * 上次那个失败结论、根本不会碰网络。因此重试要做的是**换掉内存缓存的 key**，
 * 而不是动 URL：
 *
 * - 首次尝试 → `null`（用默认 key，也就是 URL 本身）—— 这样正常的缓存命中不受影响；
 * - 第 n 次重试 → `"$model#retry$n"` —— URL 一个字节都不改，只是让 Coil
 *   「不认上次那个失败」。
 *
 * 磁盘缓存仍然按 URL 存，所以重试成功的那张图对**所有**页面都生效。
 *
 * ## 退避
 *
 * 失败后不立刻重试：跨洋链路的一次抖动通常要几百毫秒才过去，
 * 立刻重试往往还是撞在同一个抖动上。第 1 次等 [FIRST_RETRY_DELAY_MS]，
 * 之后等 [NEXT_RETRY_DELAY_MS]。
 *
 * > 网络层还有一层重试（`ImageRetryInterceptor`，只对 [java.io.IOException] 与 5xx 生效）。
 * > 两层职责不同：网络层对付「一次往返里的抖动」，这里对付「Coil 拿到的结论是失败」
 * > （含解码失败、被上游错误页顶掉等网络层看不见的情形）。
 */
@Composable
fun RetryableImage(
    model: Any,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    retryLimit: Int = 2,
    placeholder: Painter,
    error: Painter,
    contentScale: ContentScale? = ContentScale.Fit
) {
    val context = LocalContext.current
    // ⚠️ 三个状态都必须以 model 为 key：列表里的 Composable 会被复用，
    //    不重置的话新条目会继承上一条已经用掉的次数 ⇒ 新图「一次都不重试」。
    var attempt by remember(model) { mutableIntStateOf(0) }
    var retryScheduled by remember(model) { mutableStateOf(false) }

    LaunchedEffect(retryScheduled) {
        if (!retryScheduled) return@LaunchedEffect
        delay(if (attempt == 0) FIRST_RETRY_DELAY_MS else NEXT_RETRY_DELAY_MS)
        attempt++
        // 复位让「再失败」能再排一次；attempt 已经加过，不会重复计数。
        retryScheduled = false
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(model)
            // 只在重试时换 key，URL 保持原样（原因见上面的注释）。
            .memoryCacheKey(if (attempt == 0) null else "$model#retry$attempt")
            .crossfade(true)
            .listener(
                onError = { _, result ->
                    LogUtil.e("CoilError", "Image load failed (attempt $attempt)", result.throwable)
                }
            ).build(),
        contentDescription = contentDescription,
        placeholder = placeholder,
        error = error,
        modifier = modifier,
        onError = {
            // 用到底了就让错误占位图留在那里 —— 一直转圈反而让人以为还在加载。
            if (attempt < retryLimit) retryScheduled = true
        },
        contentScale = contentScale ?: ContentScale.Fit
    )
}

/** 首次失败后的等待（毫秒）。短一点：多数抖动一次就过去了。 */
private const val FIRST_RETRY_DELAY_MS = 400L

/** 后续重试的等待（毫秒）。 */
private const val NEXT_RETRY_DELAY_MS = 1200L

@Composable
fun getColumnCount(itemWidth: Int): Int {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenWidthPx = windowInfo.containerSize.width
    val screenWidthDp = with(density) { screenWidthPx.toDp() }
    return maxOf(2, (screenWidthDp / itemWidth.dp).toInt())
}

@Composable
fun rememberCardResponsiveWidth(
    horizontalPadding: Dp = SpacingLarge,
    itemSpacing: Dp = SpacingNormal
): Pair<Dp, Float> {
    val containerWidth = LocalWindowInfo.current.containerSize.width
    val density = LocalDensity.current
    val currentWidthDp = with(density) { containerWidth.toDp() }

    val isPreview = LocalInspectionMode.current
    val itemsToShow = if (!isPreview) {
        SettingsRepository.horizontalCardCountConfig.countForWidthDp(currentWidthDp.value.toInt())
    } else {
        val estimatedCardWidth = 160.dp
        maxOf(1f, ((currentWidthDp - (horizontalPadding * 2)) / (estimatedCardWidth + itemSpacing)))
    }

    val safeItemsToShow = maxOf(1f, itemsToShow)
    val cardWidth = (currentWidthDp - (horizontalPadding * 2) - (itemSpacing * (safeItemsToShow - 1))) / safeItemsToShow

    return Pair(cardWidth, safeItemsToShow)
}

@Composable
fun rememberVideoGridColumns(): Int {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenWidthPx = windowInfo.containerSize.width
    val screenWidthDp = with(density) { screenWidthPx.toDp() }

    val isPreview = LocalInspectionMode.current

    return if (!isPreview && SettingsRepository.tabletMode) {
        SettingsRepository.searchGridColumnsConfig.columnsForWidthDp(screenWidthDp.value.toInt())
    } else {
        maxOf(2, ((screenWidthDp + SpacingNormal) / (VideoNormalCardMinWidth + SpacingNormal)).toInt())
    }
}

@Composable
fun rememberRandomLoadingHint(): String {
    val defaultHint = stringResource(R.string.loading)
    if (!SettingsRepository.funLoadingHints) return defaultHint

    val placeholders = stringArrayResource(R.array.loading_hints)
    return remember(placeholders) { placeholders.random() }
}
