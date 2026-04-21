package io.agedm.tv.data

import android.util.Log
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

class BangumiAccountService(
    private val repository: AgeRepository,
    private val playbackStore: PlaybackStore,
    private val store: BangumiAccountStore,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _account = MutableStateFlow(store.readSession())
    val account: StateFlow<BangumiAccountSession?> = _account.asStateFlow()

    private val loginChallenges = ConcurrentHashMap<String, LoginChallenge>()
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)
    private val queueLock = Any()
    private val userPendingWrites = LinkedHashMap<Long, QueuedStatusWrite>()
    private val backgroundPendingWrites = LinkedHashMap<Long, QueuedStatusWrite>()

    init {
        scope.launch {
            while (true) {
                val task = nextQueuedWrite()
                runCatching { processQueuedWrite(task) }
                    .onFailure { error ->
                        Log.w(
                            TAG,
                            "Bangumi write failed: animeId=${task.animeId}, title=${task.title}, " +
                                "status=${task.status}, source=${task.source}",
                            error,
                        )
                    }
                delay(
                    if (task.source == WriteSource.USER_ACTION) {
                        Random.nextLong(200L, 450L)
                    } else {
                        Random.nextLong(700L, 1_300L)
                    },
                )
            }
        }
        if (isLoggedIn()) {
            scope.launch {
                validateStoredSession()
            }
        }
    }

    fun isLoggedIn(): Boolean = _account.value?.cookies?.isNotEmpty() == true

    fun currentAccount(): BangumiAccountSession? = _account.value

    fun cachedCollectionStatus(animeId: Long): BangumiCollectionStatus? {
        return BangumiCollectionStatus.fromWireName(store.getCollectionStatus(animeId)?.status)
    }

    suspend fun prepareLoginPage(): BangumiLoginPage {
        val response = executeTextRequest(
            url = LOGIN_URL,
            referer = LOGIN_URL,
        )
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

    suspend fun loadCaptcha(
        sessionId: String,
        requestToken: Long = System.currentTimeMillis(),
    ): BinaryResponse {
        val challenge = loginChallenges[sessionId] ?: throw IOException("登录会话已失效，请重新扫码")
        val nonce = "${System.currentTimeMillis()}${Random.nextInt(1, 7)}"
        val captchaResponse = executeBinaryRequest(
            url = "$CAPTCHA_URL?$nonce",
            cookies = challenge.cookies,
            referer = LOGIN_URL,
        )
        val mergedCookies = challenge.cookies.toMutableMap().apply { putAll(captchaResponse.cookies) }
        val loginResponse = executeTextRequest(
            url = LOGIN_URL,
            cookies = mergedCookies,
            referer = LOGIN_URL,
        )
        val refreshedFields = parseLoginHiddenFields(loginResponse.body).takeIf { it.isNotEmpty() }
            ?: challenge.hiddenFields
        val refreshedChallenge = LoginChallenge(
            sessionId = sessionId,
            cookies = loginResponse.cookies.toMutableMap(),
            hiddenFields = refreshedFields,
            createdAtMs = System.currentTimeMillis(),
            lastCaptchaToken = requestToken,
        )
        loginChallenges.compute(sessionId) { _, current ->
            when {
                current == null -> null
                requestToken >= current.lastCaptchaToken -> refreshedChallenge
                else -> current
            }
        }
        return captchaResponse
    }

    suspend fun submitLogin(
        sessionId: String,
        username: String,
        password: String,
        captcha: String,
    ): BangumiLoginResult {
        val challenge = loginChallenges[sessionId] ?: return BangumiLoginResult(
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
            url = FOLLOW_THE_RABBIT_URL,
            cookies = challenge.cookies,
            formFields = fields,
            referer = LOGIN_URL,
            origin = BASE_URL.removeSuffix("/"),
        )
        val cookies = challenge.cookies.toMutableMap().apply { putAll(loginResponse.cookies) }
        val directAccount = parseAccount(loginResponse.body, cookies)
        val homepage = if (directAccount == null) {
            executeTextRequest(
                url = BASE_URL,
                cookies = cookies,
                referer = LOGIN_URL,
            )
        } else {
            null
        }
        homepage?.cookies?.let(cookies::putAll)
        val account = directAccount ?: homepage?.let { parseAccount(it.body, cookies) }
        return if (account != null) {
            val verified = verifyAuthenticatedAccount(cookies)
            if (verified == null) {
                BangumiLoginResult(
                    success = false,
                    message = "登录未完成，Bangumi 会话未通过校验，请重新输入验证码",
                )
            } else {
            loginChallenges.remove(sessionId)
                store.saveSession(verified)
                _account.value = verified
                BangumiLoginResult(true, "Bangumi 登录成功", verified)
            }
        } else {
            val failureMessage = extractLoginFailureMessage(loginResponse.body)
                ?: homepage?.body?.let(::extractLoginFailureMessage)
                ?: "登录失败，请检查用户名、密码和验证码"
            loginChallenges[sessionId] = refreshChallenge(
                sessionId = sessionId,
                fallback = challenge,
                cookies = cookies,
                loginPageHtml = loginResponse.body,
            )
            BangumiLoginResult(false, failureMessage)
        }
    }

    fun logout() {
        store.clearSession()
        _account.value = null
    }

    private suspend fun validateStoredSession() {
        val session = _account.value ?: return
        runCatching {
            refreshStoredAccount(session)
        }
    }

    private fun clearInvalidSession() {
        store.clearSession()
        _account.value = null
    }

    private fun updatePersistedSession(
        extraCookies: Map<String, String> = emptyMap(),
        validatedAtMs: Long = System.currentTimeMillis(),
    ) {
        val current = _account.value ?: return
        val mergedCookies = current.cookies.toMutableMap().apply { putAll(extraCookies) }
        val updated = current.copy(
            cookies = mergedCookies,
            lastValidatedMs = validatedAtMs,
        )
        store.saveSession(updated)
        _account.value = updated
    }

    fun enqueueManualStatusUpdate(animeId: Long, title: String, status: BangumiCollectionStatus) {
        if (!isLoggedIn()) return
        enqueueWrite(
            QueuedStatusWrite(
                animeId = animeId,
                title = title,
                status = status,
                mode = WriteMode.UPSERT,
                source = WriteSource.USER_ACTION,
            ),
        )
    }

    fun enqueuePlaybackStarted(animeId: Long, title: String) {
        if (!isLoggedIn()) return
        when (cachedCollectionStatus(animeId)) {
            BangumiCollectionStatus.DO,
            BangumiCollectionStatus.COLLECT,
            BangumiCollectionStatus.ON_HOLD,
            BangumiCollectionStatus.DROPPED,
            -> return
            null,
            BangumiCollectionStatus.WISH,
            -> Unit
        }
        enqueueWrite(
            QueuedStatusWrite(
                animeId = animeId,
                title = title,
                status = BangumiCollectionStatus.DO,
                mode = WriteMode.PLAYBACK_STARTED,
                source = WriteSource.PLAYBACK_SYNC,
            ),
        )
    }

    fun enqueuePlaybackCompleted(animeId: Long, title: String) {
        if (!isLoggedIn()) return
        when (cachedCollectionStatus(animeId)) {
            BangumiCollectionStatus.COLLECT,
            BangumiCollectionStatus.ON_HOLD,
            BangumiCollectionStatus.DROPPED,
            -> return
            null,
            BangumiCollectionStatus.WISH,
            BangumiCollectionStatus.DO,
            -> Unit
        }
        enqueueWrite(
            QueuedStatusWrite(
                animeId = animeId,
                title = title,
                status = BangumiCollectionStatus.COLLECT,
                mode = WriteMode.UPSERT,
                source = WriteSource.PLAYBACK_SYNC,
            ),
        )
    }

    fun enqueueBatchStatusUpdate(cards: Collection<AnimeCard>, status: BangumiCollectionStatus) {
        if (!isLoggedIn()) return
        cards.forEach { card ->
            enqueueWrite(
                QueuedStatusWrite(
                    animeId = card.animeId,
                    title = card.title,
                    status = status,
                    mode = WriteMode.UPSERT,
                    source = WriteSource.USER_ACTION,
                ),
            )
        }
    }

    fun syncExistingPlaybackRecords() {
        if (!isLoggedIn()) return
        playbackStore.getRecentRecords(MAX_BACKGROUND_SYNC_RECORDS).forEach { record ->
            when (cachedCollectionStatus(record.animeId)) {
                BangumiCollectionStatus.DO,
                BangumiCollectionStatus.COLLECT,
                BangumiCollectionStatus.ON_HOLD,
                BangumiCollectionStatus.DROPPED,
                -> return@forEach
                null,
                BangumiCollectionStatus.WISH,
                -> Unit
            }
            enqueueWrite(
                QueuedStatusWrite(
                    animeId = record.animeId,
                    title = record.animeTitle,
                    status = BangumiCollectionStatus.DO,
                    mode = WriteMode.PLAYBACK_STARTED,
                    source = WriteSource.PLAYBACK_SYNC,
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
        val refreshedSession = refreshStoredAccount(session)
        var loadedAnySection = false
        var lastError: Throwable? = null
        val sections = listOf(
            Triple(BangumiCollectionStatus.DO, "在看", 12),
            Triple(BangumiCollectionStatus.WISH, "想看", 12),
            Triple(BangumiCollectionStatus.COLLECT, "看过", 12),
            Triple(BangumiCollectionStatus.ON_HOLD, "搁置", 12),
            Triple(BangumiCollectionStatus.DROPPED, "抛弃", 12),
        )
            .mapNotNull { (status, title, limit) ->
                runCatching { fetchCollectionSection(refreshedSession, status, title, limit) }
                    .onSuccess { loadedAnySection = true }
                    .onFailure { lastError = it }
                    .getOrNull()
            }
            .filter { it.items.isNotEmpty() }
        val fetchError = lastError
        if (!loadedAnySection && fetchError != null) throw fetchError
        return BangumiMyPageData(
            username = refreshedSession.username,
            displayName = refreshedSession.displayName.ifBlank { refreshedSession.username },
            sections = sections,
        )
    }

    private suspend fun fetchCollectionSection(
        session: BangumiAccountSession,
        status: BangumiCollectionStatus,
        title: String,
        limit: Int,
    ): BrowseSection {
        val response = executeAuthenticatedTextRequest(
            url = "https://bgm.tv/anime/list/${session.username}/${status.wireName}",
            cookies = requireSessionCookies(),
        )
        val document = Jsoup.parse(response.body)
        val cards = buildList {
            for (item in document.select("#browserItemList li.item")) {
                if (size >= limit) break
                val link = item.selectFirst("a.subjectCover[href*=/subject/], h3 a[href*=/subject/]") ?: continue
                val subjectId = link.attr("href").substringAfterLast('/').toLongOrNull() ?: continue
                val alignedAnimeId = store.findAnimeIdBySubjectId(subjectId)
                    ?: alignSubjectToAge(subjectId, item)
                    ?: continue
                store.saveCollectionStatus(
                    alignedAnimeId,
                    BangumiCollectionCacheEntry(
                        subjectId = subjectId,
                        status = status.wireName,
                        updatedAtMs = System.currentTimeMillis(),
                    ),
                )
                val titleText = extractCollectionTitles(item).firstOrNull().orEmpty()
                val subtitle = item.selectFirst("p.collectInfo")?.text()?.trim().orEmpty()
                val cover = item.selectFirst("img")?.let { img ->
                    img.attr("src").ifBlank { img.attr("data-cfsrc") }
                }.orEmpty()
                add(
                    AnimeCard(
                        animeId = alignedAnimeId,
                        title = titleText,
                        cover = cover,
                        badge = status.label,
                        subtitle = subtitle,
                        bgmScore = repository.peekBangumiScore(alignedAnimeId).orEmpty(),
                    ),
                )
            }
        }
        return BrowseSection(
            title = title,
            subtitle = "Bangumi · ${cards.size} 条",
            items = cards,
        )
    }

    private suspend fun alignSubjectToAge(subjectId: Long, item: org.jsoup.nodes.Element): Long? {
        val titles = extractCollectionTitles(item)
        val aligned = repository.alignBangumiTitlesToAge(titles) ?: return null
        store.saveSubjectMatch(subjectId, aligned.animeId)
        return aligned.animeId
    }

    private fun extractCollectionTitles(item: org.jsoup.nodes.Element): List<String> {
        return sequenceOf(
            item.selectFirst("h3 a.l")?.text(),
            item.selectFirst("h3 small.grey")?.text(),
            item.selectFirst("a.subjectCover")?.attr("title"),
            item.selectFirst("a.subjectCover img")?.attr("alt"),
            item.selectFirst("h3 a[href*=/subject/]")?.attr("title"),
        )
            .map { it?.trim().orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private suspend fun processQueuedWrite(task: QueuedStatusWrite) {
        if (!isLoggedIn()) return
        val current = resolveCurrentStatus(task)
        if (!shouldWrite(current, task)) return
        updateCollectionStatus(task.animeId, task.title, task.status)
    }

    private fun enqueueWrite(task: QueuedStatusWrite) {
        val changed = synchronized(queueLock) {
            when (task.source) {
                WriteSource.USER_ACTION -> {
                    backgroundPendingWrites.remove(task.animeId)
                    userPendingWrites[task.animeId] = mergePendingWrite(userPendingWrites[task.animeId], task)
                    true
                }
                WriteSource.PLAYBACK_SYNC -> {
                    if (userPendingWrites.containsKey(task.animeId)) {
                        false
                    } else {
                        backgroundPendingWrites[task.animeId] = mergePendingWrite(backgroundPendingWrites[task.animeId], task)
                        true
                    }
                }
            }
        }
        if (changed) {
            queueSignal.trySend(Unit)
        }
    }

    private suspend fun resolveCurrentStatus(task: QueuedStatusWrite): BangumiCollectionStatus? {
        return when (task.source) {
            WriteSource.USER_ACTION -> {
                cachedCollectionStatus(task.animeId)
                    ?: runCatching { fetchCollectionStatus(task.animeId, task.title) }.getOrNull()
            }
            WriteSource.PLAYBACK_SYNC -> {
                runCatching { fetchCollectionStatus(task.animeId, task.title) }.getOrNull()
                    ?: cachedCollectionStatus(task.animeId)
            }
        }
    }

    private suspend fun nextQueuedWrite(): QueuedStatusWrite {
        while (true) {
            synchronized(queueLock) {
                takeFirstPending(userPendingWrites)?.let { return it }
                takeFirstPending(backgroundPendingWrites)?.let { return it }
            }
            queueSignal.receive()
        }
    }

    private fun takeFirstPending(queue: LinkedHashMap<Long, QueuedStatusWrite>): QueuedStatusWrite? {
        val iterator = queue.entries.iterator()
        if (!iterator.hasNext()) return null
        val entry = iterator.next()
        iterator.remove()
        return entry.value
    }

    private fun mergePendingWrite(
        existing: QueuedStatusWrite?,
        incoming: QueuedStatusWrite,
    ): QueuedStatusWrite {
        if (existing == null) return incoming
        if (existing.source != incoming.source) {
            return if (incoming.source == WriteSource.USER_ACTION) incoming else existing
        }
        if (incoming.source == WriteSource.USER_ACTION) {
            return incoming
        }
        if (existing.status == BangumiCollectionStatus.COLLECT || incoming.status == BangumiCollectionStatus.COLLECT) {
            return incoming.copy(
                title = incoming.title.ifBlank { existing.title },
                status = BangumiCollectionStatus.COLLECT,
                mode = WriteMode.UPSERT,
            )
        }
        return incoming.copy(title = incoming.title.ifBlank { existing.title })
    }

    private fun shouldWrite(
        current: BangumiCollectionStatus?,
        task: QueuedStatusWrite,
    ): Boolean {
        if (current == task.status) return false
        if (task.source == WriteSource.PLAYBACK_SYNC) {
            if (current == BangumiCollectionStatus.COLLECT ||
                current == BangumiCollectionStatus.ON_HOLD ||
                current == BangumiCollectionStatus.DROPPED
            ) {
                return false
            }
            if (task.mode == WriteMode.PLAYBACK_STARTED) {
                return current == null || current == BangumiCollectionStatus.WISH
            }
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
        executeAuthenticatedTextRequest(
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
        val response = executeAuthenticatedTextRequest(
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
        val subjectId = repository.ensureBangumiSubjectId(animeId, title) ?: return null
        if (subjectId > 0) {
            store.saveSubjectMatch(subjectId, animeId)
            val current = store.getCollectionStatus(animeId)
            store.saveCollectionStatus(
                animeId,
                BangumiCollectionCacheEntry(
                    subjectId = subjectId,
                    status = current?.status.orEmpty(),
                    updatedAtMs = current?.updatedAtMs ?: 0L,
                ),
            )
        }
        return subjectId.takeIf { it > 0 }
    }

    private suspend fun refreshStoredAccount(session: BangumiAccountSession): BangumiAccountSession {
        val response = executeAuthenticatedTextRequest(
            url = SETTINGS_URL,
            cookies = session.cookies,
        )
        val mergedCookies = session.cookies.toMutableMap().apply { putAll(response.cookies) }
        val refreshed = parseAccount(response.body, mergedCookies)?.copy(
            lastValidatedMs = System.currentTimeMillis(),
        ) ?: run {
            clearInvalidSession()
            throw BangumiSessionExpiredException()
        }
        store.saveSession(refreshed)
        _account.value = refreshed
        return refreshed
    }

    private fun verifyAuthenticatedAccount(cookies: Map<String, String>): BangumiAccountSession? {
        return runCatching {
            val response = executeTextRequest(
                url = SETTINGS_URL,
                cookies = cookies,
                referer = LOGIN_URL,
            )
            if (looksLikeExpiredSession(response)) {
                null
            } else {
                val mergedCookies = cookies.toMutableMap().apply { putAll(response.cookies) }
                parseAccount(response.body, mergedCookies)?.copy(
                    lastValidatedMs = System.currentTimeMillis(),
                )
            }
        }.getOrNull()
    }

    private fun parseAccount(body: String, cookies: Map<String, String>): BangumiAccountSession? {
        val document = Jsoup.parse(body)
        val username = extractAuthenticatedUsername(body) ?: return null
        val displayName = extractDisplayName(document, username)
        val avatarUrl = extractAvatarUrl(document, username)
        return BangumiAccountSession(
            username = username,
            displayName = displayName,
            avatarUrl = avatarUrl,
            cookies = cookies,
            lastValidatedMs = System.currentTimeMillis(),
        )
    }

    private fun extractLoginFailureMessage(body: String): String? {
        val document = Jsoup.parse(body)
        return document.selectFirst("#colunmNotice .message .text, #columnNotice .message .text, .message .text")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractAuthenticatedUsername(body: String): String? {
        val uid = Regex("""CHOBITS_UID\s*=\s*(\d+)""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?: return null
        if (uid <= 0L) return null
        return Regex("""CHOBITS_USERNAME\s*=\s*'([^']*)'""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractDisplayName(document: org.jsoup.nodes.Document, username: String): String {
        document.selectFirst("input[name=nickname]")?.attr("value")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        document.selectFirst("#dock a[href*=/user/$username] span.ico_home")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        document.selectFirst("h1 a[href*=/user/$username]")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.endsWith("的个人设置") }
            ?.removeSuffix("的个人设置")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return username
    }

    private fun extractAvatarUrl(document: org.jsoup.nodes.Document, username: String): String {
        document.selectFirst("a.avatar[href*=/$username] img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val style = document.selectFirst("a.avatar[href*=/$username] span[style*=background-image]")
            ?.attr("style")
            .orEmpty()
        return Regex("""background-image:\s*url\(['"]?([^'")]+)""")
            .find(style)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun requireSessionCookies(): Map<String, String> {
        return _account.value?.cookies?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Bangumi 账号尚未登录")
    }

    private fun pruneExpiredChallenges() {
        val threshold = System.currentTimeMillis() - LOGIN_CHALLENGE_TTL_MS
        loginChallenges.entries.removeIf { it.value.createdAtMs < threshold }
    }

    private fun refreshChallenge(
        sessionId: String,
        fallback: LoginChallenge,
        cookies: Map<String, String>,
        loginPageHtml: String,
    ): LoginChallenge {
        val fields = parseLoginHiddenFields(loginPageHtml).takeIf { it.isNotEmpty() } ?: fallback.hiddenFields
        return LoginChallenge(
            sessionId = sessionId,
            cookies = cookies.toMutableMap(),
            hiddenFields = fields,
            createdAtMs = System.currentTimeMillis(),
            lastCaptchaToken = fallback.lastCaptchaToken,
        )
    }

    private fun parseLoginHiddenFields(body: String): Map<String, String> {
        val form = Jsoup.parse(body).selectFirst("form[action*=FollowTheRabbit]") ?: return emptyMap()
        return buildMap {
            form.select("input[name]").forEach { input ->
                put(input.attr("name"), input.attr("value"))
            }
        }
    }

    private fun executeAuthenticatedTextRequest(
        url: String,
        cookies: Map<String, String>,
        formFields: Map<String, String>? = null,
    ): TextResponse {
        val response = executeTextRequest(
            url = url,
            cookies = cookies,
            formFields = formFields,
        )
        if (looksLikeExpiredSession(response)) {
            clearInvalidSession()
            throw BangumiSessionExpiredException()
        }
        updatePersistedSession(extraCookies = response.cookies)
        return response
    }

    private fun executeTextRequest(
        url: String,
        cookies: Map<String, String> = emptyMap(),
        formFields: Map<String, String>? = null,
        referer: String = BASE_URL,
        origin: String? = null,
    ): TextResponse {
        val cookieJar = cookies.toMutableMap()
        var currentUrl = url
        var currentMethod = if (formFields != null) "POST" else "GET"
        var currentFormFields = formFields
        repeat(MAX_REDIRECTS) {
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
            if (cookieJar.isNotEmpty()) {
                requestBuilder.header("Cookie", buildCookieHeader(cookieJar))
            }
            if (currentMethod == "POST" && currentFormFields != null) {
                origin?.let { requestBuilder.header("Origin", it) }
                val requestFields = currentFormFields ?: emptyMap()
                val body = FormBody.Builder().apply {
                    requestFields.forEach { (key, value) -> add(key, value) }
                }.build()
                requestBuilder.post(body)
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                cookieJar.putAll(parseSetCookies(response.headers))
                if (response.isRedirect) {
                    val location = response.header("Location")
                        ?: throw IOException("Bangumi 重定向缺少目标地址")
                    currentUrl = absoluteUrl(response.request.url.toString(), location)
                    if (response.code == 303 || response.code == 301 || response.code == 302) {
                        currentMethod = "GET"
                        currentFormFields = null
                    }
                    return@repeat
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("Bangumi 请求失败 ${response.code}")
                }
                return TextResponse(
                    body = body,
                    cookies = cookieJar.toMap(),
                    finalUrl = response.request.url.toString(),
                )
            }
        }
        throw IOException("Bangumi 请求重定向次数过多")
    }

    private fun executeBinaryRequest(
        url: String,
        cookies: Map<String, String> = emptyMap(),
        referer: String = LOGIN_URL,
    ): BinaryResponse {
        val cookieJar = cookies.toMutableMap()
        var currentUrl = url
        repeat(MAX_REDIRECTS) {
            val requestBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
            if (cookieJar.isNotEmpty()) {
                requestBuilder.header("Cookie", buildCookieHeader(cookieJar))
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                cookieJar.putAll(parseSetCookies(response.headers))
                if (response.isRedirect) {
                    val location = response.header("Location")
                        ?: throw IOException("验证码请求重定向缺少目标地址")
                    currentUrl = absoluteUrl(response.request.url.toString(), location)
                    return@repeat
                }
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) {
                    throw IOException("验证码加载失败 ${response.code}")
                }
                return BinaryResponse(
                    bytes = bytes,
                    contentType = response.header("Content-Type").orEmpty(),
                    cookies = cookieJar.toMap(),
                )
            }
        }
        throw IOException("验证码请求重定向次数过多")
    }

    private fun looksLikeExpiredSession(response: TextResponse): Boolean {
        if (response.finalUrl.contains("/login")) return true
        val body = response.body
        if (body.contains("self.parent.tb_remove()") && body.contains("window.parent.location.href='/'")) {
            return true
        }
        val document = Jsoup.parse(body)
        val hasAuthenticatedMarkers =
            extractAuthenticatedUsername(body) != null || document.selectFirst("input[name=nickname]") != null
        val dockLogin = document.selectFirst(
            "#dock a[href*=login], #dock a[href$=/login], #dock a[href='https://bgm.tv/login']",
        ) != null
        val guestHeader = document.selectFirst(".idBadgerNeue .guest a[href*=login]") != null
        val loginRequiredNotice = document.selectFirst("#colunmNotice .message .text a[href=/login]") != null ||
            body.contains("当前操作需要您") && body.contains("/login")
        return (guestHeader && loginRequiredNotice) || (dockLogin && !hasAuthenticatedMarkers)
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
        baseUrl.toHttpUrlOrNull()?.resolve(raw)?.toString()?.let { return it }
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
        val lastCaptchaToken: Long = 0L,
    )

    private data class TextResponse(
        val body: String,
        val cookies: Map<String, String>,
        val finalUrl: String,
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

    private enum class WriteSource {
        USER_ACTION,
        PLAYBACK_SYNC,
    }

    private data class QueuedStatusWrite(
        val animeId: Long,
        val title: String,
        val status: BangumiCollectionStatus,
        val mode: WriteMode,
        val source: WriteSource,
    )

    private class BangumiSessionExpiredException : IOException("Bangumi 登录状态已失效，请重新扫码")

    companion object {
        private const val BASE_URL = "https://bgm.tv/"
        private const val LOGIN_URL = "${BASE_URL}login"
        private const val SETTINGS_URL = "${BASE_URL}settings"
        private const val CAPTCHA_URL = "${BASE_URL}signup/captcha"
        private const val FOLLOW_THE_RABBIT_URL = "${BASE_URL}FollowTheRabbit"
        private const val LOGIN_CHALLENGE_TTL_MS = 10 * 60_000L
        private const val MAX_REDIRECTS = 6
        private const val MAX_BACKGROUND_SYNC_RECORDS = 20
        private const val TAG = "BangumiAccountService"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; Google TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
    }
}
