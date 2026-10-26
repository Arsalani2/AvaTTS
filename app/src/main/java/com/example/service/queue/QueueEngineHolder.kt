package com.example.service.queue

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.pref.UserPreferences
import com.example.data.repository.AppRepository
import com.example.data.vault.ApiKeyVaultManager
import com.example.service.tts.GeminiTtsService
import com.example.util.FileManager

/**
 * BUG-01: مالکیت صف از چرخهٔ حیات Activity/ViewModel جدا شد.
 *
 * این holder در سطح اپ زنده می‌ماند و همان نمونهٔ واحد TtsQueueEngine و وابستگی‌های
 * stateful آن (گاوصندوق کلیدها و تنظیمات) را به همهٔ ViewModelها می‌دهد؛ بنابراین بستن اپ
 * از Recents پردازش صف را نمی‌کشد و بازگشایی اپ، state زندهٔ همان صف را نشان می‌دهد.
 *
 * لغو صف فقط از این سه مسیر مجاز است: دکمهٔ توقف کاربر در اپ، اکشن «توقف صف» اعلان سرویس،
 * یا پایان طبیعی صف. onCleared ویو‌مدل دیگر صف را کانسل نمی‌کند.
 */
object QueueEngineHolder {

    @Volatile
    private var engine: TtsQueueEngine? = null

    @Volatile
    private var vaultManager: ApiKeyVaultManager? = null

    @Volatile
    private var userPreferences: UserPreferences? = null

    /** نمونهٔ واحد گاوصندوق کلیدها (stateful: کول‌داون‌ها و آمار در حافظه) */
    fun vaultManager(application: Application): ApiKeyVaultManager {
        return vaultManager ?: synchronized(this) {
            vaultManager ?: ApiKeyVaultManager(application).also { vaultManager = it }
        }
    }

    /** نمونهٔ واحد تنظیمات (StateFlow زنده) */
    fun userPreferences(application: Application): UserPreferences {
        return userPreferences ?: synchronized(this) {
            userPreferences ?: UserPreferences(application).also { userPreferences = it }
        }
    }

    /** نمونهٔ واحد صف تبدیل — پردازش پس‌زمینه مستقل از چرخهٔ حیات اکتیویتی */
    fun engine(application: Application): TtsQueueEngine {
        return engine ?: synchronized(this) {
            engine ?: createEngine(application).also { engine = it }
        }
    }

    /** دسترسی به engine بدون ساخت — برای اکشن «توقف صف» از اعلان سرویس */
    fun peek(): TtsQueueEngine? = engine

    private fun createEngine(application: Application): TtsQueueEngine {
        val db = AppDatabase.getInstance(application)
        val repository = AppRepository(db.projectDao(), db.chunkDao(), db.conversionLogDao())
        val prefs = userPreferences(application)
        val vault = vaultManager(application)
        val fileManager = FileManager(application, prefs)
        val ttsService = GeminiTtsService()
        return TtsQueueEngine(
            context = application,
            repository = repository,
            chunkDao = db.chunkDao(),
            vaultManager = vault,
            ttsService = ttsService,
            fileManager = fileManager,
            userPreferences = prefs
        )
    }
}
