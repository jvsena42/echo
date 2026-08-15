package com.github.jvsena42.loopky.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import android.speech.SpeechRecognizer as AndroidSpeech

/**
 * [SpeechRecognizer] backed by Android [android.speech.SpeechRecognizer]. The recognizer must be
 * created and used on the main thread, so [listen] hops to the main looper via [callbackFlow]'s
 * collector context expectations — callers collect from a main-dispatched coroutine. Microphone
 * permission must already be granted.
 */
class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizer {

    override fun isAvailable(): Boolean = AndroidSpeech.isRecognitionAvailable(context)

    override fun listen(languageTag: String?): Flow<SpeechEvent> = callbackFlow {
        if (!AndroidSpeech.isRecognitionAvailable(context)) {
            trySend(SpeechEvent.Error(SpeechError.UNAVAILABLE))
            close()
            return@callbackFlow
        }

        val recognizer = AndroidSpeech.createSpeechRecognizer(context)

        @Suppress("EmptyFunctionBlock")
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.Ready) }
            override fun onBeginningOfSpeech() { trySend(SpeechEvent.BeginningOfSpeech) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                firstTranscript(partialResults)?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = firstTranscript(results)
                if (text != null) trySend(SpeechEvent.Result(text)) else trySend(SpeechEvent.Error(SpeechError.NO_MATCH))
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Error(mapError(error)))
                close()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        recognizer.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        recognizer.startListening(intent)

        awaitClose {
            recognizer.stopListening()
            recognizer.destroy()
        }
    }

    private fun firstTranscript(bundle: Bundle?): String? =
        bundle?.getStringArrayList(AndroidSpeech.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun mapError(code: Int): SpeechError = when (code) {
        AndroidSpeech.ERROR_NO_MATCH, AndroidSpeech.ERROR_SPEECH_TIMEOUT -> SpeechError.NO_MATCH
        AndroidSpeech.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION
        AndroidSpeech.ERROR_NETWORK, AndroidSpeech.ERROR_NETWORK_TIMEOUT -> SpeechError.NETWORK
        AndroidSpeech.ERROR_RECOGNIZER_BUSY -> SpeechError.BUSY
        else -> SpeechError.UNKNOWN
    }
}
