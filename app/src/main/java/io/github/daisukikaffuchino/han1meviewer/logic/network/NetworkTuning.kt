package io.github.daisukikaffuchino.han1meviewer.logic.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * 全应用共用的 OkHttp 连接池与调度器。
 *
 * ## 为什么需要它：默认值对「一条 180 ms RTT 的跨境线路」太小了
 *
 * OkHttp 的默认值是 `maxRequestsPerHost = 5`、连接池 `maxIdleConnections = 5`。
 * 那套默认值是按「同城服务器」调的：延迟几毫秒，5 条并发足够打满。
 *
 * 但本应用的主要流量都落在**自己的中转**上（`hembed` / `fourhoi` / `phncdn` /
 * `getchu`，见 [CdnRelay]）—— 对客户端来说它们**全都是同一个 host**（中转那台 VPS）。
 * 于是 `maxRequestsPerHost = 5` 变成了整条链路的并发上限：
 *
 * | 场景 | 请求数 | 5 并发要几轮 |
 * |---|---|---|
 * | 女优一览一页 | 24 张头像 | 5 轮 |
 * | 首页 | ~30 张封面 | 6 轮 |
 * | HLS 播放 | 每个分片 1 个 + 清单 | 持续排队 |
 *
 * 每一轮都要等一个完整往返（大陆 ↔ 洛杉矶约 180 ms，加上中转再取一次），
 * 于是「明明带宽还很空、就是慢」—— 这正是用户看到的「美国云没跑满」。
 *
 * ## 取值
 *
 * - `maxRequestsPerHost = 24`：一次能把上面那几类页面**一口气铺完**。
 *   再往上收益就很小了（一屏也就放得下这么多），却会明显抬高中转的线程数与上游连接数。
 * - `maxRequests = 128`：全局上限，留足非中转域名（hanime 主站等）的份额。
 * - 连接池 `32 / 5 min`：与中转的 `Keep-Alive: timeout=300` 对齐 ——
 *   两边一致才不会出现「客户端以为还活着、服务端已经关了」的竞态。
 *   32 条空闲连接足以覆盖上面所有 host 的复用。
 *
 * ⚠️ 这两个对象是**进程内共享**的：多个 client 共用一个池，才能让图片、视频分片、
 * 页面请求真正复用同一批 socket。给每个 client 各配一份，等于把并发又切回小块。
 */
object NetworkTuning {

    /** 空闲连接上限。取值理由见类注释。 */
    private const val MAX_IDLE_CONNECTIONS = 32

    /** 空闲连接保活时长（秒），与中转侧的 `KEEP_ALIVE_TIMEOUT` 保持一致。 */
    private const val KEEP_ALIVE_SECONDS = 300L

    val connectionPool: ConnectionPool =
        ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)

    val dispatcher: Dispatcher = Dispatcher().apply {
        maxRequests = 128
        // 关键的一个：中转把所有被封域名收束成同一个 host，这一项就是总并发。
        maxRequestsPerHost = 24
    }
}
