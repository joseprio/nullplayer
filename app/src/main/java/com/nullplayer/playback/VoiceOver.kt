package com.nullplayer.playback

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nullplayer.data.Track
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * The only channel through which a track ever identifies itself.
 *
 * Nothing here writes to the screen; the information exists for as long as it takes to say it and
 * then it is gone.
 */
class VoiceOver(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
) {

    private val counter = AtomicLong(0)
    private var ready = false
    private var pending: String? = null

    /** The stored choice, kept so it can be applied once the engine finishes starting. */
    private var wantedVoice: String = ""

    private val engine = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            publishVoices()
            applyVoice()
            pending?.let { pending = null; speak(it) }
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakingChanged(true)
            override fun onDone(utteranceId: String?) = onSpeakingChanged(false)
            @Deprecated("Required by the platform base class")
            override fun onError(utteranceId: String?) = onSpeakingChanged(false)
            override fun onError(utteranceId: String?, errorCode: Int) = onSpeakingChanged(false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) =
                onSpeakingChanged(false)
        })
    }

    fun speak(text: String) {
        if (!ready) {
            // The engine is still starting up; say this as soon as it is.
            pending = text
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "np-${counter.incrementAndGet()}")
    }

    /** Pass an empty name for whatever the engine picks for the device language. */
    fun setVoice(name: String) {
        wantedVoice = name
        applyVoice()
    }

    private fun applyVoice() {
        if (!ready) return
        runCatching {
            val chosen = wantedVoice
                .takeIf { it.isNotEmpty() }
                ?.let { wanted -> engine.voices?.firstOrNull { it.name == wanted } }
            // There is no way to unset a voice, so falling back means asking for the language
            // again and letting the engine choose as it did on the first run.
            if (chosen != null) engine.voice = chosen else engine.setLanguage(Locale.getDefault())
        }
    }

    /**
     * The voices worth offering: installed, and speaking the language the phone is set to.
     *
     * An engine will happily report several hundred voices across every language it ships. A list
     * that long is not a setting, it is a haystack.
     */
    private fun publishVoices() {
        val all = runCatching { engine.voices }.getOrNull().orEmpty().filterNot { voice ->
            voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true
        }
        val language = Locale.getDefault().language
        val relevant = all.filter { it.locale.language == language }.ifEmpty { all }

        val options = relevant
            .groupBy { it.locale.displayName }
            .toSortedMap()
            .flatMap { (localeName, voices) ->
                voices.sortedBy { it.name }.mapIndexed { index, voice ->
                    val notes = buildList {
                        if (voices.size > 1) add("voice ${index + 1}")
                        if (voice.isNetworkConnectionRequired) add("online")
                    }
                    VoiceOption(
                        id = voice.name,
                        label = if (notes.isEmpty()) {
                            localeName
                        } else {
                            "$localeName · ${notes.joinToString(" · ")}"
                        },
                    )
                }
            }
        Voices.publish(options)
    }

    fun stop() {
        pending = null
        if (ready) engine.stop()
        onSpeakingChanged(false)
    }

    fun release() {
        pending = null
        engine.stop()
        engine.shutdown()
    }

    companion object {

        /** Read back when a voice is picked, so the choice can be judged by ear. */
        const val VOICE_SAMPLE = "This is the voice that will read your tracks."

        /** "Blue Monday. By New Order. From Power, Corruption and Lies." */
        fun describe(track: Track?): String {
            if (track == null) return "No song loaded."
            return buildList {
                add(track.title ?: "Untitled track")
                track.artist?.let { add("by $it") }
                track.album?.let { add("from $it") }
                track.year?.let { add(it) }
            }.joinToString(". ") + "."
        }

        /** "Track 4 of 96. 3 minutes 21 seconds." */
        fun describePosition(index: Int, total: Int, durationMs: Long): String {
            val position = "Track ${index + 1} of $total."
            if (durationMs <= 0) return position

            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "$position ${plural(minutes, "minute")} ${plural(seconds, "second")}."
        }

        fun describeVault(count: Int, bytes: Long): String = when (count) {
            0 -> "The vault is empty."
            else -> "$count ${if (count == 1) "track" else "tracks"}, " +
                "${bytes / (1024 * 1024)} megabytes."
        }

        private fun plural(value: Long, noun: String) =
            "$value $noun${if (value == 1L) "" else "s"}"
    }
}
