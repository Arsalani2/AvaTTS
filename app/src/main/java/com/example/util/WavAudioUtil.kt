package com.example.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavAudioUtil {

    data class WavInfo(
        val channels: Short,
        val sampleRate: Int,
        val byteRate: Int,
        val blockAlign: Short,
        val bitsPerSample: Short,
        val dataOffset: Long,
        val dataSize: Long
    )

    /**
     * Checks if data starts with RIFF header. If not, prepends 44-byte WAV header for 24kHz 16-bit mono.
     */
    fun ensureWavFormat(
        audioBytes: ByteArray,
        sampleRate: Int = 24000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        if (audioBytes.size >= 4 &&
            audioBytes[0] == 'R'.code.toByte() &&
            audioBytes[1] == 'I'.code.toByte() &&
            audioBytes[2] == 'F'.code.toByte() &&
            audioBytes[3] == 'F'.code.toByte()
        ) {
            return audioBytes
        }

        val totalAudioLen = audioBytes.size.toLong()
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()

        val header = ByteArray(44)
        // RIFF/WAVE header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // 'fmt ' chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate.toLong() and 0xff).toByte()
        header[25] = ((sampleRate.toLong() shr 8) and 0xff).toByte()
        header[26] = ((sampleRate.toLong() shr 16) and 0xff).toByte()
        header[27] = ((sampleRate.toLong() shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte() // block align
        header[33] = 0
        header[34] = bitsPerSample.toByte() // bits per sample
        header[35] = 0
        // 'data' chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val output = ByteArrayOutputStream(header.size + audioBytes.size)
        output.write(header)
        output.write(audioBytes)
        return output.toByteArray()
    }

    fun saveBytesToFile(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
    }

    /**
     * Reads and parses WAV RIFF header and chunks.
     */
    fun parseWav(file: File): WavInfo? {
        if (!file.exists() || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff) != "RIFF") return null

            raf.skipBytes(4) // file size - 8
            val wave = ByteArray(4)
            raf.readFully(wave)
            if (String(wave) != "WAVE") return null

            var channels: Short = 1
            var sampleRate = 24000
            var byteRate = 48000
            var blockAlign: Short = 2
            var bitsPerSample: Short = 16
            var dataOffset = -1L
            var dataSize = -1L

            val chunkHeader = ByteArray(8)
            val buffer = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)

            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                buffer.rewind()
                val chunkId = String(chunkHeader, 0, 4)
                val chunkSize = buffer.getInt(4).toLong() and 0xFFFFFFFFL

                when (chunkId) {
                    "fmt " -> {
                        // BUG-20: دقیقاً chunkSize بایت خوانده می‌شود (نه ۱۶ تایی که چانک بعدی را
                        // می‌خورد)؛ اگر fmt کمتر از ۱۶ بایت بود، بقیه با صفر pad می‌شود
                        val remainingInFile = (raf.length() - raf.filePointer).coerceAtLeast(0L)
                        val toRead = chunkSize.coerceIn(0L, remainingInFile).toInt()
                        val raw = ByteArray(toRead)
                        if (toRead > 0) raf.readFully(raw)
                        val fmtData = if (raw.size >= 16) raw else raw + ByteArray(16 - raw.size)
                        val fmtBuf = ByteBuffer.wrap(fmtData).order(ByteOrder.LITTLE_ENDIAN)
                        val audioFormat = fmtBuf.short
                        if (audioFormat.toInt() != 1) {
                            // Non-PCM not directly supported
                        }
                        channels = fmtBuf.short
                        sampleRate = fmtBuf.int
                        byteRate = fmtBuf.int
                        blockAlign = fmtBuf.short
                        bitsPerSample = fmtBuf.short
                        // BUG-20: چانک با اندازهٔ فرد یک بایت pad دارد (word-alignment)
                        if (chunkSize % 2L != 0L && raf.filePointer < raf.length()) raf.skipBytes(1)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = chunkSize.coerceAtMost(raf.length() - dataOffset)
                        break
                    }
                    else -> {
                        // BUG-20: skipBytes تضمینی ندارد — تا کامل شدن skip ادامه بده
                        var toSkip = chunkSize.coerceAtMost((raf.length() - raf.filePointer).coerceAtLeast(0L))
                        while (toSkip > 0) {
                            val skipped = raf.skipBytes(toSkip.toInt())
                            if (skipped <= 0) break
                            toSkip -= skipped
                        }
                        if (toSkip > 0) break
                        // BUG-20: چانک با اندازهٔ فرد یک بایت pad دارد (word-alignment)
                        if (chunkSize % 2L != 0L && raf.filePointer < raf.length()) raf.skipBytes(1)
                    }
                }
            }

            if (dataOffset == -1L || dataSize <= 0) return null

            return WavInfo(
                channels = channels,
                sampleRate = sampleRate,
                byteRate = byteRate,
                blockAlign = blockAlign,
                bitsPerSample = bitsPerSample,
                dataOffset = dataOffset,
                dataSize = dataSize
            )
        }
    }

    /**
     * Merges multiple WAV files sequentially into a single WAV file.
     * Complies strictly with the Python wave merge logic:
     * - Preserves chunk ordering
     * - Validates audio format compatibility (channels, bitsPerSample, sampleRate)
     * - Concatenates raw PCM frames into a single output file
     */
    fun mergeWavFiles(files: List<File>, outputFile: File): Result<File> {
        if (files.isEmpty()) {
            return Result.failure(IllegalArgumentException("هیچ فایلی برای ادغام انتخاب نشده است."))
        }

        val validFiles = files.filter { it.exists() && it.isFile && it.length() > 44 }
        if (validFiles.isEmpty()) {
            return Result.failure(IllegalArgumentException("فایل‌های صوتی معتبری برای ادغام یافت نشد."))
        }

        try {
            var baseInfo: WavInfo? = null
            var totalDataLen = 0L

            val infos = mutableListOf<Pair<File, WavInfo>>()

            for (f in validFiles) {
                val info = parseWav(f)
                    ?: return Result.failure(IllegalArgumentException("فایل ${f.name} ساختار WAV معتبری ندارد."))
                if (baseInfo == null) {
                    baseInfo = info
                } else {
                    if (info.channels != baseInfo.channels ||
                        info.sampleRate != baseInfo.sampleRate ||
                        info.bitsPerSample != baseInfo.bitsPerSample
                    ) {
                        return Result.failure(
                            IllegalArgumentException(
                                "فرمت فایل ${f.name} با بقیه یکسان نیست.\n" +
                                        "انتظار: ${baseInfo.sampleRate}Hz, ${baseInfo.channels}ch, ${baseInfo.bitsPerSample}bit\n" +
                                        "دریافت: ${info.sampleRate}Hz, ${info.channels}ch, ${info.bitsPerSample}bit"
                            )
                        )
                    }
                }
                infos.add(f to info)
                totalDataLen += info.dataSize
            }

            val b = baseInfo ?: return Result.failure(IllegalStateException("خطا در خواندن مشخصات صوتی."))

            if (totalDataLen > 0xFFFFFFFFL - 36) {
                return Result.failure(IllegalArgumentException("حجم فایل‌های انتخابی فراتر از استاندارد فرمت WAV است."))
            }

            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                // Write 44-byte RIFF header
                val totalChunkLen = totalDataLen + 36
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(totalChunkLen.toInt())
                header.put("WAVE".toByteArray(Charsets.US_ASCII))
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16) // Subchunk1Size for PCM
                header.putShort(1) // AudioFormat 1 = PCM
                header.putShort(b.channels)
                header.putInt(b.sampleRate)
                header.putInt(b.byteRate)
                header.putShort(b.blockAlign)
                header.putShort(b.bitsPerSample)
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(totalDataLen.toInt())

                out.write(header.array())

                // Stream PCM data from each file with guaranteed exact offset seeking and 64KB buffer
                val buffer = ByteArray(65536)
                for ((f, info) in infos) {
                    FileInputStream(f).use { fis ->
                        fis.channel.position(info.dataOffset)
                        var bytesRemaining = info.dataSize
                        while (bytesRemaining > 0) {
                            val toRead = bytesRemaining.coerceAtMost(buffer.size.toLong()).toInt()
                            val read = fis.read(buffer, 0, toRead)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            bytesRemaining -= read
                        }
                    }
                }
            }

            return Result.success(outputFile)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}
