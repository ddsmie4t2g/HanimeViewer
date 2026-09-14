package io.github.daisukikaffuchino.han1meviewer.logic.account

import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * **自建账号服务**的 HTTP 层（26.7.0 新增）。
 *
 * ## 它和 hanime 的登录没有任何关系
 *
 * hanime 的登录是**站点账号**（往 hanime 发用户名密码、拿 cookie，见 `NetworkRepo.login`），
 * 只能存 hanime 的东西。这里是**用户自己的账号**：跑在用户自己的 VPS 上，存的是
 * 「我的关注 / 我的清单 / 我的观看记录」，与任何站点无关。用户的原话是
 * 「最好做一个自己的登录，不跟 hanime 等等有任何关系，用来存储自己的数据」。
 *
 * ## 信任链：复用中转那张已内置的证书
 *
 * 账号服务与中转跑在同一台机器上，直接用同一张自签证书（SAN = 服务器 IP），
 * 于是客户端不需要第二套信任链 —— `CdnRelay.sslContext/trustManager` 里已经把这张证书
 * 和系统 CA 组合好了（见那里的注释）。**换机时两者一起换**，与中转的换机流程完全一样。
 *
 * ## 服务端接口（见 VPS 上 /opt/haccount/account.py）
 *
 * | 方法 | 路径 | 说明 |
 * |---|---|---|
 * | GET | `/ping` | 探活（不需要 token）|
 * | POST | `/api/register` `/api/login` | 返回 `{token,user}` |
 * | POST | `/api/logout` | 让当前 token 失效 |
 * | GET | `/api/me` | 账号信息（含云端数据版本）|
 * | GET | `/api/data` | 云端数据 + revision |
 * | PUT | `/api/data` | 上传（带 baseRevision，冲突回 409 + 服务器那份）|
 * | POST | `/api/password` | 改密（改完所有会话失效）|
 */
object AccountApi {

    /**
     * 服务地址。与中转同一台机器、同一张证书，只是端口不同（中转 7443 / 账号 7444）。
     */
    const val HOST = CdnRelay.HOST
    const val PORT = 7444

    val baseUrl: String get() = "https://$HOST:$PORT"

    private const val JSON_TYPE = "application/json; charset=utf-8"

    /**
     * 账号专用 client。
     *
     * ⚠️ 三项必须对齐 [CdnRelay] 那边：**内置证书的复合信任链**（否则每个请求都会
     * 证书校验失败，而报错看起来像「服务器挂了」）、**UserAgent 拦截器**、**不写死
     * 明文超时**（自签 + 境外，首字节要等）。
     *
     * 刻意**不挂** `CdnRelayInterceptor`：那个只按白名单改写到中转，账号服务是直连的
     * 普通境外 IP（不在任何黑名单里），走中转反而多一跳。
     */
    private val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .sslSocketFactory(CdnRelay.sslContext.socketFactory, CdnRelay.trustManager)
            // 显式挂代理选择器（与 [CdnRelay.probeClient] 一致：不写的话靠 ProxySelector.getDefault()
            // 在启动时被换成 HProxySelector 的副作用，写出来才不会被后来人当成漏挂）。
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .build()
    }

    /**
     * 服务端返回的业务错误。
     *
     * ⚠️ 两个字段各有用途，别合并：
     * - [errorCode] 是**机器码**（`username_invalid` / `password_too_short` / `username_taken` …），
     *   界面拿它查本地化文案 —— 只显示服务端那句英文，中文用户看不懂「原因」；
     * - [message] 是服务端的人读兜底，机器码认不出时原样显示（总比空白强）。
     */
    class AccountException(
        val code: Int,
        val errorCode: String,
        override val message: String,
    ) : Exception(message)

    /** 网络层错误（连不上、超时、证书）——它们没有服务端机器码，但**必须**有一句能看懂的话。 */
    class NetworkException(override val message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * 上传冲突：服务端的数据比客户端新。
     *
     * 带着**服务器当前那份**一起抛出来，调用方合并后再传一次 —— 这就是整套同步的
     * 冲突处理闭环（服务端只做「乐观并发」，合并规则全在客户端，见 [AccountSync]）。
     */
    class ConflictException(
        val revision: Int,
        val updatedAt: Long,
        val data: String,
    ) : Exception("cloud data is newer (revision=$revision)")

    data class User(val username: String, val createdAt: Long, val dataRevision: Int, val dataUpdatedAt: Long)

    data class Session(val token: String, val user: User)

    data class RemoteData(val revision: Int, val updatedAt: Long, val data: String)

    // ────────────────────────────────────────────────────────── 基础请求

    private suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: JSONObject()).toString().toRequestBody(JSON_TYPE.toMediaType()))
            "PUT" -> builder.put((body ?: JSONObject()).toString().toRequestBody(JSON_TYPE.toMediaType()))
            else -> error("unsupported method $method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (!response.isSuccessful) {
                val code = response.code
                if (code == 409 && json?.optString("error") == "conflict") {
                    throw ConflictException(
                        revision = json.optInt("revision", 0),
                        updatedAt = json.optLong("updatedAt", 0L),
                        data = json.opt("data")?.toString() ?: "{}",
                    )
                }
                throw AccountException(
                    code = code,
                    errorCode = json?.optString("error").orEmpty().ifBlank { "error" },
                    // 服务端现在两个字段都发：error=机器码、message=人读说明。
                    // 老版本服务端只发 error（整句话），所以 message 缺失时回退到 error。
                    message = json?.optString("message").orEmpty().ifBlank {
                        json?.optString("error").orEmpty().ifBlank { "HTTP $code" }
                    },
                )
            }
            json ?: throw AccountException(response.code, "bad_response", "服务端返回的不是 JSON")
        }
    }

    private fun parseUser(json: JSONObject): User {
        val user = json.optJSONObject("user") ?: JSONObject()
        return User(
            username = user.optString("username"),
            createdAt = user.optLong("createdAt", 0L),
            dataRevision = user.optInt("dataRevision", 0),
            dataUpdatedAt = user.optLong("dataUpdatedAt", 0L),
        )
    }

    // ────────────────────────────────────────────────────────── 接口

    suspend fun ping(): Boolean = runCatching {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$baseUrl/ping").get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        }
    }.getOrDefault(false)

    suspend fun register(username: String, password: String): Session {
        val json = call("POST", "/api/register", JSONObject().apply {
            put("username", username)
            put("password", password)
        })
        return Session(json.optString("token"), parseUser(json))
    }

    suspend fun login(username: String, password: String): Session {
        val json = call("POST", "/api/login", JSONObject().apply {
            put("username", username)
            put("password", password)
        })
        return Session(json.optString("token"), parseUser(json))
    }

    suspend fun logout(token: String) {
        runCatching { call("POST", "/api/logout", JSONObject(), token) }
    }

    suspend fun me(token: String): User = parseUser(call("GET", "/api/me", token = token))

    suspend fun fetchData(token: String): RemoteData {
        val json = call("GET", "/api/data", token = token)
        return RemoteData(
            revision = json.optInt("revision", 0),
            updatedAt = json.optLong("updatedAt", 0L),
            data = json.opt("data")?.toString() ?: "{}",
        )
    }

    /**
     * 上传整份数据。
     *
     * [baseRevision] 传**上次看到的云端版本**：不一致时服务端回 409，
     * 这里变成 [ConflictException]（带上服务器那份）由调用方合并重试。
     */
    suspend fun putData(token: String, baseRevision: Int, data: String): RemoteData {
        // data 是 JSON 文本，必须**原样**嵌进去（再转义成字符串的话服务端存的就是一坨字符串）。
        val dataObject = runCatching { JSONObject(data.ifBlank { "{}" }) }
            .getOrElse { throw AccountException(0, "local_json_invalid", "本机数据不是合法 JSON，未上传") }
        val payload = JSONObject().apply {
            put("baseRevision", baseRevision)
            put("data", dataObject)
        }
        val json = call("PUT", "/api/data", payload, token)
        return RemoteData(
            revision = json.optInt("revision", 0),
            updatedAt = json.optLong("updatedAt", 0L),
            data = "{}",
        )
    }

    suspend fun changePassword(token: String, oldPassword: String, newPassword: String) {
        call("POST", "/api/password", JSONObject().apply {
            put("oldPassword", oldPassword)
            put("newPassword", newPassword)
        }, token)
    }
}
