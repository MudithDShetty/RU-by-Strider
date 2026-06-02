package com.strider.quanto

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceSearchManager(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onListeningStarted()
        fun onListeningEnded()
        fun onPartialResult(text: String)
        fun onResult(text: String)
        fun onError(messageResId: Int)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    var isListening = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (isListening) return
        if (!isAvailable()) {
            callbacks.onError(R.string.voice_not_available)
            return
        }

        destroyRecognizer()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
            startListening(buildRecognizerIntent())
        }
        isListening = true
    }

    fun stopListening() {
        if (!isListening) return
        speechRecognizer?.stopListening()
    }

    fun destroy() {
        isListening = false
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun buildRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            callbacks.onListeningStarted()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            isListening = false
            callbacks.onListeningEnded()
        }

        override fun onError(error: Int) {
            isListening = false
            callbacks.onListeningEnded()
            val messageResId = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> R.string.voice_no_match
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_network_error
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_permission_denied
                else -> R.string.voice_error
            }
            callbacks.onError(messageResId)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            callbacks.onListeningEnded()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val transcript = matches?.firstOrNull()?.trim().orEmpty()
            if (transcript.isNotEmpty()) {
                callbacks.onResult(MultilingualBridge.normalizeVoiceTranscript(transcript))
            } else {
                callbacks.onError(R.string.voice_no_match)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull()?.trim().orEmpty()
            if (partial.isNotEmpty()) {
                callbacks.onPartialResult(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
