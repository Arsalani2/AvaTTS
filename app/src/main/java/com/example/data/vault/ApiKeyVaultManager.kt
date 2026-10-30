package com.example.data.vault

import android.content.Context
import android.content.SharedPreferences
import com.example.security.KeystoreHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ApiKeyVaultManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ava_vault_secure_store_v1", Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow<List<ApiKeyItem>>(emptyList())
    val keys: StateFlow<List<ApiKeyItem>> = _keys.asStateFlow()

    // Global rotation counter ensuring circular round-robin distribution across all requests
    // (both single chunk and queue conversions!)
    private val rotationCounter = AtomicInteger(0)

    // BUG-28: streak های 429 برای هر کلید — خنک‌سازی افزایشی 60s → 120s → 240s
    private val quotaLimitStreaks = mutableMapOf<String, Int>()

    init {
        loadKeys()
        // BUG-22: seed کلید از BuildConfig کامل حذف شد — BuildConfig داخل dex در APK
        // حضور دارد و با apktool استخراج می‌شود؛ گاوصندوق خودش برای کلید هست و
        // .env/.env.example فقط راهنمای توسعه‌دهنده است (کلید واقعی هرگز کامپایل نشود).
    }

    @Synchronized
    private fun loadKeys() {
        val jsonString = prefs.getString("keys_json_array", null) ?: "[]"
        val list = mutableListOf<ApiKeyItem>()
        try {
            val arr = JSONArray(jsonString)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(ApiKeyItem.fromJson(obj))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sanitize: ensure all enabled keys have clean status (never locked in permanent INVALID state)
        val sanitized = list.map { item ->
            if (item.isEnabled) {
                item.copy(status = if (item.isCooldownActive) KeyStatus.COOLDOWN else KeyStatus.HEALTHY)
            } else {
                item.copy(status = KeyStatus.INVALID)
            }
        }
        _keys.value = sanitized
    }

    @Synchronized
    private fun persistKeys(list: List<ApiKeyItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("keys_json_array", arr.toString()).apply()
        _keys.value = list
    }

    @Synchronized
    // BUG-07 (تکمیل ۲۰۲۶-۱۰-۲۹): در شکست KeystoreHelper کلید خام ذخیره نمی‌شود — null = خطای رمزنگاری
    fun addKey(name: String, rawKey: String): ApiKeyItem? {
        val trimmedKey = rawKey.trim()
        val encrypted = KeystoreHelper.encrypt(trimmedKey)
        if (encrypted.isBlank()) return null
        val masked = ApiKeyItem.maskKey(trimmedKey)
        val newItem = ApiKeyItem(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "کلید جدید" },
            encryptedKey = encrypted,
            maskedKey = masked,
            isEnabled = true,
            status = KeyStatus.HEALTHY,
            cooldownUntil = 0L,
            createdAt = System.currentTimeMillis()
        )
        val updated = _keys.value + newItem
        persistKeys(updated)
        return newItem
    }

    @Synchronized
    fun updateKey(id: String, newName: String, newRawKey: String?): Boolean {
        val current = _keys.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return false

        val old = current[index]
        val updated = if (!newRawKey.isNullOrBlank()) {
            val trimmed = newRawKey.trim()
            // BUG-07 (تکمیل ۲۰۲۶-۱۰-۲۹): شکست رمزنگاری = رد کامل؛ fallback خام حذف شد —
            // کلید Plaintext هرگز در SharedPreferences نوشته نمی‌شود
            val encrypted = KeystoreHelper.encrypt(trimmed)
            if (encrypted.isBlank()) return false
            old.copy(
                name = newName.ifBlank { old.name },
                encryptedKey = encrypted,
                maskedKey = ApiKeyItem.maskKey(trimmed),
                status = KeyStatus.HEALTHY,
                cooldownUntil = 0L
            )
        } else {
            // BUG-21: تغییر فقط نام، وضعیت/کول‌داون کلید را صفر نمی‌کند
            old.copy(name = newName.ifBlank { old.name })
        }
        current[index] = updated
        persistKeys(current)
        return true
    }

    @Synchronized
    fun deleteKey(id: String): Boolean {
        val current = _keys.value.filterNot { it.id == id }
        persistKeys(current)
        return true
    }

    @Synchronized
    fun getDecryptedKey(id: String): String? {
        val item = _keys.value.firstOrNull { it.id == id } ?: return null
        val decrypted = KeystoreHelper.decrypt(item.encryptedKey)
        if (decrypted.isNotBlank()) return decrypted
        if (item.encryptedKey.startsWith("AIzaSy")) {
            // BUG-07 (تکمیل): self-heal — کلید قدیمی خام یک‌بار رمزنگاری و در prefs جایگزین می‌شود
            val healed = KeystoreHelper.encrypt(item.encryptedKey)
            if (healed.isNotBlank()) {
                val cur = _keys.value.toMutableList()
                val idx = cur.indexOfFirst { it.id == id }
                if (idx != -1) {
                    cur[idx] = cur[idx].copy(encryptedKey = healed)
                    persistKeys(cur)
                }
            }
            return item.encryptedKey
        }
        return null
    }

    @Synchronized
    fun toggleKeyEnabled(id: String): Boolean {
        val current = _keys.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return false
        val item = current[index]
        val newEnabled = !item.isEnabled
        current[index] = item.copy(
            isEnabled = newEnabled,
            status = if (newEnabled) KeyStatus.HEALTHY else KeyStatus.INVALID,
            cooldownUntil = if (newEnabled) 0L else item.cooldownUntil
        )
        persistKeys(current)
        return true
    }

    /**
     * Circular round-robin key selection advancing the global rotation counter.
     * Ensures EVERY conversion (even individual single chunk) cycles circularly through all keys!
     */
    @Synchronized
    fun selectNextRoundRobinKey(excludeKeyIds: Set<String> = emptySet()): ApiKeyItem? {
        val nextIdx = rotationCounter.getAndIncrement()
        return selectRoundRobinKey(roundIndex = nextIdx, excludeKeyIds = excludeKeyIds)
    }

    /**
     * Round-robin rotational key selection:
     * Selects circularly among enabled keys to distribute requests evenly
     * and give each key adequate cooling time between requests.
     */
    @Synchronized
    fun selectRoundRobinKey(roundIndex: Int, excludeKeyIds: Set<String> = emptySet()): ApiKeyItem? {
        val now = System.currentTimeMillis()
        val allEnabled = _keys.value.filter { it.isEnabled }
        if (allEnabled.isEmpty()) return null

        val nonExcluded = allEnabled.filter { it.id !in excludeKeyIds }
        val candidates = if (nonExcluded.isNotEmpty()) nonExcluded else allEnabled

        // BUG-28: فقط کلیدهای سالمِ خارج از کول‌داون؛ کلید در حال خنک‌سازی تحویل موتور صف نمی‌شود
        val healthy = candidates.filter { it.cooldownUntil <= now }
        if (healthy.isEmpty()) return null

        // Stable ordering by createdAt so round robin rotates systematically
        val sorted = healthy.sortedBy { it.createdAt }
        val safeIndex = Math.floorMod(roundIndex, sorted.size)
        return sorted[safeIndex]
    }

    /**
     * Dynamic key selection prioritizing healthy and stable keys.
     */
    @Synchronized
    fun selectOptimalKey(excludeKeyIds: Set<String> = emptySet()): ApiKeyItem? {
        val now = System.currentTimeMillis()
        // BUG-28: فقط کلیدهای سالمِ خارج از کول‌داون — fallback «نزدیک‌ترین انقضا» حذف شد؛
        // در نبودِ کلید سالم null برمی‌گردد و gate کول‌داونِ صف مسئول انتظار است
        val healthy = _keys.value.filter { it.isEnabled && it.id !in excludeKeyIds && it.cooldownUntil <= now }
        if (healthy.isEmpty()) return null
        return healthy.sortedWith(
            compareByDescending<ApiKeyItem> { it.stabilityScore }
                .thenBy { it.lastUsedAt ?: 0L }
        ).first()
    }

    /**
     * Returns true if there are enabled keys, but ALL of them are currently in cooldown.
     */
    @Synchronized
    fun areAllEnabledKeysInCooldown(): Boolean {
        val now = System.currentTimeMillis()
        val enabled = _keys.value.filter { it.isEnabled }
        if (enabled.isEmpty()) return false
        return enabled.all { it.cooldownUntil > now }
    }

    /**
     * Returns the minimum remaining cooldown time in milliseconds among cooling keys.
     */
    @Synchronized
    fun getMinRemainingCooldownMs(): Long {
        val now = System.currentTimeMillis()
        val cooling = _keys.value.filter { it.isEnabled && it.cooldownUntil > now }
        if (cooling.isEmpty()) return 0L
        val minExpiry = cooling.minOf { it.cooldownUntil }
        return (minExpiry - now).coerceAtLeast(0L)
    }

    fun hasAnyKey(): Boolean = _keys.value.isNotEmpty()

    fun hasAnyEnabledKey(): Boolean = _keys.value.any { it.isEnabled }

    @Synchronized
    fun recordSuccess(id: String) {
        val current = _keys.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = current[index]
            // BUG-28: موفقیت، streak خنک‌سازی 429 را ری‌ست می‌کند
            quotaLimitStreaks.remove(item.id)
            current[index] = item.copy(
                lastUsedAt = System.currentTimeMillis(),
                successCount = item.successCount + 1,
                status = KeyStatus.HEALTHY,
                cooldownUntil = 0L
            )
            persistKeys(current)
        }
    }

    @Synchronized
    fun recordError(id: String, isQuotaLimit: Boolean, isPermanentInvalid: Boolean = false) {
        val current = _keys.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = current[index]
            val now = System.currentTimeMillis()
            // Never permanently lock enabled keys into INVALID status.
            // BUG-28: خنک‌سازی 429 افزایشی است — 60s → 120s → 240s (سقف 240s)؛
            // خطای غیر-سهمیه 25s ثابت. streak فقط با موفقیت ری‌ست می‌شود.
            val newCooldown = if (isQuotaLimit) {
                val streak = (quotaLimitStreaks[item.id] ?: 0) + 1
                quotaLimitStreaks[item.id] = streak
                val multiplier = 1L shl (kotlin.math.min(streak - 1, 2))
                now + 60_000L * multiplier
            } else now + 25_000L

            current[index] = item.copy(
                lastUsedAt = now,
                errorCount = item.errorCount + 1,
                cooldownUntil = newCooldown,
                status = KeyStatus.COOLDOWN
            )
            persistKeys(current)
        }
    }

    @Synchronized
    fun resetCooldown(id: String) {
        val current = _keys.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            val item = current[index]
            // BUG-28: لغو دستی خنک‌سازی، streak 429 را هم ری‌ست می‌کند
            quotaLimitStreaks.remove(item.id)
            current[index] = item.copy(
                cooldownUntil = 0L,
                status = if (item.isEnabled) KeyStatus.HEALTHY else KeyStatus.INVALID
            )
            persistKeys(current)
        }
    }

    @Synchronized
    fun resetAllCooldowns() {
        val current = _keys.value.toMutableList()
        // BUG-28: ری‌ست کامل، همهٔ streakهای 429 هم پاک می‌کند
        quotaLimitStreaks.clear()
        for (i in 0 until current.size) {
            val item = current[i]
            current[i] = item.copy(
                cooldownUntil = 0L,
                status = if (item.isEnabled) KeyStatus.HEALTHY else KeyStatus.INVALID
            )
        }
        persistKeys(current)
    }

    // BUG-23: شمارش کلیدهایی که پس از بکاپ/انتقال به دستگاه دیگر قابل رمزگشایی نیستند
    @Synchronized
    fun detectUndecryptableEntries(): Int {
        return _keys.value.count { item ->
            item.encryptedKey.isNotBlank() && KeystoreHelper.decrypt(item.encryptedKey).isBlank()
        }
    }
}
