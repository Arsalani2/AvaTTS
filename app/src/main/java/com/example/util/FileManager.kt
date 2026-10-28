package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.pref.UserPreferences
import java.io.File

class FileManager(
    private val context: Context,
    private val userPreferences: UserPreferences? = null
) {

    /**
     * Resolves the root TTS directory.
     * Prioritizes custom user folder in standard public directories (Music / Downloads)
     * so that the user and external file manager apps can easily access and view the files.
     */
    fun getRootTtsDirectory(customPath: String? = null): File {
        val configured = customPath?.trim()?.ifBlank { null }
            ?: userPreferences?.settings?.value?.defaultStorageFolder?.trim()?.ifBlank { null }
            ?: "AvaTTS"

        // If user gave a relative name like "AvaTTS" or "Podcasts"
        val folderName = if (configured.contains("/")) configured.substringAfterLast("/").ifBlank { "AvaTTS" } else configured

        // 1. Try public Music folder (most accessible for audio/TTS files)
        try {
            val publicMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val customDir = File(publicMusic, folderName)
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir
            }
        } catch (_: Exception) {}

        // 2. Try public Downloads folder
        try {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val customDir = File(publicDownloads, folderName)
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir
            }
        } catch (_: Exception) {}

        // 3. Fallback to App's External Music directory
        try {
            val appExt = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            val customDir = File(appExt, folderName)
            if (customDir.exists() || customDir.mkdirs()) {
                return customDir
            }
        } catch (_: Exception) {}

        // 4. Final Fallback to internal storage
        val internalDir = File(context.filesDir, folderName)
        if (!internalDir.exists()) {
            internalDir.mkdirs()
        }
        return internalDir
    }

    /**
     * Sanitizes string to safe folder/filename
     */
    fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Project" }
    }

    /**
     * Returns project folder inside root TTS directory: TTS/{ProjectName}/
     */
    fun getProjectDirectory(projectName: String, customRoot: String? = null): File {
        val root = getRootTtsDirectory(customRoot)
        val safeName = sanitizeFileName(projectName)
        val projectDir = File(root, safeName)
        if (!projectDir.exists()) {
            projectDir.mkdirs()
        }
        return projectDir
    }

    /**
     * Standard naming: {Project_Name}_Part_{Index}.wav
     */
    fun getAudioOutputFile(projectName: String, chunkIndex: Int, customRoot: String? = null): File {
        val dir = getProjectDirectory(projectName, customRoot)
        val safeName = sanitizeFileName(projectName)
        val fileName = "${safeName}_Part_${chunkIndex}.wav"
        return File(dir, fileName)
    }

    /**
     * Standard merged file naming: {Project_Name} - merged.wav
     */
    fun getMergedAudioOutputFile(projectName: String, customRoot: String? = null): File {
        val dir = getProjectDirectory(projectName, customRoot)
        val safeName = sanitizeFileName(projectName)
        val fileName = "$safeName - merged.wav"
        return File(dir, fileName)
    }

    /**
     * Checks if file exists and has non-zero bytes on disk.
     */
    fun isAudioFileAvailable(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        val file = File(filePath)
        return file.exists() && file.isFile && file.length() > 44
    }

    /**
     * Deletes file safely if exists
     */
    fun deleteAudioFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return try {
            val file = File(filePath)
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * BUG-06: پاک‌سازی best-effort پوشهٔ صوتی پروژه هنگام حذف پروژه —
     * شکست پاک‌سازی، حذف رکوردها را بلاک نمی‌کند.
     */
    fun deleteProjectFolder(projectName: String, customRoot: String? = null): Boolean {
        return try {
            val root = getRootTtsDirectory(customRoot)
            val dir = File(root, sanitizeFileName(projectName))
            if (dir.exists()) dir.deleteRecursively() else true
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /**
         * Opens a file or its parent folder in the device's default File Manager or Audio Player.
         */
        fun openFileOrFolder(context: Context, pathOrFile: String) {
            try {
                val file = File(pathOrFile)
                val target = if (file.exists()) file else file.parentFile ?: file

                if (target.isDirectory) {
                    val uri: Uri = try {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                    } catch (_: Exception) {
                        Uri.fromFile(target)
                    }

                    val candidateMimes = listOf(
                        "resource/folder",
                        "vnd.android.document/directory",
                        "*/*"
                    )

                    var launched = false
                    for (mime in candidateMimes) {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            val chooser = Intent.createChooser(intent, "مشاهده در مدیریت فایل")
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(chooser)
                            launched = true
                            break
                        } catch (_: Exception) {}
                    }

                    if (!launched) {
                        Toast.makeText(context, "مسیر پوشه:\n${target.absolutePath}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    val uri: Uri = try {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                    } catch (_: Exception) {
                        Uri.fromFile(target)
                    }

                    val mime = when {
                        target.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                        target.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                        else -> "audio/*"
                    }

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val chooser = Intent.createChooser(intent, "مدیریت فایل یا پخش صوت")
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "مسیر فایل: $pathOrFile", Toast.LENGTH_LONG).show()
            }
        }
    }
}
