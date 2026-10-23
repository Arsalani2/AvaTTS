package com.example.service.tts

enum class VoiceGender {
    FEMALE,
    MALE
}

data class TtsVoice(
    val name: String,
    val persianName: String,
    val gender: VoiceGender,
    val tone: String,
    val description: String,
    val sampleText: String = "سلام! صدای آزمایشی گوینده هوشمند گوگل است."
)

object VoiceCatalog {
    val allVoices: List<TtsVoice> = listOf(
        // Female Voices (6 authentic Gemini voices)
        TtsVoice(
            name = "Kore",
            persianName = "کوره (Kore)",
            gender = VoiceGender.FEMALE,
            tone = "ملایم و شفاف",
            description = "آرامش‌بخش، گویندگی عمومی و کتاب"
        ),
        TtsVoice(
            name = "Aoede",
            persianName = "آئوده (Aoede)",
            gender = VoiceGender.FEMALE,
            tone = "حرفه‌ای و رادیویی",
            description = "رسمی، اخبار و پادکست‌های شرکتی"
        ),
        TtsVoice(
            name = "Leda",
            persianName = "لدا (Leda)",
            gender = VoiceGender.FEMALE,
            tone = "پرانرژی و جوان",
            description = "شاداب، آموزشی و انیمیشنی"
        ),
        TtsVoice(
            name = "Callisto",
            persianName = "کالیستو (Callisto)",
            gender = VoiceGender.FEMALE,
            tone = "گرم و متین",
            description = "روایی، ادبیات و پادکست‌های داستانی"
        ),
        TtsVoice(
            name = "Erin",
            persianName = "ارین (Erin)",
            gender = VoiceGender.FEMALE,
            tone = "شفاف و صمیمی",
            description = "لحن روزمره، مصاحبه و آموزش آنلاین"
        ),
        TtsVoice(
            name = "Vega",
            persianName = "وگا (Vega)",
            gender = VoiceGender.FEMALE,
            tone = "رسا و علمی",
            description = "گویندگی تخصصی، تیزر و مقالات علمی"
        ),

        // Male Voices (6 authentic Gemini voices)
        TtsVoice(
            name = "Puck",
            persianName = "پاک (Puck)",
            gender = VoiceGender.MALE,
            tone = "دوستانه و مدرن",
            description = "صمیمی، محاوره‌ای و تیزرهای جذاب"
        ),
        TtsVoice(
            name = "Charon",
            persianName = "کارون (Charon)",
            gender = VoiceGender.MALE,
            tone = "کلاسیک و مستند",
            description = "عمیق، متین، مستندسازی و کتاب صوتی"
        ),
        TtsVoice(
            name = "Fenrir",
            persianName = "فنریر (Fenrir)",
            gender = VoiceGender.MALE,
            tone = "بم و حماسی",
            description = "پرصلابت، مقتدر، تریلر و تاریخی"
        ),
        TtsVoice(
            name = "Zephyr",
            persianName = "زفیر (Zephyr)",
            gender = VoiceGender.MALE,
            tone = "راوی داستان و ملایم",
            description = "لحن گرم، شعر، ادبیات و قصه"
        ),
        TtsVoice(
            name = "Orus",
            persianName = "اوروس (Orus)",
            gender = VoiceGender.MALE,
            tone = "رسمی و بیانی",
            description = "گوینده اخبار، مقالات و سخنرانی"
        ),
        TtsVoice(
            name = "Enif",
            persianName = "انیف (Enif)",
            gender = VoiceGender.MALE,
            tone = "آرام و دلنشین",
            description = "آموزشی، پادکست و گفتگوهای محاوره‌ای"
        )
    )

    fun getVoiceByName(name: String): TtsVoice {
        return allVoices.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: allVoices.first()
    }
}
