package com.example

import com.example.data.vault.ApiKeyItem
import com.example.data.vault.KeyStatus
import com.example.service.tts.TtsInvalidKeyException
import com.example.service.tts.TtsQuotaExceededException
import com.example.util.SmartTextChunker
import com.example.util.WavAudioUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExampleUnitTest {

    @Test
    fun testSmartTextChunker_naturalBoundaries() {
        val sampleText = "این یک جمله کوتاه است. جمله دوم با علامت سوال به پایان می‌رسد؟ جمله سوم همراه با ویرگول، بخش بعدی را توضیح می‌دهد."
        val chunks = SmartTextChunker.chunkText(sampleText, 40)
        assertTrue("باید حداقل دو بخش تولید شود", chunks.size >= 2)
        // Verify no chunks are blank
        for (chunk in chunks) {
            assertTrue("بخش نباید خالی باشد", chunk.isNotBlank())
            assertFalse("بخش نباید با کاراکترهای خالی نامتعارف شروع شود", chunk.startsWith(" "))
        }
    }

    @Test
    fun testSmartTextChunker_longWordDoesNotCauseInfiniteLoop() {
        val longWord = "خیلیخیلیخیلیخیلیخیلیخیلیطولانی"
        val chunks = SmartTextChunker.chunkText(longWord, 10)
        assertTrue(chunks.isNotEmpty())
        assertEquals(longWord, chunks.joinToString(""))
    }

    @Test
    fun testWavAudioUtil_ensureWavFormat() {
        // Raw dummy PCM 16-bit mono 24kHz (480 bytes = 10ms)
        val rawPcm = ByteArray(480) { (it % 128).toByte() }
        val wavBytes = WavAudioUtil.ensureWavFormat(rawPcm, sampleRate = 24000, channels = 1, bitsPerSample = 16)

        // Must have at least 44-byte RIFF header + 480 payload = 524 bytes
        assertEquals(524, wavBytes.size)
        // Check RIFF magic
        assertEquals('R'.code.toByte(), wavBytes[0])
        assertEquals('I'.code.toByte(), wavBytes[1])
        assertEquals('F'.code.toByte(), wavBytes[2])
        assertEquals('F'.code.toByte(), wavBytes[3])
        // Check WAVE magic
        assertEquals('W'.code.toByte(), wavBytes[8])
        assertEquals('A'.code.toByte(), wavBytes[9])
        assertEquals('V'.code.toByte(), wavBytes[10])
        assertEquals('E'.code.toByte(), wavBytes[11])
    }

    @Test
    fun testApiKeyItem_masking() {
        val raw1 = "AIzaSyD123456789TestKeyX"
        val masked1 = ApiKeyItem.maskKey(raw1)
        assertEquals("AIza****KeyX", masked1)

        val rawShort = "AIza12"
        val maskedShort = ApiKeyItem.maskKey(rawShort)
        assertTrue(maskedShort.contains("****"))
    }

    @Test
    fun testApiKeyItem_stabilityScore() {
        val item = ApiKeyItem(
            id = "key-1",
            name = "Test Key",
            encryptedKey = "enc",
            maskedKey = "AI****X",
            successCount = 9,
            errorCount = 1
        )
        assertEquals(0.9, item.stabilityScore, 0.001)
    }

    @Test
    fun testTtsExceptions_httpCodes() {
        val quotaEx = TtsQuotaExceededException("Quota exceeded")
        assertEquals(429, quotaEx.httpCode)

        val invalidKeyEx = TtsInvalidKeyException("Invalid key")
        assertEquals(403, invalidKeyEx.httpCode)
    }
}
