package io.agedm.tv.data

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class BangumiAccountService(
    private val repository: AgeRepository,
    private val playbackStore: PlaybackStore,
    private val store: BangumiAccountStore,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _account = MutableStateFlow(store.readSession())
    val account: StateFlow<BangumiAccountSession?> = _account.asStateFlow()

    private val loginChallenges = ConcurrentHashMap<String, LoginChallenge>()
    private val writeQueue = Channel<QueuedStatusWrite>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in writeQueue) {
                runCatching { processQueuedWrite(task) }
                delay(Random.nextLong(700L, 1_300L))
            }
        }
        if (isLoggedIn()) {
            syncExistingPlaybackRecords()
        }
    }

    fun isLoggedIn(): Boolean = _account.value?.cookies?.isNotEmpty() == true

    fun currentAccount(): BangumiAccountSession? = _account.value

    fun cachedCollectionStatus(animeId: Long): BangumiCollectionStatus? {
        return BangumiCollectionStatus.fromWireName(store.getCollectionStatus(animeId)?.status)
    }

    suspend fun prepareLoginPage(): BangumiLoginPage {
        val response = executeTextRequest("https://bgm.tv/login")
        val document = Jsoup.parse(response.body)
        val form = document.selectFirst("form[action*=FollowTheRabbit]")
            ?: throw IOException("Bangumi 登录页结构已变化")
        val fields = linkedMapOf<String, String>()
        form.select("input[name]").forEach { input ->
            fields[input.attr("name")] = input.attr("value")
        }
        val sessionId = UUID.randomUUID().toString()
        loginChallenges[sessionId] = LoginChallenge(
            sessionId = sessionId,
            cookies = response.cookies.toMutableMap(),
            hiddenFields = fields,
            createdAtMs = System.currentTimeMillis(),
        )
        pruneExpiredChallenges()
        return BangumiLoginPage(sessionId)
    }

    suspend fun loadCaptcha(sessionId: String): BinaryResponse {
        val challenge = loginChallenges[sessionId] ?: throw IOException("登录会话已失效，请重新扫码")
        val nonce = "${System.currentTimeMillis()}${Random.nextInt(1, 7)}"
        val response = executeBinaryRequest(
            url = "https://bgm.tv/signup/captcha?$nonce",
            cookies = challenge.cookies,
        )
        challenge.cookies.putAll(response.cookies)
        return response
    }

    suspend fun submitLogin(
        sessionId: String,
        username: String,
        password: String,
        captcha: String,
    ): BangumiLoginResult {
        val challenge = loginChallenges.remove(sessionId) ?: return BangumiLoginResult(
            success = false,
            message = "登录会话已过期，请重新扫码",
        )
        if (username.isBlank() || password.isBlank() || captcha.isBlank()) {
            return BangumiLoginResult(false, "请输入用户名、密码和验证码")
        }

        val fields = linkedMapOf<String, String>()
        fields.putAll(challenge.hiddenFields)
        fields["email"] = username
        fields["password"] = password
        fields["captcha_challenge_field"] = captcha
        fields["loginsubmit"] = fields["loginsubmit"].orEmpty().ifBlank { "登录" }
        fields.remove("cookietime")

        val loginResponse = executeTextRequest(
            url = "https://bgm.tv/FollowTheRabbit",
            cookies = challenge.cookies,
            formFields = fields,
        )
        val cookies = challenge.cookies.toMutableMap().apply { putAll(loginResponse.cookies) }
        val homepage = executeTextRequest(
            url = "https://bgm.tv/",
            cookies = cookies,
        )
        cookies.putAll(homepage.cookies)
        val account = parseAccount(homepage.body, cookies)
        return if (account != null) {
            store.saveSession(account)
            _account.value = account
            syncExistingPlaybackRecords()
            BangumiLoginResult(true, "Bangumi 登录成功", account)
        } else {
            BangumiLoginResult(false, "登录失败，请检查用户名、密码和验证码")
        }
    }

    fun logout() {
        store.clearSession()
        _account.value = null
    }

    fun enqueueManualStatusUpdate(animeId: Long, title: String, status: BangumiCollectionStatus) {
        if (!isLoggedIn()) return
        writeQueue.trySend(
            QueuedStatusWrite(
                animeId = animeId,
                title = title,
                status = status,
                mode = WriteMode.UPSERT,
            ),
        )
    }

    fun enqueuePlaybackStarted(animeId: Long, title: String) {
        if (!isLoggedIn()) return
        writeQueue.trySend(
            QueuedStatusWrite(
                animeId = animeId,
                title = title,
                status = BangumiCollectionStatus.DO,
                mode = WriteMode.PLAYBACK_STARTED,
            ),
        )
    }

    fun enqueuePlaybackCompleted(animeId: Long, title: String) {
        if (!isLoggedIn()) return
        writeQueue.trySend(
            QueuedStatusWrite(
                animeId = animeId,
                title = title,
                status = BangumiCollectionStatus.COLLECT,
                mode = WriteMode.UPSERT,
            ),
        )
    }

    fun enqueueBatchStatusUpdate(cards: Collection<AnimeCard>, status: BangumiCollectionStatus) {
        if (!isLoggedIn()) return
        cards.forEach { card ->
            writeQueue.trySend(
                QueuedStatusWrite(
                    animeId = card.animeId,
                    title = card.title,
                    status = status,
                    mode = WriteMode.UPSERT,
                ),
            )
        }
    }

    fun syncExistingPlaybackRecords() {
        if (!isLoggedIn()) return
        playbackStore.getRecentRecords(60).forEach { record ->
            writeQueue.trySend(
                QueuedStatusWrite(
                    animeId = record.animeId,
                    title = record.animeTitle,
                    status = if (record.completed) BangumiCollectionStatus.COLLECT else BangumiCollectionStatus.DO,
                    mode = if (record.completed) WriteMode.UPSERT else WriteMode.PLAYBACK_STARTED,
                ),
            )
        }
    }

    suspend fun fetchCollectionStatus(animeId: Long, title: String): BangumiCollectionStatus? {
        val subjectId = resolveSubjectId(animeId, title) ?: return null
        val form = fetchCollectionForm(subjectId)
        val status = form.status
        store.saveSubjectMatch(subjectId, animeId)
        store.saveCollectionStatus(
            animeId,
            BangumiCollectionCacheEntry(
                subjectId = subjectId,
                status = status?.wireName.orEmpty(),
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        return status
    }

    suspend fun fetchMyPage(): BangumiMyPageData? {
        val session = _account.value ?: return null
        val sections = buildList {
            add(fetchCollectionSection(session, BangumiCollectionStatus.DO, "在看", 12))
            add(fetchCollectionSection(session, BangumiCollectionStatus.WISH, "想看", 12))
            add(fetchCollectionSection(session, BangumiCollectionStatus.COLLECT, "看过", 12))
            add(fetchCollectionSection(session, BangumiCollectionStatus.ON_HOLD, "搁置", 12))
            add(fetchCollectionSection(session, BangumiCollectionStatus.DROPPED, "抛弃", 12))
        }.filter { it.items.isNotEmpty() }
        return BangumiMyPageData(
            username = session.username,
            displayName = session.displayName.ifBlank { session.username },
            sections = sections,
        )
    }

    private suspend fun fetchCollectionSection(
        session: BangumiAccountSession,
        status: BangumiCollectionStatus,
        title: String,
        limit: Int,
    ): BrowseSection {
        val response = executeTextRequest(
            url = "https://bgm.tv/anime/list/${session.username}/${status.wireName}",
            cookies = session.cookies,
        )
        val document = Jsoup.parse(response.body)
        val cards = document.select("#browserItemList li.item").mapNotNull { item ->
            val link = item.selectFirst("a.subjectCover[href*=/subject/], h3 a[href*=/subject/]") ?: return@mapNotNull null
            val subjectId = link.attr("href").substringAfterLast('/').toLongOrNull() ?: return@mapNotNull null
            val alignedAnimeId = store.findAnimeIdBySubjectId(subjectId)
                ?: alignSubjectToAge(subjectId, item)
                ?: return@mapNotNull null
            store.saveCollectionStatus(
                alignedAnimeId,
                BangumiCollectionCacheEntry(
                    subjectId = subjectId,
                    status = status.wireName,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            val titleText = item.selectFirst("h3 a.l")?.text()?.trim().orEmpty()
            val subtitle = item.selectFirst("p.collectInfo")?.text()?.trim().orEmpty()
            val cover = item.selectFirst("img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-cfsrc") }
            }.orEmpty()
            AnimeCard(
                animeId = alignedAnimeId,
                title = titleText,
                cover = cover,
                badge = status.label,
                subtitle = subtitle,
            )
        }.take(limit)
        return BrowseSection(
            title = title,
            subtitle = "Bangumi · ${cards.size} 条",
            items = cards,
        )
    }

    private suspend fun alignSubjectToAge(subjectId: Long, item: org.jsoup.nodes.Element): Long? {
        val titles = listOfNotNull(
            item.selectFirst("h3 a.l")?.text()?.trim()?.takeIf { it.isNotBlank() },
            item.selectFirst("h3 small.grey")?.text()?.trim()?.takeIf { it.isNotBlank() },
        )
        val aligned = repository.alignBangumiTitlesToAge(titles) ?: return null
        store.saveSubjectMatch(subjectId, aligned.animeId)
        return aligned.animeId
    }

    private suspend fun processQueuedWrite(task: QueuedStatusWrite) {
        if (!isLoggedIn()) return
        val current = runCatching { fetchCollectionStatus(task.animeId, task.title) }.getOrNull()
        if (!shouldWrite(current, task)) return
        updateCollectionStatus(task.animeId, task.title, task.status)
    }

    private fun shouldWrite(
        current: BangumiCollectionStatus?,
        task: QueuedStatusWrite,
    ): Boolean {
        if (current == task.status) return false
        if (task.mode == WriteMode.PLAYBACK_STARTED && current == BangumiCollectionStatus.COLLECT) {
            return false
        }
        return true
    }

    private suspend fun updateCollectionStatus(
        animeId: Long,
        title: String,
        status: BangumiCollectionStatus,
    ) {
        val subjectId = resolveSubjectId(animeId, title) ?: return
        val form = fetchCollectionForm(subjectId)
        val fields = linkedMapOf<String, String>()
        fields.putAll(form.fields)
        fields["interest"] = status.interestValue
        fields["referer"] = "ajax"
        fields["update"] = "保存"
        executeTextRequest(
            url = form.actionUrl,
            cookies = requireSessionCookies(),
            formFields = fields,
        )
        store.saveSubjectMatch(subjectId, animeId)
        store.saveCollectionStatus(
            animeId,
            BangumiCollectionCacheEntry(
                subjectId = subjectId,
                status = status.wireName,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun fetchCollectionForm(subjectId: Long): CollectionForm {
        val response = executeTextRequest(
            url = "https://bgm.tv/update/$subjectId?keepThis=false&TB_iframe=true&height=360&width=500",
            cookies = requireSessionCookies(),
        )
        val document = Jsoup.parse(response.body)
        val form = document.selectFirst("#collectBoxForm")
            ?: throw IOException("未能加载 Bangumi 收藏表单，登录状态可能已失效")
        val actionUrl = absoluteUrl("https://bgm.tv", form.attr("action"))
        val fields = linkedMapOf<String, String>()
        form.select("input[name], textarea[name]").forEach { element ->
            val name = element.attr("name").trim()
            if (name.isBlank()) return@forEach
            when (element.tagName()) {
                "textarea" -> fields[name] = element.text()
                else -> {
                    val type = element.attr("type").lowercase()
                    if (type == "radio") {
                        if (element.hasAttr("checked")) fields[name] = element.attr("value")
                    } else if (type == "checkbox") {
                        if (element.hasAttr("checked")) fields[name] = element.attr("value").ifBlank { "on" }
                    } else {
                        fields[name] = element.attr("value")
                    }
                }
            }
        }
        val status = form.select("input[name=interest][type=radio]")
            .firstOrNull { it.hasAttr("checked") }
            ?.attr("value")
            ?.let(BangumiCollectionStatus::fromInterestValue)
        return CollectionForm(
            actionUrl = actionUrl,
            fields = fields,
            status = status,
        )
    }

    private suspend fun resolveSubjectId(animeId: Long, title: String): Long? {
        store.getCollectionStatus(animeId)?.subjectId?.takeIf { it > 0 }?.let { return it }
        val metadata = repository.ensureBangumiMetadata(animeId, title) ?: return null
        if (metadata.subjectId > 0) {
            store.saveSubjectMatch(metadata.subjectId, animeId)
        }
        return metadata.subjectId.takeIf { it > 0 }
    }

    private fun parseAccount(body: String, cookies: Map<String, String>): BangumiAccountSession? {
        val document = Jsoup.parse(body)
        val profileLink = document.selectFirst("#dock a[href^=/user/], #headerNeue2 a[href^=/user/]")
            ?: return null
        val username = profileLink.attr("href").substringAfterLast('/').trim()
        if (username.isBlank()) return null
        val displayName = profileLink.text().trim().ifBlank { username }
        val avatarUrl = document.selectFirst("#dock a.avatar img, #badgeUserPanel a.avatar img")
            ?.attr("src")
            .orEmpty()
        return BangumiAccountSession(
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            cookies = cookies,
            lastValidatedMs = System.currentTimeMillis(),
        )
    }

    private fun requireSessionCookies(): Map<String, String> {
        return _account.value?.cookies?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Bangumi 账号尚未登录")
    }

    private fun pruneExpiredChallenges() {
        val threshold = System.currentTimeMillis() - LOGIN_CHALLENGE_TTL_MS
        loginChallenges.entries.removeIf { it.value.createdAtMs < threshold }
    }

    private fun executeTextRequest(
        url: String,
        cookies: Map<String, String> = emptyMap(),
        formFields: Map<String, String>? = null,
    ): TextResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://bgm.tv/")
        if (cookies.isNotEmpty()) {
            requestBuilder.header("Cookie", buildCookieHeader(cookies))
        }
        if (formFields != null) {
            val body = FormBody.Builder().apply {
                formFields.forEach { (key, value) -> add(key, value) }
            }.build()
            requestBuilder.post(body)
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Bangumi 请求失败 ${response.code}")
            }
            return TextResponse(
                body = body,
                cookies = parseSetCookies(response.headers),
            )
        }
    }

    private fun executeBinaryRequest(
        url: String,
        cookies: Map<String, String> = emptyMap(),
    ): BinaryResponse {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://bgm.tv/login")
        if (cookies.isNotEmpty()) {
            requestBuilder.header("Cookie", buildCookieHeader(cookies))
        }
        client.newCall(requestBuilder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (!response.isSuccessful) {
                throw IOException("验证码加载失败 ${response.code}")
            }
            return BinaryResponse(
                bytes = bytes,
                contentType = response.header("Content-Type").orEmpty(),
                cookies = parseSetCookies(response.headers),
            )
        }
    }

    private fun parseSetCookies(headers: Headers): Map<String, String> {
        return buildMap {
            headers.values("Set-Cookie").forEach { header ->
                val cookie = header.substringBefore(';')
                val name = cookie.substringBefore('=').trim()
                val value = cookie.substringAfter('=', "").trim()
                if (name.isNotBlank()) {
                    put(name, value)
                }
            }
        }
    }

    private fun buildCookieHeader(cookies: Map<String, String>): String {
        return cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    private fun absoluteUrl(baseUrl: String, raw: String): String {
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> baseUrl.trimEnd('/') + raw
            else -> baseUrl.trimEnd('/') + "/" + raw
        }
    }

    private data class LoginChallenge(
        val sessionId: String,
        val cookies: MutableMap<String, String>,
        val hiddenFields: Map<String, String>,
        val createdAtMs: Long,
    )

    private data class TextResponse(
        val body: String,
        val cookies: Map<String, String>,
    )

    data class BinaryResponse(
        val bytes: ByteArray,
        val contentType: String,
        val cookies: Map<String, String>,
    )

    private data class CollectionForm(
        val actionUrl: String,
        val fields: Map<String, String>,
        val status: BangumiCollectionStatus?,
    )

    private enum class WriteMode {
        UPSERT,
        PLAYBACK_STARTED,
    }

    private data class QueuedStatusWrite(
        val animeId: Long,
        val title: String,
        val status: BangumiCollectionStatus,
        val mode: WriteMode,
    )

    companion object {
        private const val LOGIN_CHALLENGE_TTL_MS = 10 * 60_000L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
