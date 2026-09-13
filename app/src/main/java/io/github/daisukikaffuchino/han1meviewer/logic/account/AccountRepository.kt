package io.github.daisukikaffuchino.han1meviewer.logic.account

import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **自建账号**的本地状态与高层操作（26.7.0）。
 *
 * 与 hanime 的站点登录完全分开：
 *
 * | | hanime 登录（[SettingsRepository.isAlreadyLogin]） | 自建账号（这里） |
 * |---|---|---|
 * | 账号在哪 | hanime1 的服务器 | **用户自己的 VPS** |
 * | 存什么 | hanime 的订阅 / 清单 / 评论 | **关注 / 本机清单 / 观看记录** |
 * | 凭证 | 站点 cookie | 这里自己签发的 Bearer token |
 * | 换机后 | 换站点的号 | **数据还在你自己服务器上** |
 *
 * token 与用户名存在 [SettingsRepository.accountJson]（一个 JSON 字符串），
 * 因为它总是整组读写，拆成多个字段只会让 DataStore 映射表更长。
 */
object AccountRepository {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class AccountState(
        val token: String = "",
        val username: String = "",
        /** 上次看到的**云端**数据版本（上传时当 baseRevision 用）。 */
        val revision: Int = 0,
        val lastSyncAt: Long = 0L,
    ) {
        val isLoggedIn: Boolean get() = token.isNotBlank() && username.isNotBlank()
    }

    /** 同步结果（成功与否都带一句**能读懂**的话，界面直接显示）。 */
    data class SyncOutcome(
        val success: Boolean,
        val message: String,
        val diff: AccountSync.Diff? = null,
    )

    val serverUrl: String get() = AccountApi.baseUrl

    /** 账号状态流（界面订阅它，注册/登录/登出后自动刷新）。 */
    val state: Flow<AccountState> = SettingsRepository.settings.map { decode(it.accountJson) }

    suspend fun current(): AccountState = decode(SettingsRepository.accountJson)

    private fun decode(raw: String): AccountState = runCatching {
        json.decodeFromString<AccountState>(raw.ifBlank { "{}" })
    }.getOrDefault(AccountState())

    private suspend fun save(state: AccountState) {
        SettingsRepository.setAccountJson(json.encodeToString(state))
    }

    /** 探活：注册/登录前给用户一个「服务器通不通」的明确答案，别让他在表单上猜。 */
    suspend fun ping(): Boolean = AccountApi.ping()

    suspend fun register(username: String, password: String): Result<AccountState> =
        runCatching {
            val session = AccountApi.register(username.trim(), password)
            val state = AccountState(
                token = session.token,
                username = session.user.username,
                revision = session.user.dataRevision,
            )
            save(state)
            state
        }.recoverCatching { e -> throw translate(e) }

    suspend fun login(username: String, password: String): Result<AccountState> =
        runCatching {
            val session = AccountApi.login(username.trim(), password)
            val state = AccountState(
                token = session.token,
                username = session.user.username,
                revision = session.user.dataRevision,
            )
            save(state)
            state
        }.recoverCatching { e -> throw translate(e) }

    /**
     * 登出：**先清本地再告诉服务端**。
     *
     * 反过来的话，服务端一旦超时，用户会卡在「点了登出但还是登录态」；而本地清了之后，
     * 即使服务端那次 logout 没成功，最坏情况也只是那枚 token 还在服务端有效（可改密踢掉）。
     */
    suspend fun logout() {
        val state = current()
        save(AccountState())
        if (state.token.isNotBlank()) AccountApi.logout(state.token)
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = runCatching {
        val state = requireLoggedIn()
        AccountApi.changePassword(state.token, oldPassword, newPassword)
        // 服务端改密会踢掉所有会话（包括这枚 token）→ 本地也清掉，逼一次重新登录。
        save(AccountState())
    }.recoverCatching { e -> throw translate(e) }

    /**
     * ⭐ **双向同步**：拉云端 → 与本机合并 → 写回本机 → 传回云端。
     *
     * 顺序刻意是「先合并再上传」：这样第一次登录时，云端是空的、本机有数据，
     * 结果就是把本机数据**带上去**；换机时反过来，把云端数据**带下来**。
     *
     * 冲突（别的设备刚传过）由 [AccountApi.putData] 抛 [AccountApi.ConflictException] 暴露，
     * 这里用服务端那份再合并一次重试（最多 3 次）—— 因为合并是**可交换**的
     * （见 [AccountSync.merge]），重试必然收敛。
     */
    suspend fun sync(): SyncOutcome = runCatching<SyncOutcome> {
        val state = requireLoggedIn()
        val remote = AccountApi.fetchData(state.token)
        val remoteSnapshot = AccountSync.decode(remote.data)
        val localSnapshot = AccountSync.snapshot()
        val merged = AccountSync.merge(localSnapshot, remoteSnapshot)
        val diff = AccountSync.diff(localSnapshot, merged)
        AccountSync.apply(merged)
        push(state, merged, remote.revision, diff)
    }.getOrElse { e ->
        SyncOutcome(success = false, message = readableError(e))
    }

    /** 只上传本机数据（本机是权威时的入口）。 */
    suspend fun uploadLocal(): SyncOutcome = runCatching<SyncOutcome> {
        val state = requireLoggedIn()
        push(state, AccountSync.snapshot(), state.revision, diff = null)
    }.getOrElse { e -> SyncOutcome(success = false, message = readableError(e)) }

    /**
     * 上传（带冲突重试）。
     *
     * 抽成独立函数有两个原因：一是 `while(true)` 当 lambda 的最后一句会让
     * `runCatching` 的类型推断成 `Unit`（编译不过）；二是这段「冲突 → 再合并 → 再传」
     * 的闭环在 [sync] 与 [uploadLocal] 里一模一样，写两遍迟早会漂移。
     */
    private suspend fun push(
        state: AccountState,
        snapshot: AccountSnapshot,
        baseRevision: Int,
        diff: AccountSync.Diff?,
    ): SyncOutcome {
        var data = snapshot
        var base = baseRevision
        var attempt = 0
        while (true) {
            try {
                val result = AccountApi.putData(state.token, base, AccountSync.encode(data))
                save(state.copy(revision = result.revision, lastSyncAt = System.currentTimeMillis()))
                return SyncOutcome(success = true, message = "同步完成", diff = diff)
            } catch (conflict: AccountApi.ConflictException) {
                attempt += 1
                if (attempt > 3) {
                    return SyncOutcome(
                        success = false,
                        message = "云端数据一直在变，稍后再试（已合并的内容已保存在本机）",
                        diff = diff,
                    )
                }
                // 把服务端最新那份并进来，再用它的 revision 重传。
                data = AccountSync.merge(data, AccountSync.decode(conflict.data))
                AccountSync.apply(data)
                base = conflict.revision
            }
        }
    }

    /** 刷新账号信息（顺带验证 token 还有效）。 */
    suspend fun refresh(): Result<AccountState> = runCatching {
        val state = requireLoggedIn()
        val user = AccountApi.me(state.token)
        val updated = state.copy(
            username = user.username,
            revision = user.dataRevision,
        )
        save(updated)
        updated
    }.recoverCatching { e -> throw translate(e) }

    private suspend fun requireLoggedIn(): AccountState {
        val state = current()
        if (!state.isLoggedIn) throw IllegalStateException("尚未登录自建账号")
        return state
    }

    /** 把异常翻译成用户能读懂的一句话（服务端的 message 本来就是短句，优先用它）。 */
    fun readableError(e: Throwable): String = when (e) {
        is AccountApi.AccountException -> when (e.code) {
            401 -> "登录已失效，请重新登录"
            403 -> e.message
            409 -> "用户名已被占用"
            429 -> "尝试太频繁，请过几分钟再试"
            0 -> e.message
            else -> "服务器返回 ${e.code}：${e.message}"
        }

        is AccountApi.NetworkException -> e.message
        is java.net.UnknownHostException -> "找不到服务器地址（${e.message.orEmpty().ifBlank { "DNS 失败" }}）"
        is java.net.SocketTimeoutException -> "服务器响应超时（${e.message.orEmpty().ifBlank { "timeout" }}）"
        is javax.net.ssl.SSLException -> "证书校验失败：服务器可能换机了，需要更新 App（${e::class.java.simpleName}）"
        is java.io.IOException ->
            "连不上账号服务器（${e::class.java.simpleName}）：${e.message.orEmpty().ifBlank { "网络不可达" }}"

        // ⚠️ 最后这条兜底**必须**存在：以前失败时把 `it.message.orEmpty()` 交给界面，
        // 异常 message 为 null 时界面拿到空串 → 提示写着「原因见下方」而下面什么都没有。
        else -> e.message?.takeIf { it.isNotBlank() }
            ?: "未知错误（${e::class.java.simpleName}）"
    }

    /**
     * 把任何异常规整成「一定有一句能看懂的话」的异常。
     *
     * 业务错误（[AccountApi.AccountException]）原样保留 —— 界面要靠它的 `errorCode` 查本地化文案；
     * 其余（网络、超时、证书、未知）包成 [AccountApi.NetworkException]，message 由 [readableError] 保证非空。
     */
    private fun translate(e: Throwable): Throwable =
        if (e is AccountApi.AccountException) e else AccountApi.NetworkException(readableError(e), e)
}
