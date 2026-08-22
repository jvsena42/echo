package com.github.jvsena42.loopky.domain.model

/**
 * Fallback language list for the deck language picker.
 *
 * The picker prefers what the installed TTS engine reports it can voice
 * ([com.github.jvsena42.loopky.platform.Speaker.availableLanguages]), because offering a language
 * the device cannot pronounce is a dead end. That list is empty while the engine is still
 * initializing and on a device with no engine at all, so this stands in — the deck's language pair
 * is metadata other people's devices will read, and it should not go unset just because *this*
 * phone happens to be missing a voice.
 *
 * Region-bearing tags on purpose: a voice needs `pt-BR` rather than `pt`. The tags carry no display
 * names — each platform renders them with its own locale API, so nothing here needs translating.
 */
object SpeechLanguages {

    val COMMON: List<String> = listOf(
        "ar-SA", "bn-BD", "cs-CZ", "da-DK", "de-DE", "el-GR", "en-GB", "en-US", "es-ES", "es-MX",
        "fi-FI", "fr-CA", "fr-FR", "he-IL", "hi-IN", "hu-HU", "id-ID", "it-IT", "ja-JP", "ko-KR",
        "nb-NO", "nl-NL", "pl-PL", "pt-BR", "pt-PT", "ro-RO", "ru-RU", "sk-SK", "sv-SE", "th-TH",
        "tr-TR", "uk-UA", "vi-VN", "zh-CN", "zh-TW",
    )

    /**
     * Whether a deck being authored has an audio opt-in on but has not said what language each
     * side is in — the one rule the publish flow and the deck editor both validate before writing.
     *
     * Kept here rather than duplicated in the two ViewModels so the two screens cannot drift into
     * disagreeing about when a deck may declare Listen or Speak.
     */
    fun isPairMissing(
        listenEnabled: Boolean,
        speakEnabled: Boolean,
        frontLang: String?,
        backLang: String?,
    ): Boolean = (listenEnabled || speakEnabled) && (frontLang == null || backLang == null)
}
