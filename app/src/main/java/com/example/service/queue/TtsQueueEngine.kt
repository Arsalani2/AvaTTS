package com.example.service.queue

import android.content.Context
import com.example.data.db.ChunkDao
import com.example.data.db.ChunkEntity
import com.example.data.db.ProjectEntity
import com.example.data.pref.UserPreferences
import com.example.data.repository.AppRepository
import com.example.data.vault.ApiKeyVaultManager
import com.example.service.tts.GeminiTtsService
import com.example.service.tts.TtsInvalidKeyException
import com.example.service.tts.TtsNetworkException
import com.example.service.tts.TtsQuotaExceededException
import com.example.util.FileManager
import com.example.util.WavAudioUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QueueState(
    val isRunning: Boolean = false,
    val activeChunkId: Long? = null,
    val totalInQueue: Int = 0,
    val completedInQueue: Int = 0,
    val currentStatusMessage: String = "",
    val lastSwitchedKeyInfo: String? = null
)

class TtsQueueEngine(
    private val context: Context,
    private val repository: AppRepository,
    private val chunkDao: ChunkDao,
    private val vaultManager: ApiKeyVaultManager,
    private val ttsService: GeminiTtsService,
    private val fileManager: FileManager,
    private val userPreferences: UserPreferences
) {
    // BUG-01: scope صف مستقل از چرخهٔ حیات ViewModel/Activity — SupervisorJob تا شکست یک چانک بقیه را نکشد
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var queueJob: Job? = null

    private val _queueState = MutableStateFlow(QueueState())
    val queueState: StateFlow<QueueState> = _queueState.asStateFlow()

    fun startQueue(
        project: ProjectEntity,
        chunkIds: List<Long>,
        onChunkCompleted: ((Long) -> Unit)? = null
    ) {
        if (chunkIds.isEmpty()) return
        // BUG-05: استارت دوباره در حین اجرای صف رد می‌شود — قبلاً queueJob?.cancel()
        // بی‌صدا کارِ قبل را می‌کشت، بخشِ PROCESSING همان‌طور گیر می‌کرد و هیچ پیامی هم نمی‌داد
        if (_queueState.value.isRunning) {
            _queueState.value = _queueState.value.copy(
                currentStatusMessage = "صف دیگری در حال اجراست؛ ابتدا صف جاری را متوقف کنید."
            )
            return
        }

        queueJob = scope.launch {
            _queueState.value = QueueState(
                isRunning = true,
                totalInQueue = chunkIds.size,
                completedInQueue = 0,
                currentStatusMessage = "شروع پردازش صف..."
            )

            // Start background foreground service with notification
            // BUG-38: نام گویندهٔ واقعی پروژه به اعلان ارسال می‌شود (نه «گوگل» سخت‌کدشده)
            TtsQueueService.start(context, project.name, chunkIds.size, project.selectedVoice)

            var completedCount = 0
            var errorCount = 0
            var totalDownloadedBytes = 0L

            for ((loopIndex, chunkId) in chunkIds.withIndex()) {
                val chunk = chunkDao.getChunkById(chunkId) ?: continue

                // Add gradual pacing delay between requests to avoid rate limits (Google API throttling prevention)
                if (loopIndex > 0) {
                    _queueState.value = _queueState.value.copy(
                        currentStatusMessage = "خنک‌سازی و آماده‌سازی برای بخش ${chunk.chunkIndex}..."
                    )
                    delay(1800L)
                }

                _queueState.value = _queueState.value.copy(
                    activeChunkId = chunkId,
                    currentStatusMessage = "در حال پردازش بخش ${chunk.chunkIndex}..."
                )

                // Update notification
                TtsQueueService.updateProgress(
                    context = context,
                    projectName = project.name,
                    currentChunk = chunk.chunkIndex,
                    totalChunks = chunkIds.size,
                    completedChunks = completedCount,
                    keyName = "درحال انتخاب",
                    speakerName = project.selectedVoice,
                    downloadedBytes = totalDownloadedBytes,
                    errorCount = errorCount
                )

                // Mark as processing in DB
                repository.updateChunkStatus(
                    chunkId = chunkId,
                    status = ChunkEntity.STATUS_PROCESSING,
                    audioPath = null,
                    keyUsedMasked = null,
                    keyUsedName = null,
                    durationMs = 0L,
                    error = null
                )

                // Process chunk with round-robin rotational key selection & automatic failover
                val processSuccess = processChunkWithFailover(
                    project = project,
                    chunk = chunk,
                    totalInQueue = chunkIds.size,
                    completedSoFar = completedCount,
                    onBytesDownloaded = { bytes -> totalDownloadedBytes += bytes }
                )
                if (processSuccess) {
                    completedCount++
                    // Immediately deselect converted chunk
                    onChunkCompleted?.invoke(chunk.id)
                } else {
                    errorCount++
                }

                _queueState.value = _queueState.value.copy(
                    completedInQueue = completedCount
                )

                // Update notification progress
                TtsQueueService.updateProgress(
                    context = context,
                    projectName = project.name,
                    currentChunk = chunk.chunkIndex,
                    totalChunks = chunkIds.size,
                    completedChunks = completedCount,
                    keyName = "",
                    speakerName = project.selectedVoice,
                    downloadedBytes = totalDownloadedBytes,
                    errorCount = errorCount
                )
            }

            _queueState.value = QueueState(
                isRunning = false,
                activeChunkId = null,
                totalInQueue = chunkIds.size,
                completedInQueue = completedCount,
                currentStatusMessage = "پردازش صف پایان یافت ($completedCount از ${chunkIds.size} موفق)."
            )

            // Stop Foreground Service and display rich Completion Notification
            TtsQueueService.stop(context)
            TtsQueueService.showCompletionNotification(
                context = context,
                projectName = project.name,
                totalChunks = chunkIds.size,
                completedChunks = completedCount,
                errorCount = errorCount,
                speaker = project.selectedVoice
            )
        }
    }

    private suspend fun processChunkWithFailover(
        project: ProjectEntity,
        chunk: ChunkEntity,
        totalInQueue: Int,
        completedSoFar: Int,
        onBytesDownloaded: (Long) -> Unit = {}
    ): Boolean {
        val excludedKeyIds = mutableSetOf<String>()
        var attempt = 0
        val maxAttempts = (vaultManager.keys.value.size + 2).coerceAtLeast(3)
        // BUG-03: خطای شبکه جریمهٔ کلید نیست — بودجهٔ retry جدا از بودجهٔ تلاش کلیدها
        var networkRetry = 0
        val maxNetworkRetries = 4

        while (attempt < maxAttempts) {
            attempt++

            // Check if all enabled keys are currently cooling down
            if (vaultManager.areAllEnabledKeysInCooldown()) {
                while (vaultManager.areAllEnabledKeysInCooldown()) {
                    val remainingMs = vaultManager.getMinRemainingCooldownMs()
                    val remainingSec = ((remainingMs + 999) / 1000).toInt().coerceAtLeast(1)

                    val statusMsg = "تمام کلیدها در حال خنک‌سازی هستند. ادامه خودکار تا $remainingSec ثانیه دیگر..."
                    _queueState.value = _queueState.value.copy(currentStatusMessage = statusMsg)

                    TtsQueueService.updateProgress(
                        context = context,
                        projectName = project.name,
                        currentChunk = chunk.chunkIndex,
                        totalChunks = totalInQueue,
                        completedChunks = completedSoFar,
                        keyName = "خنک‌سازی ($remainingSec ثانیه)",
                        speakerName = project.selectedVoice,
                        cooldownSec = remainingSec
                    )

                    delay(1000L)
                }
                // Cooldown ended or was cancelled by user in Vault!
                excludedKeyIds.clear()
            }

            // Rotational round-robin on first attempt, optimal failover on retry
            val candidateKey = if (attempt == 1) {
                vaultManager.selectNextRoundRobinKey(excludedKeyIds)
                    ?: vaultManager.selectOptimalKey(excludedKeyIds)
            } else {
                vaultManager.selectOptimalKey(excludedKeyIds)
            }

            if (candidateKey == null) {
                // If excludedKeyIds filtered out all keys, try resetting excluded and retry once
                if (excludedKeyIds.isNotEmpty()) {
                    excludedKeyIds.clear()
                    continue
                }

                val errorMsg = when {
                    !vaultManager.hasAnyKey() -> "هیچ کلیدی در گاوصندوق ثبت نشده است. لطفاً کلید API اضافه نمایید."
                    !vaultManager.hasAnyEnabledKey() -> "همه کلیدها در گاوصندوق خاموش هستند. لطفاً در گاوصندوق کلیدها را روشن کنید."
                    else -> "کلیدهای فعال در حال خنک‌سازی هستند. لطفاً دکمه «لغو خنک‌سازی» را در تب گاوصندوق بزنید یا کمی صبر کنید."
                }

                repository.updateChunkStatus(
                    chunkId = chunk.id,
                    status = ChunkEntity.STATUS_FAILED,
                    audioPath = null,
                    keyUsedMasked = null,
                    keyUsedName = null,
                    durationMs = 0L,
                    error = errorMsg
                )
                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    keyName = "بدون کلید",
                    keyMasked = "---",
                    status = "ERROR",
                    httpCode = 0,
                    latencyMs = 0,
                    message = errorMsg
                )
                return false
            }

            val decryptedRawKey = vaultManager.getDecryptedKey(candidateKey.id)
            if (decryptedRawKey.isNullOrBlank()) {
                excludedKeyIds.add(candidateKey.id)
                continue
            }

            // Update notification with active key name
            TtsQueueService.updateProgress(
                context = context,
                projectName = project.name,
                currentChunk = chunk.chunkIndex,
                totalChunks = totalInQueue,
                completedChunks = completedSoFar,
                keyName = candidateKey.name,
                speakerName = project.selectedVoice
            )

            val startTime = System.currentTimeMillis()

            try {
                // Call Gemini TTS with selected model name from user preferences
                val activeModel = userPreferences.settings.value.ttsModelName
                val wavBytes = ttsService.synthesizeSpeech(
                    apiKey = decryptedRawKey,
                    text = chunk.text,
                    voiceName = project.selectedVoice,
                    modelName = activeModel
                )
                val latency = System.currentTimeMillis() - startTime
                val sizeBytes = wavBytes.size.toLong()
                onBytesDownloaded(sizeBytes)

                val sizeStr = if (sizeBytes >= 1024 * 1024) {
                    String.format(java.util.Locale.US, "%.2f مگابایت", sizeBytes.toDouble() / (1024 * 1024))
                } else {
                    "${sizeBytes / 1024} کیلوبایت"
                }

                // Log download from Google
                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    keyName = candidateKey.name,
                    keyMasked = candidateKey.maskedKey,
                    status = "DOWNLOAD",
                    httpCode = 200,
                    latencyMs = latency,
                    message = "بخش ${chunk.chunkIndex} از سرور گوگل دانلود شد به حجم $sizeStr",
                    filePath = null,
                    fileSize = sizeBytes
                )

                // Save audio file
                val outputFile = fileManager.getAudioOutputFile(
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    customRoot = project.folderName
                )
                val savedSuccessfully = try {
                    WavAudioUtil.saveBytesToFile(outputFile, wavBytes)
                    true
                } catch (e: Exception) {
                    false
                }

                if (savedSuccessfully) {
                    repository.logConversion(
                        projectId = project.id,
                        projectName = project.name,
                        chunkIndex = chunk.chunkIndex,
                        keyName = candidateKey.name,
                        keyMasked = candidateKey.maskedKey,
                        status = "SAVED",
                        httpCode = 200,
                        latencyMs = 0L,
                        message = "بخش ${chunk.chunkIndex} در مسیر ${outputFile.absolutePath} ذخیره شد",
                        filePath = outputFile.absolutePath,
                        fileSize = outputFile.length()
                    )
                } else {
                    // BUG-31: صدا از گوگل ساخته شد ولی ذخیره فایل شکست خورد — چانک هرگز COMPLETED نمی‌شود؛
                    // کلید سالم بود، پس نه recordError و نه recordSuccess (جریمه کلید اشتباه است).
                    repository.logConversion(
                        projectId = project.id,
                        projectName = project.name,
                        chunkIndex = chunk.chunkIndex,
                        keyName = candidateKey.name,
                        keyMasked = candidateKey.maskedKey,
                        status = "ERROR",
                        httpCode = 500,
                        latencyMs = 0L,
                        message = "بخش ${chunk.chunkIndex} در مسیر ${outputFile.absolutePath} ذخیره نشد (خطای حافظه). تولید صدا موفق بود ولی نوشتن فایل شکست خورد — حافظه پر است؟"
                    )
                    repository.updateChunkStatus(
                        chunkId = chunk.id,
                        status = ChunkEntity.STATUS_FAILED,
                        audioPath = null,
                        keyUsedMasked = candidateKey.maskedKey,
                        keyUsedName = candidateKey.name,
                        durationMs = latency,
                        error = "تولید صدا موفق بود اما ذخیره فایل روی حافظه شکست خورد (حافظه پر است؟)"
                    )
                    return false
                }

                // Update key metrics
                vaultManager.recordSuccess(candidateKey.id)

                // Update chunk in DB with the Key's Name
                repository.updateChunkStatus(
                    chunkId = chunk.id,
                    status = ChunkEntity.STATUS_COMPLETED,
                    audioPath = outputFile.absolutePath,
                    keyUsedMasked = candidateKey.maskedKey,
                    keyUsedName = candidateKey.name,
                    durationMs = latency,
                    error = null
                )

                // Log completion
                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    keyName = candidateKey.name,
                    keyMasked = candidateKey.maskedKey,
                    status = "SUCCESS",
                    httpCode = 200,
                    latencyMs = latency,
                    message = "بخش ${chunk.chunkIndex} انجام شد با کلید ${candidateKey.maskedKey}",
                    filePath = outputFile.absolutePath,
                    fileSize = outputFile.length()
                )

                return true

            } catch (e: TtsQuotaExceededException) {
                val latency = System.currentTimeMillis() - startTime
                // Dynamic failover: quota limit puts key in 60s cooldown
                vaultManager.recordError(candidateKey.id, isQuotaLimit = true)
                excludedKeyIds.add(candidateKey.id)

                val notice = "سوئیچ از کلید «${candidateKey.name}» به علت محدودیت سهمیه (429)"
                _queueState.value = _queueState.value.copy(lastSwitchedKeyInfo = notice)

                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    keyName = candidateKey.name,
                    keyMasked = candidateKey.maskedKey,
                    status = "RATE_LIMIT_429",
                    httpCode = 429,
                    latencyMs = latency,
                    message = notice
                )
                continue

            } catch (e: TtsInvalidKeyException) {
                val latency = System.currentTimeMillis() - startTime
                vaultManager.recordError(candidateKey.id, isQuotaLimit = false, isPermanentInvalid = false)
                excludedKeyIds.add(candidateKey.id)

                val notice = "کلید «${candidateKey.name}» نامعتبر بود؛ سوئیچ به کلید سالم بعدی..."
                _queueState.value = _queueState.value.copy(lastSwitchedKeyInfo = notice)

                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    keyName = candidateKey.name,
                    keyMasked = candidateKey.maskedKey,
                    status = "INVALID_KEY",
                    httpCode = 403,
                    latencyMs = latency,
                    message = notice
                )
                continue

            } catch (e: TtsNetworkException) {
                // BUG-03: قطعی اینترنت/timeout جریمهٔ کلید نیست — نه recordError، نه exclude
                networkRetry++
                if (networkRetry <= maxNetworkRetries) {
                    val backoffMs = (2000L shl (networkRetry - 1).coerceAtMost(4)).coerceAtMost(30000L)
                    val noNetwork = !isNetworkAvailable()
                    val notice = if (noNetwork) {
                        "اینترنت قطع است؛ منتظر بازگشت ارتباط و تلاش دوباره (${networkRetry}/${maxNetworkRetries})..."
                    } else {
                        "خطای شبکه؛ تلاش دوباره تا ${backoffMs / 1000} ثانیه دیگر (${networkRetry}/${maxNetworkRetries})..."
                    }
                    _queueState.value = _queueState.value.copy(currentStatusMessage = notice)

                    repository.logConversion(
                        projectId = project.id,
                        projectName = project.name,
                        chunkIndex = chunk.chunkIndex,
                        keyName = candidateKey.name,
                        keyMasked = candidateKey.maskedKey,
                        status = "NETWORK_RETRY",
                        httpCode = 0,
                        latencyMs = 0L,
                        message = notice
                    )

                    TtsQueueService.updateProgress(
                        context = context,
                        projectName = project.name,
                        currentChunk = chunk.chunkIndex,
                        totalChunks = totalInQueue,
                        completedChunks = completedSoFar,
                        keyName = "خطای شبکه (تلاش $networkRetry از $maxNetworkRetries)",
                        speakerName = project.selectedVoice
                    )

                    delay(backoffMs)
                    // شبکه مصرف‌کنندهٔ بودجهٔ تلاش کلیدها نیست
                    attempt--
                    continue
                }

                val finalNetMsg = "ارتباط شبکه برقرار نیست و پس از $maxNetworkRetries تلاش هم بازنگشت. اینترنت را بررسی کنید و دوباره تلاش کنید."
                repository.updateChunkStatus(
                    chunkId = chunk.id,
                    status = ChunkEntity.STATUS_FAILED,
                    audioPath = null,
                    keyUsedMasked = null,
                    keyUsedName = null,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = finalNetMsg
                )
                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    keyName = candidateKey.name,
                    keyMasked = candidateKey.maskedKey,
                    status = "ERROR",
                    httpCode = 0,
                    latencyMs = System.currentTimeMillis() - startTime,
                    message = finalNetMsg
                )
                return false
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startTime
                // Place key into temporary 25s cooldown on ANY error to avoid immediate repeated failure
                vaultManager.recordError(candidateKey.id, isQuotaLimit = false)
                excludedKeyIds.add(candidateKey.id)

                val errorMsg = e.message ?: "خطای ناشناخته در تبدیل صوت"
                val notice = "خطا در کلید «${candidateKey.name}»: $errorMsg. سوئیچ به کلید بعدی..."
                _queueState.value = _queueState.value.copy(lastSwitchedKeyInfo = notice)

                repository.logConversion(
                    projectId = project.id,
                    projectName = project.name,
                    chunkIndex = chunk.chunkIndex,
                    keyName = candidateKey.name,
                    keyMasked = candidateKey.maskedKey,
                    status = "ERROR",
                    httpCode = 500,
                    latencyMs = latency,
                    message = errorMsg
                )

                // If we have more attempts and other keys, try failover
                if (attempt < maxAttempts && (vaultManager.keys.value.size > 1)) {
                    continue
                }

                repository.updateChunkStatus(
                    chunkId = chunk.id,
                    status = ChunkEntity.STATUS_FAILED,
                    audioPath = null,
                    keyUsedMasked = candidateKey.maskedKey,
                    keyUsedName = candidateKey.name,
                    durationMs = latency,
                    error = errorMsg
                )
                return false
            }
        }

        // Guaranteed fallback if all attempts finished without success: never leave chunk stuck in PROCESSING!
        val finalError = "تلاش برای تبدیل این بخش با تمام کلیدهای موجود ناموفق بود. لطفاً کلیدها یا اتصال اینترنت را بررسی فرمایید."
        repository.updateChunkStatus(
            chunkId = chunk.id,
            status = ChunkEntity.STATUS_FAILED,
            audioPath = null,
            keyUsedMasked = null,
            keyUsedName = null,
            durationMs = 0L,
            error = finalError
        )
        return false
    }

    /** BUG-03: تشخیص در دسترس بودن شبکه فقط برای پیام وضعیت درست — گیت‌کنندهٔ صف نیست */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (e: Exception) {
            true // در شکست تشخیص، فرض اتصال — تصمیم نهایی با سقف retry شبکه است
        }
    }

    fun cancelQueue() {
        // BUG-05: بخش فعال PROCESSING پیش از پاک‌شدن state به PENDING برمی‌گردد
        // (منطق مشترک با بقیهٔ مسیرهای لغو — دیگه چیزی گیر نمی‌کند)
        resetActiveChunk()
        queueJob?.cancel()
        // BUG-16: درخواست HTTP در جریان هم فوراً لغو می‌شود، نه فقط بعد از پایانش
        ttsService.cancelActiveRequest()
        TtsQueueService.stop(context)
        _queueState.value = QueueState(
            isRunning = false,
            activeChunkId = null,
            currentStatusMessage = "پردازش صف متوقف شد."
        )
    }

    // BUG-05: بخش فعال صف اگر در وضعیت PROCESSING گیر کرده باشد به PENDING برمی‌گردد
    private fun resetActiveChunk() {
        val currentActiveId = _queueState.value.activeChunkId ?: return
        scope.launch {
            val current = chunkDao.getChunkById(currentActiveId)
            if (current?.status == ChunkEntity.STATUS_PROCESSING) {
                repository.updateChunkStatus(
                    chunkId = currentActiveId,
                    status = ChunkEntity.STATUS_PENDING,
                    audioPath = null,
                    keyUsedMasked = null,
                    keyUsedName = null,
                    durationMs = 0L,
                    error = null
                )
            }
        }
    }
}
