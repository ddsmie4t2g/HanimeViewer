package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.HanimeConstants.HANIME_HOSTNAME
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import okhttp3.Dns
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * @project Han1meViewer
 * @author Yenaly Liew
 * @time 2024/03/10 010 17:01
 */
class HDns : Dns {

    private data class DohRuntimeConfig(
        val url: String,
        val bootstrapIps: List<String>,
        val timeoutSeconds: Int,
    )

    @Volatile
    private var cachedDohConfig: DohRuntimeConfig? = null

    @Volatile
    private var cachedDohDns: Dns? = null

    companion object {

        /**
         * hanime 系域名的**内置 IP**（仅在用户手动打开「内置域名」时生效）。
         *
         * ⚠️ 2026-09-12 逐条复测后**重写**。旧表 8 条里有 3 条已彻底失效：
         *
         * | 旧值 | 实测 | 处理 |
         * |---|---|---|
         * | `162.159.0.1` | **403**（边缘拒绝） | 删除 |
         * | `172.64.33.1` | **403** | 删除 |
         * | `108.162.192.1` | **403** | 删除 |
         * | `172.64.229.154` | 200 | 保留 |
         * | `104.19.0.1` | 200（曾偶发 000，复测 4/4 恢复） | 保留 |
         *
         * 补入的是 `doh.pub` 给出的 `hanime1.com` **真实 A 记录** + 若干验证可用的
         * Cloudflare 边缘 IP。以下每个 IPv4 都在 `hanime1.com` 上实测 **3/3 返回 200**
         * （**不带 `-k`**，即完整校验证书，与 app 行为一致）：
         * `172.67.167.30`、`104.21.42.221`、`104.26.7.251`、`104.26.6.251`、
         * `172.67.70.97`、`104.18.53.139`、`172.64.229.154`、`104.19.0.1`。
         *
         * IPv6 放最后：本机无全局 IPv6，**这几条无法实测**，只能取自 `doh.pub` 对
         * `hanime1.com` 的 AAAA 回答。IPv4 在前，v6 不通时 OkHttp 会继续往下试。
         *
         * > 站点换 IP 后需要更新这张表。候选来源：`doh.pub` 的 `hanime1.com` A/AAAA。
         */
        private val cloudFlareIps = listOf(
            "172.67.167.30", "104.21.42.221", "104.26.7.251", "104.26.6.251",
            "172.67.70.97", "104.18.53.139", "172.64.229.154", "104.19.0.1",
            "2606:4700:3037::ac43:a71e", "2606:4700:3032::6815:2add"
        )

        private val getchuIps = listOf("210.155.150.166", "210.155.150.145")

        private const val GETCHU_HOSTNAME = "www.getchu.com"

        /**
         * nJAV 系域名的**内置 IP 兜底**。
         *
         * ⚠️ 这里必须无条件生效（不看 `useBuiltInHosts`，也**不给 DoH / 系统 DNS 任何机会**），
         * 原因是 `njavtv.com` 在国内被 **DNS 投毒** —— 实测 2026-09-11：
         *
         * | 解析途径 | 返回 | 实测 |
         * |---|---|---|
         * | 系统 DNS | `31.13.91.33`（**Facebook 的 IP 段**） | 连接超时 |
         * | 阿里 DoH（dns.alidns.com） | `199.96.63.53`（同样是污染结果） | 连接超时 |
         * | 腾讯 DoH（doh.pub） | `104.26.7.251` / `104.26.6.251` / `172.67.70.97` | **200，211 KB 真页面** |
         *
         * 也就是说：**只要走系统 DNS 或阿里 DoH，nJAV 详情页必然打不开**
         * （表现为「点进去加载不出来」，用户容易误判成「反爬」或「播放器不兼容」）。
         * 这几个 IP 是 Cloudflare 的地址，靠 SNI 路由，直接钉住即可。
         *
         * > 站点若换 IP，需要更新这张表。候选来源：`doh.pub` 的 `njavtv.com` A 记录。
         * > 已排除 `199.96.63.53`（阿里 DoH 返回，实测超时，是污染结果）。
         */
        private val builtInIpsByHost: Map<String, List<String>> = mapOf(
            "njavtv.com" to listOf("104.26.7.251", "104.26.6.251", "172.67.70.97"),
            // 视频源（surrit.com）：系统解析本来是对的（Cloudflare 真实 IP），内置一份是为了
            // 「哪天被投毒了也不至于能解析地址却播不了」。
            // ⚠️ 2026-09-12 复核：doh.pub 给的是 `104.18.53.139` + `104.18.49.25`，
            // 其中 `104.18.49.25` **已经连不上了**（connect 超时），所以只保留可用的那个；
            // 万一它也失效，[lookupBuiltInIps] 的系统 DNS 尾巴会接手。
            "surrit.com" to listOf("104.18.53.139"),
            // nJAV 的**图片 CDN**（女优头像 / 封面，`fourhoi.com`）。
            //
            // ⚠️ 26.8 补：以前这张表里没有它，而它与 njavtv.com 一样会被投毒 ——
            // 表现是「网页上女优一览每个人都有头像，App 里全是空白」。
            // 下面两个 IP 取自实测可用解析（`104.20.20.131` / `172.66.169.100`），
            // 尾部依旧有系统 DNS 兜底。
            "fourhoi.com" to listOf("104.20.20.131", "172.66.169.100"),
            //
            // ── 关于 Pornhub（第三个数据源）───────────────────────────────────
            // 这里**没有**、也不需要它的条目：pornhub.com 与 *.phncdn.com 都是
            // SNI 阻断（不是 DNS 投毒 —— 换了 IP 照样在 TLS 握手阶段被 RST），
            // 所以它的全部流量都由 CdnRelayInterceptor 改写成自建中转到固定 IP，
            // 客户端根本不会去解析这两个域名。往这里加条目对它没有帮助。
        )

        /**
         * 添加DNS
         */
        private operator fun MutableMap<String, List<InetAddress>>.set(
            host: String, ips: List<String>,
        ) {
            this[host] = ips.map {
                InetAddress.getByAddress(host, InetAddress.getByName(it).address)
            }
        }

        /**
         * 解析自定义 IP 列表，逗号分隔
         */
        fun parseCustomIps(raw: String): List<String> {
            return raw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        /**
         * 验证自定义 IP 列表格式是否有效
         * @return 无效 IP 的错误信息列表，为空表示全部有效
         */
        fun validateCustomHosts(raw: String): List<String> {
            val errors = mutableListOf<String>()
            if (raw.isBlank()) return errors
            val ips = parseCustomIps(raw)
            if (ips.isEmpty()) {
                errors.add("No IP addresses entered")
                return errors
            }
            ips.forEach { ip ->
                if (!isValidIpAddress(ip)) {
                    errors.add("Invalid IP address: \"$ip\"")
                }
            }
            return errors
        }

        private fun isValidIpAddress(ip: String): Boolean {
            return runCatching {
                val addr = InetAddress.getByName(ip)
                addr.hostAddress == ip || addr.hostAddress == ip.removePrefix("[")
                    .removeSuffix("]")
            }.getOrDefault(false)
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        // getchu：内置 IP 本身是真的，但它们以前是**光杆**返回的 —— 一旦这两条从用户
        // 的网络不可达，OkHttp 不会再回头问系统 DNS，这个域名就彻底打不开
        // （`GETCHU_HOSTNAME` 的失败曾经只表现为「连接被中断」）。
        // 现在同样接上系统 DNS 尾巴（理由见 [pinnedWithSystemTail]）。
        if (hostname == GETCHU_HOSTNAME) {
            return pinnedWithSystemTail(hostname, getchuIps)
        }

        // nJAV 系 / 图片 CDN：DNS 已被投毒，无条件走内置 IP（理由见 builtInIpsByHost 的注释）。
        lookupBuiltInIps(hostname)?.let { return it }

        // 用户自填的 IP 仍然优先（这是「内置域名」开关唯一的专属作用）。
        if (SettingsRepository.useBuiltInHosts &&
            HANIME_HOSTNAME.any { it.equals(hostname, ignoreCase = true) }
        ) {
            resolveCustomIps()?.takeIf { it.isNotEmpty() }?.let { customIps ->
                return customIps.mapNotNull { ip ->
                    runCatching {
                        InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
                    }.getOrNull()
                }
            }
        }

        // ⭐ hanime 系：**无条件**使用内置 Cloudflare IP，不再要求用户先打开「内置域名」开关。
        //
        // 26.8 修的正是这里：以前这张表只在 `useBuiltInHosts` 打开时才生效，
        // 而默认是关的 ⇒ 大多数用户实际上一直在走 DoH/系统 DNS。
        // 实测（2026-09-13，中国移动）：系统 DNS 给 `hanime1.com` 的是 `104.244.46.85`（投毒），
        // `hanime1.me` 是 `103.252.114.101`（投毒）——
        // 于是**登录**（一次 POST + 一次 GET，全是纯 API 请求）第一个就撞在假 IP 上，
        // 表现就是「不挂梯子登不上」。而这张内置表里的 8 个 IP 实测 **7/8 直接 200**。
        if (HANIME_HOSTNAME.any { it.equals(hostname, ignoreCase = true) }) {
            return pinnedWithSystemTail(hostname, cloudFlareIps)
        }

        val dohUrl = DohConfig.resolveUrl()
        if (!dohUrl.isNullOrBlank()) {
            return runCatching { lookupByDoH(dohUrl, hostname) }
                .getOrElse {
                    LogUtil.w("DOH", "lookup failed for $hostname: ${it.message}")
                    Dns.SYSTEM.lookup(hostname)
                }
        }

        return Dns.SYSTEM.lookup(hostname)
    }

    /**
     * 内置 IP 在前、系统 DNS 结果接在**尾部**。
     *
     * 为什么尾巴必须有：OkHttp 只看 [lookup] 返回的地址，内置表一旦整段过期，
     * 它**不会**再回头问系统 DNS —— 「钉 IP」就会反噬成「这个域名彻底打不开」。
     * 追加在尾部，正常路径仍走内置 IP，全部失败时还有一条退路。
     */
    private fun pinnedWithSystemTail(hostname: String, ips: List<String>): List<InetAddress> {
        val pinned = ips.mapNotNull { ip ->
            runCatching {
                InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
            }.getOrNull()
        }
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        return (pinned + system).distinctBy { it.hostAddress }
    }

    /**
     * nJAV 系域名的内置 IP 解析；域名不在表里就返回 null（交回上层走常规流程）。
     *
     * ⚠️ **内置 IP 后面要接上系统 DNS 的结果**。内置表是「防投毒」用的，但它同时
     * 切断了「表过期时的退路」：OkHttp 只看本函数返回的地址，内置 IP 全连不上不会
     * 自动改问系统 DNS。`surrit.com` 就已经出现过一半 IP 失效（见 [builtInIpsByHost]）。
     * 追加在尾部，正常路径仍然只走内置 IP，代价是「多绕一次」而不是「彻底不通」。
     */
    private fun lookupBuiltInIps(hostname: String): List<InetAddress>? {
        val ips = builtInIpsByHost[hostname.lowercase()] ?: return null
        val pinned = ips.mapNotNull { ip ->
            runCatching {
                InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address)
            }.getOrNull()
        }
        // njavtv.com 的系统 DNS 是被投毒的，所以系统结果只能放尾巴上，
        // 绝不能排在前面 —— 排前面就等于把投毒结果当首选。
        val system = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        return (pinned + system).distinctBy { it.hostAddress }.takeIf { it.isNotEmpty() }
    }

    private fun lookupByDoH(dohUrl: String, hostname: String): List<InetAddress> {
        val config = DohRuntimeConfig(
            url = dohUrl,
            bootstrapIps = DohConfig.bootstrapIps(),
            timeoutSeconds = DohConfig.timeoutSeconds(),
        )
        val dns = getOrCreateDohDns(config)
        return dns.lookup(hostname).also {
            LogUtil.i("DOH", it.toString())
        }
    }

    fun lookupByDoHOnly(hostname: String): List<InetAddress> {
        val dohUrl = DohConfig.resolveUrl() ?: error("DoH is disabled")
        return lookupByDoH(dohUrl, hostname)
    }

    private fun getOrCreateDohDns(config: DohRuntimeConfig): Dns {
        val currentDns = cachedDohDns
        if (currentDns != null && cachedDohConfig == config) return currentDns

        synchronized(this) {
            val dnsAgain = cachedDohDns
            if (dnsAgain != null && cachedDohConfig == config) return dnsAgain

            val client = OkHttpClient.Builder()
                .connectTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .readTimeout(config.timeoutSeconds.toLong(), TimeUnit.SECONDS)
                .build()
            val bootstrapHosts = config.bootstrapIps.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            val dnsBuilder = DnsOverHttps.Builder()
                .client(client)
                .url(config.url.toHttpUrl())
                .includeIPv6(true)
                .post(false)
                .resolvePrivateAddresses(true)
                .resolvePublicAddresses(true)
            if (bootstrapHosts.isNotEmpty()) {
                dnsBuilder.bootstrapDnsHosts(bootstrapHosts)
            }
            val dns = dnsBuilder.build()

            cachedDohConfig = config
            cachedDohDns = dns
            return dns
        }
    }

    fun getCDNList(host: String): List<String> {
        if (host == GETCHU_HOSTNAME) {
            return getchuIps.distinct()
        }

        // nJAV 系直接给内置 IP，别去问系统 DNS（只会拿到污染结果）
        builtInIpsByHost[host.lowercase()]?.let { return it.distinct() }

        if (SettingsRepository.useBuiltInHosts && HANIME_HOSTNAME.contains(host)) {
            val customIps = resolveCustomIps()
            if (!customIps.isNullOrEmpty()) {
                return customIps.distinct()
            }
            return cloudFlareIps.distinct()
        }

        return runCatching {
            Dns.SYSTEM.lookup(host).map { it.hostAddress }.distinct()
        }.getOrElse {
            it.printStackTrace()
            emptyList()
        }
    }

    @Volatile
    private var cachedCustomIps: List<String>? = null

    @Volatile
    private var cachedCustomIpsRaw: String? = null

    private fun resolveCustomIps(): List<String>? {
        val raw = SettingsRepository.customHostsData
        if (raw.isBlank()) return null
        if (raw == cachedCustomIpsRaw && cachedCustomIps != null) {
            return cachedCustomIps
        }
        val result = parseCustomIps(raw)
        cachedCustomIpsRaw = raw
        cachedCustomIps = result
        return result
    }

}
