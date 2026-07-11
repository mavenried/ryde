package me.mavenried.Ryde.util

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

object TtsVoices {
    fun available(engine: TextToSpeech): List<Voice> =
        (engine.voices?.filter { it.locale == Locale.US } ?: emptySet())
            .sortedByDescending { it.quality }

    fun best(voices: List<Voice>): Voice? =
        voices.filterNot { it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
            ?: voices.maxByOrNull { it.quality }

    fun preferred(engine: TextToSpeech, savedVoiceName: String?): Voice? {
        val voices = available(engine)
        return voices.find { it.name == savedVoiceName } ?: best(voices)
    }

    fun label(voice: Voice): String {
        val quality = when {
            voice.quality >= Voice.QUALITY_VERY_HIGH -> "Very high"
            voice.quality >= Voice.QUALITY_HIGH -> "High"
            voice.quality >= Voice.QUALITY_NORMAL -> "Normal"
            voice.quality >= Voice.QUALITY_LOW -> "Low"
            else -> "Very low"
        }
        val network = if (voice.isNetworkConnectionRequired) ", online" else ""
        return "$quality quality$network — ${voice.name}"
    }
}
