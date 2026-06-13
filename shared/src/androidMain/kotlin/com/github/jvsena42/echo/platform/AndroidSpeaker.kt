package com.github.jvsena42.echo.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * [Speaker] backed by Android [TextToSpeech]. The engine initializes asynchronously; [speak] is a
 * no-op until it reports ready. Uses the device default locale and the OS-installed voices.
 */
class AndroidSpeaker(context: Context) : Speaker {

    private var ready = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.setLanguage(Locale.getDefault())
    }

    override fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "echo-speak")
    }
}
