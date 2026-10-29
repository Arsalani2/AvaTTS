package com.example.util

/**
 * نرمال‌سازی ارقام فارسی/عربی به لاتین برای ورودی‌های عددی (الگوی BUG-12).
 * «۵۰۰» و «٥٠٠» هر دو به «500» تبدیل می‌شوند تا toIntOrNull کار کند.
 */
object PersianDigits {

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val ascii = when (ch) {
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
            sb.append(ascii)
        }
        return sb.toString()
    }
}
