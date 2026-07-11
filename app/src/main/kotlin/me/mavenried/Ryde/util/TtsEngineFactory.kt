package me.mavenried.Ryde.util

import android.content.Context
import android.speech.tts.TextToSpeech

// Google's TTS engine has much higher-quality voices than most OEM defaults
// (e.g. Samsung's built-in engine) — prefer it, falling back to whatever's
// installed if it's not available on this device. onReady fires exactly once,
// with whichever engine actually finished initializing successfully.
object TtsEngineFactory {
    fun create(context: Context, onReady: (TextToSpeech) -> Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                onReady(engine!!)
            } else {
                engine?.shutdown()
                engine = TextToSpeech(context) { fallbackStatus ->
                    if (fallbackStatus == TextToSpeech.SUCCESS) onReady(engine!!)
                }
            }
        }, "com.google.android.tts")
    }
}
