package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

/**
 * **nJAV 女优索引的本地缓存**（26.8.2 新增）。
 *
 * ## 它解决的是什么
 *
 * nJAV 的**头像只存在于女优一览 / 排行页的卡片里** —— 视频详情页只有名字、
 * 女优页顶部是首字占位符。而索引页**没有名字检索**（`?q=` 等实测被忽略），
 * 于是「按名字找头像」只能一页页翻，一次要 1~3 个 180 KB 的页面请求。
 * 表现就是用户说的：**头像不是不出现，而是要好一会儿才出现**。
 *
 * 这里把「已经从索引 / 排行页拿到的女优」按名字落盘。于是：
 *
 * | 场景 | 之前 | 现在 |
 * |---|---|---|
 * | 打开作者页补头像 | 1~3 次页面请求（数秒） | 一次本地查询（0 请求） |
 * | 从女优一览点进作者页 | 还要再查一次 | **卡片里就带着头像**，根本不用查 |
 * | 只浏览过一览 / 排行 | —— | 一次请求就把 24~100 个人全存进来了 |
 *
 * ## 几条刻意的取舍
 *
 * - **只缓存有头像的条目**：这份缓存存在的唯一理由就是头像，空头像存进去等于存了个 miss。
 * - **不设过期**：`fourhoi.com/actress/<id>-t.jpg` 是稳定直链，头像换了地址也会变
 *   （新 id 就是新 URL）。作品数 / 出道年可能会旧，但那份数据只是卡片上的一行小字，
 *   不值得为它过期整份缓存。
 * - **有上限、按写入时间淘汰**：索引有 1400+ 页、上万人，不设限会把这个
 *   Preferences 文件撑爆。只留最近见过的 [MAX_ENTRIES] 条。
 * - **与「关注表」完全分开**：关注表是用户数据（会同步到自建账号），这份是纯缓存。
 *   [SettingsRepository.njavActressCacheJson] 里存的就是它。
 *
 * ⚠️ 所有读写都包了 `runCatching`：`SettingsRepository.store` 是 `lateinit`，
 * **JVM 单测里没有装过**，直接读会抛 `UninitializedPropertyAccessException`。
 */
object NjavActressCache {

    /**
     * @param avatar 头像直链（**只存非空**）
     * @param videoCount 「5669 条影片」里的 5669
     * @param debutYear 「2008 出道」里的 2008
     * @param path 形如 `actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3`
     * @param rank 排行页的名次（一览页拿到的条目没有）
     * @param updatedAt 写入时刻，淘汰时用
     */
    @Serializable
    data class Entry(
        val avatar: String = "",
        val videoCount: Int? = null,
        val debutYear: Int? = null,
        val name: String = "",
        val path: String = "",
        val rank: Int? = null,
        val updatedAt: Long = 0L,
    )

    /**
     * 上限 1500 条（约 200 KB）。
     *
     * 依据：索引默认按作品数倒序，「叫得出名字」的女优都挤在前几十页，
     * 1500 条 ≈ 62 页（每页 24 人）≈ 覆盖到很冷门的名字了；再多只是白占地方。
     */
    private const val MAX_ENTRIES = 1500

    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()

    /** 进程内的那份（读设置只做一次）。 */
    private var snapshot: Map<String, Entry>? = null

    /**
     * 名字归一化。
     *
     * ⚠️ 索引页 `href` 用**繁体**、`h4` 用简体（见 [NjavActress.path]），
     * 所以「波多野结衣 / 波多野結衣」这两个写法**都会出现在同一个人身上**。
     * 这里只做 Unicode NFC + 去空白 + 小写，**不做繁简转换** ——
     * 转换表是一大坨数据，而多存一条同名条目没有任何代价。
     */
    private fun key(name: String): String = runCatching {
        Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
            .lowercase()
            .filterNot { it.isWhitespace() }
    }.getOrDefault(name.trim().lowercase())

    private fun load(): Map<String, Entry> {
        snapshot?.let { return it }
        val decoded = runCatching {
            val raw = SettingsRepository.njavActressCacheJson
            if (raw.isBlank()) emptyMap() else json.decodeFromString<Map<String, Entry>>(raw)
        }.getOrDefault(emptyMap())
        snapshot = decoded
        return decoded
    }

    /** 缓存里有没有这个人（且带着头像）。 */
    fun contains(name: String): Boolean = load()[key(name)]?.avatar?.isNotBlank() == true

    /**
     * 两个名字是不是同一个人。
     *
     * 判据与缓存键一致（NFC + 去空白 + 小写）：`NetworkRepo.findNjavActress` 用它
     * 在索引页结果里找目标，这样「缓存查得到」和「翻页找得到」不会出现两套口径。
     */
    fun matches(a: String, b: String): Boolean = key(a) == key(b)

    /** 头像直链；没有就返回空串（**不会**发起网络请求）。 */
    fun avatarOf(name: String): String =
        load()[key(name)]?.avatar?.trim().orEmpty()

    /** 缓存里的完整条目；没有返回 null。 */
    fun find(name: String): NjavActress? {
        val entry = load()[key(name)] ?: return null
        if (entry.name.isBlank() || entry.avatar.isBlank()) return null
        return NjavActress(
            name = entry.name,
            avatarUrl = entry.avatar,
            videoCount = entry.videoCount,
            debutYear = entry.debutYear,
            path = entry.path,
            rank = entry.rank,
        )
    }

    /**
     * 按**女优路径**查（26.8.3 新增）：作者页拿得到 `…/actresses/<编码名>`，
     * 那是最稳的定位方式 —— 比按名字查更可靠，因为详情页给的名字可能与索引页的
     * 写法不同（一个繁体一个简体）。
     *
     * @param path 形如 `actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3`，
     *   由 [io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork.actressPathFrom] 抠出来
     */
    fun findByPath(path: String): NjavActress? {
        val wanted = pathKey(path) ?: return null
        val hit = pathIndex()[wanted] ?: return null
        if (hit.avatar.isBlank() || hit.name.isBlank()) return null
        return NjavActress(
            name = hit.name,
            avatarUrl = hit.avatar,
            videoCount = hit.videoCount,
            debutYear = hit.debutYear,
            path = hit.path,
            rank = hit.rank,
        )
    }

    /** 路径 → 条目（按需建立；上限 1500 条，一次遍历而已）。 */
    private var cachedPathIndex: Map<String, Entry>? = null

    private fun pathIndex(): Map<String, Entry> {
        cachedPathIndex?.let { return it }
        val index = LinkedHashMap<String, Entry>()
        load().values.forEach { entry ->
            val k = pathKey(entry.path) ?: return@forEach
            // 同一路径有多条时保留**有头像**的那条（没头像的那条对这里没用）。
            val old = index[k]
            if (old == null || (old.avatar.isBlank() && entry.avatar.isNotBlank())) {
                index[k] = entry
            }
        }
        cachedPathIndex = index
        return index
    }

    /**
     * 路径的归一形式：**解码后再归一**。
     *
     * 站点同一张女优页会写 `actresses/%E6%B3%A2...`（编码）或者直接写汉字，
     * 而 `dm###` 前缀与 `?page=` 也不该参与比较 —— 不归一就会「明明缓存里有，就是查不到」。
     */
    private fun pathKey(path: String): String? {
        val raw = path.trim()
        if (raw.isEmpty()) return null
        val tail = raw.substringAfter("actresses/", "").substringBefore('?').trim('/')
        if (tail.isEmpty()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(tail, "UTF-8") }.getOrDefault(tail)
        return key(decoded)
    }

    /** 已缓存的条目数（设置页 / 日志用）。 */
    val size: Int get() = load().size

    /**
     * 把一批刚从索引 / 排行页解析出来的女优记下来。
     *
     * @return 真正新增或更新的条数（0 表示没有任何变化，调用方可以据此跳过落盘）。
     */
    suspend fun rememberAll(actresses: List<NjavActress>): Int {
        val usable = actresses.filter { it.avatarUrl.isNotBlank() && it.name.isNotBlank() }
        if (usable.isEmpty()) return 0
        return mutex.withLock {
            val current = load().toMutableMap()
            val now = System.currentTimeMillis()
            var changed = 0
            usable.forEach { actress ->
                val k = key(actress.name)
                val old = current[k]
                // 头像没变就不重写 —— 否则每翻一页都要动一次 DataStore。
                val same = old != null &&
                        old.avatar == actress.avatarUrl &&
                        old.videoCount == actress.videoCount &&
                        old.debutYear == actress.debutYear &&
                        old.rank == actress.rank
                if (same) return@forEach
                current[k] = Entry(
                    avatar = actress.avatarUrl,
                    videoCount = actress.videoCount,
                    debutYear = actress.debutYear,
                    name = actress.name,
                    path = actress.path,
                    rank = actress.rank,
                    updatedAt = now,
                )
                changed++
            }
            if (changed == 0) return@withLock 0
            val trimmed = if (current.size <= MAX_ENTRIES) {
                current
            } else {
                // 淘汰最旧的：按 updatedAt 升序丢掉超出部分。
                current.entries
                    .sortedByDescending { it.value.updatedAt }
                    .take(MAX_ENTRIES)
                    .associate { it.key to it.value }
            }
            snapshot = trimmed
            // 路径索引是 snapshot 的派生物，snapshot 一换就必须丢掉（26.8.3）。
            cachedPathIndex = null
            runCatching {
                SettingsRepository.setNjavActressCacheJson(json.encodeToString(trimmed))
            }
            changed
        }
    }

    /** 清空（设置里「清除缓存」用；正常流程不会调）。 */
    suspend fun clear() {
        mutex.withLock {
            snapshot = emptyMap()
            cachedPathIndex = null
            runCatching { SettingsRepository.setNjavActressCacheJson("") }
        }
    }
}
