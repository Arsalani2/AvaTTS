package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.vault.ApiKeyItem
import com.example.util.SmartTextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AvaTTS", appName)
    }

    @Test
    fun `test api key masking preserves security`() {
        val key = "AIzaSyD123456789TestKeyX"
        val masked = ApiKeyItem.maskKey(key)
        assertTrue(masked.contains("****"))
        assertTrue(masked.startsWith("AIza"))
        assertTrue(masked.endsWith("eyX"))
    }

    @Test
    fun `test text chunker splits correctly`() {
        val text = "جمله اول تست. جمله دوم تست است. جمله سوم تست طولانی تر است."
        val chunks = SmartTextChunker.chunkText(text, 25)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `test project seeding preference persists correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.pref.UserPreferences(context)
        prefs.setSeededInitialProject(true)
        assertTrue(prefs.hasSeededInitialProject())
        prefs.setLastActiveProjectId(42L)
        assertEquals(42L, prefs.getLastActiveProjectId())
    }

    @Test
    fun `test key enabled toggle and current status`() {
        val key = ApiKeyItem(
            id = "key-1",
            name = "کلید تستی",
            encryptedKey = "enc",
            maskedKey = "AI****X",
            isEnabled = false
        )
        assertEquals(com.example.data.vault.KeyStatus.INVALID, key.currentStatus)
    }

    @Test
    fun `test chunk and project models include new sync fields`() {
        val project = com.example.data.db.ProjectEntity(
            id = 1L,
            name = "پروژه تست",
            isLocked = true
        )
        assertTrue(project.isLocked)

        val chunk = com.example.data.db.ChunkEntity(
            id = 10L,
            projectId = 1L,
            chunkIndex = 1,
            text = "متن نمونه",
            keyUsedName = "کلید اصلی تست"
        )
        assertEquals("کلید اصلی تست", chunk.keyUsedName)
    }
}
