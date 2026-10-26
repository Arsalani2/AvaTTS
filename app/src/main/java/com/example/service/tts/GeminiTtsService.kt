package com.example.service.tts

import android.util.Base64
import com.example.util.WavAudioUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class TtsException(message: String, val httpCode: Int = 0) : Exception(message)
class TtsQuotaExceededException(message: String) : TtsException(message, 429)
class TtsInvalidKeyException(message: String) : TtsException(message, 403)
class TtsNetworkException(message: String) : TtsException(message, 0)
class TtsGeneralException(message: String, code: Int = 500) : TtsException(message, code)

class GeminiTtsService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // BUG-16: درخواست در جریان برای لغو فوری از cancelQueue نگه‌داری می‌شود
    @Volatile
    private var activeCall: Call? = null

    /** BUG-16: لغو فوری درخواست HTTP در جریان (توقف صف وسط درخواست را می‌بُرد) */
    fun cancelActiveRequest() {
        try {
            activeCall?.cancel()
        } catch (_: Exception) {
        }
    }

    // Model list in priority order — BUG-04: فقط مدل‌های رسمی موجود در API گوگل؛
    // مدل‌های ناموجود قبلی حذف شدند تا هر خطای ۴۰۰ سه درخواست اضافه نسوزاند
    private val candidateModels = listOf(
        "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-tts"
    )

    /**
     * Synthesizes audio for given text and voice name using the provided API key.
     * Optionally accepts custom modelName entered by the user.
     * Throws specific TtsException for failover handling.
     */
    suspend fun synthesizeSpeech(
        apiKey: String,
        text: String,
        voiceName: String = "Kore",
        modelName: String? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw TtsInvalidKeyException("کلید API وارد نشده یا خالی است.")
        }

        var lastException: Exception? = null

        val requestedModel = modelName?.trim()?.takeIf { it.isNotBlank() } ?: "gemini-2.5-flash-preview-tts"
        val modelsToTry = if (requestedModel in candidateModels) {
            listOf(requestedModel) + candidateModels.filter { it != requestedModel }
        } else {
            listOf(requestedModel) + candidateModels
        }

        // Try models starting with user-selected model
        for (model in modelsToTry) {
            try {
                return@withContext executeSynthesis(trimmedKey, model, text, voiceName)
            } catch (e: TtsQuotaExceededException) {
                // Rate limit applies to the key, do not try other models with same key
                throw e
            } catch (e: TtsInvalidKeyException) {
                // Invalid key applies to the key
                throw e
            } catch (e: TtsGeneralException) {
                // BUG-04: فقط خطای مربوط به خودِ مدل (۴۰۴ یا ۴۰۰ِ حاوی «model»/«not found») زنجیرهٔ fallback را
                // ادامه می‌دهد؛ خطای payload (مثل متن خیلی بلند) با امتحان مدل بعدی سهمیه نمی‌سوزاند
                val msg = (e.message ?: "").lowercase()
                val modelRelated = e.httpCode == 404 ||
                    (e.httpCode == 400 && (msg.contains("model") || msg.contains("not found")))
                if (modelRelated) {
                    lastException = e
                    continue
                } else {
                    throw e
                }
            } catch (e: TtsNetworkException) {
                // BUG-32: خطای شبکه ربطی به مدل ندارد — زنجیرهٔ fallback نباید ۳ برابر درخواست بسوزاند
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                // لغو کوروتین (توقف صف) هرگز نباید بلعیده شود
                throw e
            } catch (e: Exception) {
                lastException = e
            }
        }

        throw lastException ?: TtsGeneralException("خطا در ارتباط با سرویس صوتی گوگل")
    }

    private suspend fun executeSynthesis(
        apiKey: String,
        modelName: String,
        text: String,
        voiceName: String
    ): ByteArray {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val requestPayload = JSONObject().apply {
            val contents = JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = JSONArray().apply {
                        val partObj = JSONObject().apply {
                            put("text", text)
                        }
                        put(partObj)
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)

            val generationConfig = JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voiceName)
                        })
                    })
                })
            }
            put("generationConfig", generationConfig)
        }

        val requestBody = requestPayload.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .build()

        // BUG-16: الگوی suspending با enqueue — لغو کوروتین (توقف صف) درخواست HTTP را فوراً می‌بُرد
        val call = client.newCall(request)
        activeCall = call
        val (responseBody, code, isSuccessful) = try {
            suspendCancellableCoroutine { cont ->
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) {
                            cont.resumeWith(
                                Result.failure(
                                    TtsNetworkException("خطای اتصال شبکه: لطفاً اینترنت خود را بررسی کنید. (${e.message})")
                                )
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = it.body?.string().orEmpty()
                            if (cont.isActive) {
                                cont.resumeWith(Result.success(Triple(body, it.code, it.isSuccessful)))
                            }
                        }
                    }
                })
                cont.invokeOnCancellation { call.cancel() }
            }
        } finally {
            if (activeCall === call) activeCall = null
        }

        if (!isSuccessful) {
            if (code == 429 || responseBody.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) {
                throw TtsQuotaExceededException("محدودیت سهمیه مصرف کلید (کد 429). کلید در حال خنک‌سازی...")
            }
            if (code == 400 || code == 403) {
                if (responseBody.contains("API_KEY_INVALID", ignoreCase = true) ||
                    responseBody.contains("API key not valid", ignoreCase = true) ||
                    responseBody.contains("API_KEY_SERVICE_BLOCKED", ignoreCase = true)
                ) {
                    throw TtsInvalidKeyException("کلید API نامعتبر است یا دسترسی مجاز ندارد (کد $code).")
                }
            }
            throw TtsGeneralException("خطای پاسخ سرور گوگل ($code): ${parseErrorMessage(responseBody)}", code)
        }

        // Parse JSON response
        try {
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                throw TtsGeneralException("پاسخ صوتی از مدل دریافت نشد.")
            }
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.getJSONObject("content")
            val parts = content.getJSONArray("parts")
            var base64Data: String? = null

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inlineData = part.optJSONObject("inlineData")
                if (inlineData != null) {
                    base64Data = inlineData.optString("data")
                    if (!base64Data.isNullOrBlank()) break
                }
            }

            if (base64Data.isNullOrBlank()) {
                throw TtsGeneralException("داده صوتی در پاسخ سرور یافت نشد.")
            }

            val rawBytes = Base64.decode(base64Data, Base64.DEFAULT)
            return WavAudioUtil.ensureWavFormat(rawBytes)
        } catch (e: Exception) {
            if (e is TtsException) throw e
            throw TtsGeneralException("خطا در پردازش فایل صوتی: ${e.message}")
        }
    }

    private fun parseErrorMessage(jsonBody: String): String {
        return try {
            val obj = JSONObject(jsonBody)
            val error = obj.optJSONObject("error")
            error?.optString("message") ?: jsonBody.take(120)
        } catch (e: Exception) {
            jsonBody.take(120)
        }
    }
}
