package io.github.daisukikaffuchino.han1meviewer.ui.player

import android.content.Context
import android.view.Surface
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.USER_AGENT
import io.github.daisukikaffuchino.han1meviewer.logic.njav.PlaybackHttpClient
import io.github.daisukikaffuchino.han1meviewer.util.toNetworkErrorMessageRes
import io.github.daisukikaffuchino.utils.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class ExoPlaybackEngine(
    context: Context,
) : PlaybackEngine, Player.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 缓冲策略（26.9.17 从默认值调宽）。
     *
     * ## 为什么改
     *
     * ExoPlayer 默认 `min = max = 50 s`、重缓冲后要等 **5 s** 才恢复播放。走代理时带宽通常很充裕，
     * 50 s 的缓冲上限意味着「刚好吃饱就停手」，多余带宽完全没用上；而网络抖一下之后
     * 那 5 s 的等待则直接表现为「卡住不动」。
     *
     * | 参数 | 默认 | 现在 | 作用 |
     * |---|---|---|---|
     * | `minBufferMs` | 50 s | 45 s | 持续缓冲的下限，低于它才开始补 |
     * | `maxBufferMs` | 50 s | **90 s** | 缓冲上限 ⇒ 提前多存 40 s，把代理带宽用满 |
     * | `bufferForPlaybackMs` | 2.5 s | **1.5 s** | 起播前的最少缓冲 ⇒ 起播更快 |
     * | `bufferForPlaybackAfterRebufferMs` | 5 s | **3 s** | 抖动后恢复播放更快 |
     *
     * ⚠️ `maxBufferMs` 提高会增加内存占用（1080p 约 +25 MB 量级）。取 90 s 而不是更大，
     * 是为了在「吃满带宽」和「低端机内存」之间留余地；真要在低端机上回收内存，
     * 把这个值调回 50_000 即可，其余三个参数可以不动。
     *
     * ⚠️ 这里**不能**设 `callTimeout`（那是播放链路的事，见 [PlaybackHttpClient]），
     * LoadControl 只管缓冲水位，与单次请求的超时无关。
     */
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 45_000,
            /* maxBufferMs = */ 90_000,
            /* bufferForPlaybackMs = */ 1_500,
            /* bufferForPlaybackAfterRebufferMs = */ 3_000,
        )
        .build()

    /**
     * 重试策略：传输被掐（`SocketException: Connection reset` 之类）时退避重试，而不是直接报错。
     *
     * ⚠️⚠️ **它必须挂在 media source 的 factory 上，挂到 player 上不生效** ——
     * `ExoPlayer.Builder` 在 media3 1.10 **没有** `setLoadErrorHandlingPolicy` 这个方法
     * （`javap` 实测），而且本类是自己 `createMediaSource(...)` 之后
     * `player.setMediaSource(...)`，压根不经过 `DefaultMediaSourceFactory`。
     * 详见 [PlaybackLoadErrorPolicy] 的类注释（含「为什么会看到 ExecutionException」那一段）。
     */
    private val loadErrorPolicy = PlaybackLoadErrorPolicy()

    private val appContext = context.applicationContext

    private val player = ExoPlayer.Builder(appContext)
        .setLoadControl(loadControl)
        .build()
        .apply {
            addListener(this@ExoPlaybackEngine)
        }
    private val mutableState = MutableStateFlow(PlaybackEngineState())
    private var progressJob: Job? = null
    private var released = false

    /**
     * 单独存一份错误信息。
     *
     * ⚠️ **不能让 [publishState] 直接写 `errorMessage = null`** ——
     * 它每 250ms 跑一次，而 [onPlayerError] 只跑一次，
     * 于是刚设上的错误会在 250ms 内被自己冲掉，
     * 表现成「画面卡住 / 0:00:00 但**一个字提示都没有**」，非常难排查。
     * 这里改成：错误值常驻，只有播放真正恢复到 READY 才清。
     */
    private var lastErrorMessage: String? = null

    override val state: StateFlow<PlaybackEngineState> = mutableState.asStateFlow()

    override fun load(request: PlaybackRequest) {
        check(!released) { "Playback engine has already been released" }
        mutableState.value = mutableState.value.copy(
            phase = PlaybackPhase.Preparing,
            isBuffering = true,
            errorMessage = null,
            videoWidth = 0,
            videoHeight = 0,
            hasRenderedFirstFrame = false,
        )
        player.repeatMode = if (request.looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        player.setMediaSource(createMediaSource(request))
        player.prepare()
        if (request.startPositionMs > 0L) {
            player.seekTo(request.startPositionMs)
        }
        player.playWhenReady = request.playWhenReady
        startProgressUpdates()
        publishState()
    }

    override fun play() {
        player.play()
        publishState()
    }

    override fun pause() {
        player.pause()
        publishState()
    }

    override fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        player.seekTo(positionMs.coerceIn(0L, duration ?: Long.MAX_VALUE))
        publishState()
    }

    override fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 5f)
        player.playbackParameters = PlaybackParameters(safeSpeed)
        publishState()
    }

    override fun setVolume(volume: Float) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun attachSurface(surface: Surface) {
        if (released) return
        player.setVideoSurface(surface)
    }

    override fun detachSurface(surface: Surface) {
        if (released) return
        player.clearVideoSurface(surface)
    }

    override fun release() {
        if (released) return
        released = true
        progressJob?.cancel()
        player.removeListener(this)
        player.clearVideoSurface()
        player.release()
        scope.cancel()
        mutableState.value = PlaybackEngineState()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        publishState()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        publishState()
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        publishState()
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        publishState()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        publishState(videoSize)
    }

    override fun onRenderedFirstFrame() {
        mutableState.value = mutableState.value.copy(hasRenderedFirstFrame = true)
    }

    override fun onPlayerError(error: PlaybackException) {
        progressJob?.cancel()
        LogUtil.e(TAG, "Playback failed", error)
        // ⚠️ 界面提示是这条链路出问题时**唯一的诊断入口**（用户没法接 adb 抓日志时尤其重要），
        // 所以它必须带出底层 cause。但原来的写法是
        //
        //     "${error.errorCodeName}：${cause.javaClass.simpleName} ${cause.message}"
        //
        // 而 release 构建里 R8 **会把依赖（含 media3）的类名一起混淆**，于是用户看到的是
        // `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED：e23 java.io.IOException: java.util.concurrent…`
        // —— 那个 `e23` 谁都认不出来，等于把最有用的那一截丢了。
        // 现在改成按**类型**判定（类型检查不受混淆影响）并复用首页那套三语齐全的文案，
        // 见 [describePlaybackError]。
        val detail = describePlaybackError(error)
        lastErrorMessage = detail
        mutableState.value = mutableState.value.copy(
            phase = PlaybackPhase.Error,
            isPlaying = false,
            isBuffering = false,
            errorMessage = detail,
        )
    }

    private fun startProgressUpdates() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                publishState()
                delay(PROGRESS_UPDATE_INTERVAL_MS.milliseconds)
            }
        }
    }

    private fun publishState(videoSize: VideoSize = player.videoSize) {
        if (released) return
        val duration = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        // 只有播放真正恢复到 READY 才清错误；否则保留（理由见 lastErrorMessage 的注释）
        if (player.playbackState == Player.STATE_READY) lastErrorMessage = null
        mutableState.value = mutableState.value.copy(
            phase = if (lastErrorMessage != null) {
                PlaybackPhase.Error
            } else {
                when (player.playbackState) {
                    Player.STATE_BUFFERING -> PlaybackPhase.Preparing
                    Player.STATE_READY -> PlaybackPhase.Ready
                    Player.STATE_ENDED -> PlaybackPhase.Ended
                    else -> PlaybackPhase.Idle
                }
            },
            isPlaying = player.isPlaying,
            isBuffering = player.isLoading || player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            playbackSpeed = player.playbackParameters.speed,
            videoWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt(),
            videoHeight = videoSize.height,
            errorMessage = lastErrorMessage,
        )
    }

    private fun createMediaSource(request: PlaybackRequest): MediaSource {
        // ⚠️ 用 OkHttp 而不是 DefaultHttpDataSource：后者走 HttpURLConnection，
        // 会绕开应用的 HDns 兜底与用户代理配置，导致「详情页能开、一播放就 0:00/0」。
        // HLS 的主列表 / 子列表 / 每个分片都会经过这个 factory，
        // 所以 Referer 之类的防盗链头也会自动带到分片请求上。
        val httpFactory = OkHttpDataSource.Factory(PlaybackHttpClient.client)
            .setUserAgent(USER_AGENT)
            .setDefaultRequestProperties(request.headers)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val item = MediaItem.fromUri(request.uri.toUri())
        // ⚠️ 重试策略挂在 **factory** 上（理由见 [loadErrorPolicy] 的注释）。
        return if (request.uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
                .createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(loadErrorPolicy)
                .createMediaSource(item)
        }
    }

    private companion object {
        const val TAG = "ExoPlaybackEngine"
        const val PROGRESS_UPDATE_INTERVAL_MS = 250L

        /** 兜底文案里技术细节的截断长度（避免把一整条异常链塞进错误提示）。 */
        const val ERROR_DETAIL_MAX_CHARS = 200
    }

    /**
     * 把 [PlaybackException] 翻成一句人话。
     *
     * 映射复用 `util/Networks.kt` 里那套「异常 → 文案」表（`home_error_*`，三语齐全）：
     * 它认的类型（`UnknownHostException` / 超时 / TLS / `ConnectException` /
     * `SocketException: Connection reset` / 403 / 404 / 5xx）**正好覆盖播放链路会遇到的全部形态**，
     * 所以这里不需要再加一份文案。
     *
     * 判断走 [`Throwable.toNetworkErrorMessageRes`]，它自身也带消息兜底 ——
     * 这一点对 media3 1.10 很关键：`OkHttpDataSource` 把失败包成
     * `IOException(ExecutionException(SocketException))`，**最外层是个普通 `IOException`**，
     * 靠类型匹配不到 `SocketException`；但那个 `IOException` 的 message 就是
     * `cause.toString()`（里面含 `Connection reset`），消息匹配能兜住。
     *
     * ⚠️ 唯一不套本地化文案的是「兜底」（`home_error_generic`）：那条写的是「页面加载失败」，
     * 放在播放器里是错的。这一档只摊开 `errorCodeName` + 原始 message ——
     * 用户截图给我们时，靠的就是这一档。
     */
    private fun describePlaybackError(error: PlaybackException): String {
        val cause = error.cause ?: error
        val stringRes = cause.toNetworkErrorMessageRes()
        return buildString {
            if (stringRes == R.string.home_error_generic) {
                append(error.errorCodeName)
                cause.message?.takeIf { it.isNotBlank() }?.let {
                    append("：").append(it.take(ERROR_DETAIL_MAX_CHARS))
                }
            } else {
                append(appContext.getString(stringRes))
                append("（").append(error.errorCodeName).append("）")
            }
        }
    }
}
