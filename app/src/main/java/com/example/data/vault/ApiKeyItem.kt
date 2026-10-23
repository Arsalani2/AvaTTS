package com.example.data.vault

import org.json.JSONObject

enum class KeyStatus {
    HEALTHY,
    COOLDOWN,
    INVALID
}

data class ApiKeyItem(
    val id: String,
    val name: String,
    val encryptedKey: String,
    val maskedKey: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
    val successCount: Int = 0,
    val errorCount: Int = 0,
    val cooldownUntil: Long = 0L,
    val status: KeyStatus = KeyStatus.HEALTHY
) {
    val isCooldownActive: Boolean
        get() = cooldownUntil > System.currentTimeMillis()

    val remainingCooldownSeconds: Long
        get() {
            val remainingMs = cooldownUntil - System.currentTimeMillis()
            return if (remainingMs > 0) (remainingMs + 999) / 1000 else 0L
        }

    val currentStatus: KeyStatus
        get() = if (!isEnabled) {
            KeyStatus.INVALID
        } else if (isCooldownActive) {
            KeyStatus.COOLDOWN
        } else {
            KeyStatus.HEALTHY
        }

    val stabilityScore: Double
        get() {
            val total = successCount + errorCount
            if (total == 0) return 1.0
            return (successCount.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
        }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("encryptedKey", encryptedKey)
            put("maskedKey", maskedKey)
            put("isEnabled", isEnabled)
            put("createdAt", createdAt)
            put("lastUsedAt", lastUsedAt ?: 0L)
            put("successCount", successCount)
            put("errorCount", errorCount)
            put("cooldownUntil", cooldownUntil)
            put("status", status.name)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ApiKeyItem {
            val lastUsed = json.optLong("lastUsedAt", 0L)
            return ApiKeyItem(
                id = json.getString("id"),
                name = json.getString("name"),
                encryptedKey = json.getString("encryptedKey"),
                maskedKey = json.getString("maskedKey"),
                isEnabled = json.optBoolean("isEnabled", true),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastUsedAt = if (lastUsed > 0) lastUsed else null,
                successCount = json.optInt("successCount", 0),
                errorCount = json.optInt("errorCount", 0),
                cooldownUntil = json.optLong("cooldownUntil", 0L),
                status = try {
                    KeyStatus.valueOf(json.optString("status", KeyStatus.HEALTHY.name))
                } catch (e: Exception) {
                    KeyStatus.HEALTHY
                }
            )
        }

        fun maskKey(rawKey: String): String {
            val trimmed = rawKey.trim()
            if (trimmed.length <= 6) {
                return if (trimmed.length <= 2) "****" else "${trimmed.first()}****${trimmed.last()}"
            }
            val prefixLen = if (trimmed.length >= 12) 4 else 2
            val suffixLen = if (trimmed.length >= 12) 4 else 2
            val prefix = trimmed.take(prefixLen)
            val suffix = trimmed.takeLast(suffixLen)
            return "$prefix****$suffix"
        }
    }
}
