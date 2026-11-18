package cn.yuntong.companion.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import cn.yuntong.companion.domain.SpeechSession
import cn.yuntong.companion.domain.SpeechState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeechOutput(context: Context, private val error: (String) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) pause() }
            .build()
    private val session = SpeechSession()
    private val mutable = MutableStateFlow(SpeechState())
    val state = mutable.asStateFlow()
    private var ready = false
    var rate = 1f
    private var tts: TextToSpeech? = null

    init {
        tts =
            TextToSpeech(context) { status ->
                synchronized(this) {
                    ready =
                        status == TextToSpeech.SUCCESS &&
                            (tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: -1) >= 0
                    tts?.setAudioAttributes(attributes)
                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(id: String?) {}

                            override fun onError(id: String?) {
                                synchronized(this@SpeechOutput) {
                                    if (id == session.token && session.state.phase == "speaking")
                                        fail("语音播放失败，请检查中文语音引擎")
                                }
                            }

                            override fun onDone(id: String?) {
                                synchronized(this@SpeechOutput) {
                                    if (session.done(id)) sayPart()
                                    else if (session.state.phase == "completed")
                                        audio.abandonAudioFocusRequest(focus)
                                    publish()
                                }
                            }
                        }
                    )
                }
            }
    }

    private fun publish() {
        mutable.value = session.state
    }

    private fun fail(message: String) {
        session.fail(message)
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
        publish()
        error(message)
    }

    @Synchronized
    fun speak(text: String, source: String = "本机朗读") {
        stop()
        session.start(text, source)
        if (!ready) {
            fail("请在系统设置中安装并启用中文文字转语音引擎")
            return
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail("当前音频通道忙，请稍后重试")
            return
        }
        if (session.state.phase == "idle") {
            audio.abandonAudioFocusRequest(focus)
            publish()
            return
        }
        publish()
        sayPart()
    }

    private fun sayPart() {
        tts?.setSpeechRate(rate)
        if (
            tts?.speak(session.text, TextToSpeech.QUEUE_FLUSH, null, session.token) ==
                TextToSpeech.ERROR
        )
            fail("语音播放失败，请检查中文语音引擎")
    }

    @Synchronized
    fun pause() {
        session.pause()
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
        publish()
    }

    @Synchronized
    fun resume() {
        if (session.state.phase != "paused") return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error("当前音频通道忙，请稍后重试")
            return
        }
        if (session.resume()) {
            publish()
            sayPart()
        }
    }

    @Synchronized
    fun stop() {
        session.stop()
        tts?.stop()
        audio.abandonAudioFocusRequest(focus)
        publish()
    }

    fun close() {
        stop()
        tts?.shutdown()
    }
}
