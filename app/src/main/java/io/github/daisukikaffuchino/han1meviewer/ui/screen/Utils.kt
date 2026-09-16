package io.github.daisukikaffuchino.han1meviewer.ui.screen

import io.github.daisukikaffuchino.utils.LogUtil
import android.os.SystemClock
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
 *
 * ## 26.9.9 修了什么（重试预算必须是**这条图**的，不能是这次 composition 的）
 *
 * `remember(model)` 只在这份 composition 里有效。列表一回收 —— 滚出屏幕再滚回来、
 * 切页、页面重建 —— 预算就归零，于是同一张永远取不到的图会被**反复**重试一整个预算：
 * 用户看到的就是「一张加载不出来的头像一直在重试」。
 *
 * 所以预算改存到进程级台账 [ImageFailureLedger] 里，**按图片 URL 记**：
 * - 同一个 URL 最多重试 [MAX_IMAGE_RETRIES] 次（[FORGET_AFTER_MS] 内累计）；
 * - 取到一次就清零（那张图是真能拿到的，下次该按正常流程走）；
 * - 预算用尽 ⇒ 停在错误占位图上，不再发请求（一直转圈比错误图更让人以为「还在加载」）。
 *
 * 一张图连续 5 次都没拿到，再试第 6 次没有道理 —— 抖动不会持续那么久，而真拿不到的
 * （签名过期 403、图片已下架 404）重试多少次也拿不到。
 */
@Composable
fun RetryableImage(
    model: Any,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    placeholder: Painter,
    error: Painter,
    contentScale: ContentScale? = ContentScale.Fit
) {
    val context = LocalContext.current
    val ledgerKey = model.toString()
    // ⚠️ 初值取自台账：被回收重建后**接着上次的预算**，而不是从 0 重来。
    var attempt by remember(model) { mutableIntStateOf(ImageFailureLedger.count(ledgerKey)) }
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
        onSuccess = { ImageFailureLedger.succeed(ledgerKey) },
        onError = {
            // 台账才是有 authority 的那份计数：它跨 composition，界面被回收也不会
            // 把预算还回去。超了就停在错误占位图上。
            val used = ImageFailureLedger.fail(ledgerKey)
            if (used <= MAX_IMAGE_RETRIES) retryScheduled = true
        },
        contentScale = contentScale ?: ContentScale.Fit
    )
}

/** 单张图片在**整个进程内**允许的重试次数（不含首次尝试）。 */
private const val MAX_IMAGE_RETRIES = 5

/**
 * 「这张图已经连续失败过几次」——**按 URL** 记，进程级共享。
 *
 * 存在的唯一理由：`remember` 的寿命等于 composition，而列表会回收 composition，
 * 预算必须活得比它长，否则「限流」限的是**界面重建次数**，不是图片本身（见
 * [RetryableImage] 的 KDoc）。
 *
 * - 成功即清除：能拿到就说明这张图没问题；
 * - 用 LRU 封顶 [MAX_ENTRIES] 条：坏 URL 反复出现也不会把内存撑大；
 * - ⭐ [FORGET_AFTER_MS] 之后**忘掉**这条记录：预算用尽 = 「这一轮别试了」，不是
 *   「这个 URL 这辈子都不许再试」。少了这一条，一次全局抖动（代理断了一刻钟）
 *   会把这期间所有封面/头像在本进程内**永久**钉在错误占位图上，用户除了重启
 *   App 没有任何办法 —— 那比「多试几次」糟得多。
 * - 全同步：调用点在主线程的 `onSuccess/onError` 回调上，也可能在 IO 线程上。
 */
private object ImageFailureLedger {
    private const val MAX_ENTRIES = 256

    /** 超过这么久没再失败，就当作新的一轮（见 KDoc 最后一条）。 */
    private const val FORGET_AFTER_MS = 10 * 60 * 1000L

    private class Entry(var count: Int, var at: Long)

    private val entries = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 取一条**还活着**的记录；过期的顺手删掉。调用方必须已持有 [entries] 的锁。 */
    private fun live(key: String): Entry? {
        val entry = entries[key] ?: return null
        if (SystemClock.uptimeMillis() - entry.at > FORGET_AFTER_MS) {
            entries.remove(key)
            return null
        }
        return entry
    }

    fun count(key: String): Int = synchronized(entries) { live(key)?.count ?: 0 }

    /** 记一次失败，返回**含这次在内**的累计失败次数。 */
    fun fail(key: String): Int = synchronized(entries) {
        val entry = live(key)
        if (entry == null) {
            entries[key] = Entry(1, SystemClock.uptimeMillis())
            1
        } else {
            entry.count++
            entry.at = SystemClock.uptimeMillis()
            entry.count
        }
    }

    /**
     * 加载成功：把这条 URL 的失败账本清零。
     *
     * ⚠️ 必须显式写返回类型（或写成语句体）。写成
     * `fun succeed(key: String) = synchronized(entries) { entries.remove(key) }`
     * 会让**推断**出来的返回类型是 `Entry?`，而 `Entry` 是类内私有 ⇒
     * 编译报 `'private-in-file' function exposes its 'private-in-class' return type 'Entry'`。
     */
    fun succeed(key: String) {
        synchronized(entries) { entries.remove(key) }
    }
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
