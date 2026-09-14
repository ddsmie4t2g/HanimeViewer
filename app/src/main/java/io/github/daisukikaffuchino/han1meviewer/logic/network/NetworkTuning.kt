package io.github.daisukikaffuchino.han1meviewer.logic.network

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit

/**
 * 全应用共用的 OkHttp 连接池与调度器。
 *
 * ## 为什么需要它：OkHttp 的默认值是按「同城服务器」调的
 *
 * 默认是 `maxRequestsPerHost = 5`、连接池 5 条空闲连接。那套值在延迟几毫秒的
 * 同城服务上够用，但本应用的主要流量都落在**自己的中转**上（`hembed` / `fourhoi` /
 * `phncdn` / `getchu`，见 [CdnRelay]）—— 对客户端来说它们**全都是同一个 host**
 * （中转那台洛杉矶 VPS）。于是「每主机 5 并发」变成了整条链路的并发上限：
 *
 * | 场景 | 请求数 | 5 并发要几轮 |
 * |---|---|---|
 * | 首页一屏 | ~30 张封面 | 6 轮 |
 * | 女优一览一页 | 24 张头像 | 5 轮 |
 * | Getchu 发售表 | ~29 张封面 | 6 轮 |
 * | HLS 播放 | 每个分片 1 个 | 持续排队 |
 *
 * 每轮都要等一个完整往返（大陆 ↔ 洛杉矶约 180 ms），于是「带宽明明还空着、就是慢」
 * —— 这正是用户说的「美国云没跑满」。
 *
 * ## 取值
 *
 * - `maxRequestsPerHost = 24`：一屏的封面/头像能**一次铺完**。再往上收益很小
 *   （一屏也就放得下这么多），却会明显抬高中转的线程数与上游连接数。
 * - `maxRequests = 128`：全局上限，给非中转域名（hanime 主站、GitHub 等）留足份额。
 * - 连接池 `32` 条 / `300 s`：与中转的 `Keep-Alive: timeout=300` **对齐**。
 *   两边一致才不会出现「客户端以为连接还活着、服务端已经关了」的竞态 ——
 *   那种竞态的表现是随机的 `unexpected end of stream`，极难复现。
 *
 * ⚠️ 这两个对象是**进程内共享**的：多个 client 共用同一个池，图片、视频分片、
 * 页面请求才能真正复用同一批 socket。给每个 client 各配一份，等于把并发又切回小块，
 * 而且会把连接池的容量也切碎。
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
