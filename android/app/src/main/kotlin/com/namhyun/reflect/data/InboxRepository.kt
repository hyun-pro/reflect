package com.namhyun.reflect.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room 대신 SharedPreferences 기반 단순 저장소.
 * Inbox 항목 최대 200개 유지, 7일 이상은 자동 삭제.
 * 모든 변경사항은 StateFlow 로 즉시 UI 반영.
 */
object InboxRepository {

    private const val PREFS = "reflect_inbox"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 200
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow<List<InboxEntity>>(emptyList())
    private var loaded = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val raw = runCatching { prefs(context).getString(KEY_ITEMS, "[]") ?: "[]" }
                .getOrDefault("[]")
            val list = runCatching { json.decodeFromString<List<InboxEntity>>(raw) }
                .getOrDefault(emptyList())
            _items.value = pruneList(list)
            loaded = true
        }
    }

    private fun pruneList(list: List<InboxEntity>): List<InboxEntity> {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return list.filter { it.arrivedAt > cutoff }
            .sortedByDescending { it.arrivedAt }
            .take(MAX_ITEMS)
    }

    private fun persist(context: Context, list: List<InboxEntity>) {
        runCatching {
            val raw = json.encodeToString(list)
            prefs(context).edit().putString(KEY_ITEMS, raw).apply()
        }
    }

    fun observePending(context: Context): StateFlow<List<InboxEntity>> {
        ensureLoaded(context)
        return _items.asStateFlow()
    }

    fun observeAll(context: Context): StateFlow<List<InboxEntity>> = observePending(context)

    fun pendingCount(context: Context): Int {
        ensureLoaded(context)
        return _items.value.count { !it.handled }
    }

    /**
     * 같은 앱의 가장 최근 미처리 inbox 항목. DPO 페어 매핑용.
     * @param maxAgeMs 0이면 무제한, 양수면 maxAgeMs 이내만.
     */
    fun findMostRecentPending(context: Context, app: String, maxAgeMs: Long): InboxEntity? {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        return _items.value
            .asSequence()
            .filter { !it.handled && it.app == app }
            .filter { maxAgeMs <= 0 || now - it.arrivedAt <= maxAgeMs }
            .maxByOrNull { it.arrivedAt }
    }

    suspend fun add(
        context: Context,
        app: String,
        contact: String,
        incoming: String,
        suggestions: List<String>,
        originalKey: String,
        originalPkg: String,
    ): Long {
        ensureLoaded(context)
        val newItem = InboxEntity(
            id = System.currentTimeMillis(),
            app = app,
            contact = contact,
            incoming = incoming,
            suggestionsJson = runCatching { json.encodeToString(suggestions) }.getOrDefault("[]"),
            originalKey = originalKey,
            originalPkg = originalPkg,
        )
        synchronized(this) {
            val updated = pruneList(listOf(newItem) + _items.value)
            _items.value = updated
            persist(context, updated)
        }
        return newItem.id
    }

    suspend fun markHandled(context: Context, id: Long) {
        ensureLoaded(context)
        synchronized(this) {
            val updated = _items.value.map {
                if (it.id == id) it.copy(handled = true) else it
            }
            _items.value = updated
            persist(context, updated)
        }
    }

    suspend fun delete(context: Context, id: Long) {
        ensureLoaded(context)
        synchronized(this) {
            val updated = _items.value.filterNot { it.id == id }
            _items.value = updated
            persist(context, updated)
        }
    }

    fun parseSuggestions(jsonStr: String): List<String> = runCatching {
        json.decodeFromString<List<String>>(jsonStr)
    }.getOrDefault(emptyList())

    suspend fun markGroupHandled(context: Context, ids: List<Long>) {
        ensureLoaded(context)
        val idSet = ids.toSet()
        synchronized(this) {
            val updated = _items.value.map {
                if (it.id in idSet) it.copy(handled = true) else it
            }
            _items.value = updated
            persist(context, updated)
        }
    }
}

data class InboxGroup(
    val app: String,
    val contact: String,
    val latest: InboxEntity,
    val count: Int,
    val ids: List<Long>,
)

fun List<InboxEntity>.toGroups(query: String = ""): List<InboxGroup> {
    val q = query.trim().lowercase()
    return filter { !it.handled }
        .filter { item ->
            if (q.isEmpty()) true
            else item.contact.lowercase().contains(q) || item.incoming.lowercase().contains(q)
        }
        .groupBy { it.contact + "|" + it.app }
        .map { (_, items) ->
            val sorted = items.sortedByDescending { it.arrivedAt }
            val latest = sorted.first()
            InboxGroup(
                app = latest.app,
                contact = latest.contact,
                latest = latest,
                count = items.size,
                ids = items.map { it.id },
            )
        }
        .sortedByDescending { it.latest.arrivedAt }
}
