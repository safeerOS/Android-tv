// Media3 oznacuje AudioCapabilities in AnalyticsListener kot @UnstableApi (kot v PredvajalnikActivity).
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Zvocni izhod naprave: kaj sprejme nedotaknjeno (zvocnik ali sprejemnik na HDMI/eARC) in kaj gre v resnici ven.
 * Pravila in imena so v [ZvokPravila]; tu je samo branje iz sistema.
 */
object ZvokIzhod {
    private const val TAG = "SafeerOsMedia"

    /**
     * Zapisi, ki jih zvocni izhod ta hip sprejme kot nedotaknjen tok: "ac3", "eac3", "atmos" (E-AC-3 JOC), "truehd",
     * "dts". Prazno na telefonu z zvocnikom ali slusalkami in kadar sistem tega ne pove.
     */
    fun predaja(context: Context): Set<String> = try {
        val z = AudioCapabilities.getCapabilities(context.applicationContext, AudioAttributes.DEFAULT, null, emptyList())
        val s = HashSet<String>()
        if (z.supportsEncoding(C.ENCODING_AC3)) s.add("ac3")
        if (z.supportsEncoding(C.ENCODING_E_AC3)) s.add("eac3")
        if (z.supportsEncoding(C.ENCODING_E_AC3_JOC)) s.add("atmos")
        if (z.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) s.add("truehd")
        if (z.supportsEncoding(C.ENCODING_DTS) || z.supportsEncoding(C.ENCODING_DTS_HD)) s.add("dts")
        s
    } catch (_: Throwable) {
        emptySet()
    }

    /**
     * Najvec kanalov, ki jih dekodiran zvok (PCM) na tej napravi ta hip res odda. Zvocnik telefona, slusalke in
     * Bluetooth so stereo - sled 5.1 sistem zmesa sam, aplikacija pa odpre izhod s sestimi kanali in tega ne vidi.
     * Vec kanalov odda le naprava na HDMI ali USB, ki jih navede (prazen seznam pomeni »poljubno«).
     */
    fun kanalovPcm(context: Context): Int = try {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var najvec = 2
        for (n in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val vecKanalna = n.type == AudioDeviceInfo.TYPE_HDMI || n.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                n.type == 29 /* TYPE_HDMI_EARC, API 31 */ || n.type == AudioDeviceInfo.TYPE_USB_DEVICE
            if (!vecKanalna) continue
            val k = n.channelCounts
            najvec = maxOf(najvec, if (k.isEmpty()) 8 else (k.maxOrNull() ?: 2))
        }
        najvec
    } catch (_: Throwable) {
        2
    }

    /**
     * Zapisi, ki jih navaja zunanji zvocnik ali sprejemnik (naprava na HDMI, ARC ali eARC) - to, kar do njega pride
     * nedotaknjeno. Ni isto kot [predaja]: televizor kot neposreden tok sprejme tudi zapis, ki ga zvocnik ne zna
     * (izmerjeno 6. 10. 2026: DTS na zvocniku brez DTS), in ga dekodira sam. Null: zunanjega zvocnika ni ali zapisov
     * ne navaja.
     */
    fun zvocnik(context: Context): Set<String>? = try {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        var izid: HashSet<String>? = null
        for (n in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (n.type != AudioDeviceInfo.TYPE_HDMI && n.type != AudioDeviceInfo.TYPE_HDMI_ARC && n.type != 29 /* TYPE_HDMI_EARC */) continue
            val s = HashSet<String>()
            for (k in n.encodings) when (k) {
                android.media.AudioFormat.ENCODING_AC3 -> s.add("ac3")
                android.media.AudioFormat.ENCODING_E_AC3 -> s.add("eac3")
                android.media.AudioFormat.ENCODING_E_AC3_JOC -> s.add("atmos")
                android.media.AudioFormat.ENCODING_DOLBY_TRUEHD, 19 /* ENCODING_DOLBY_MAT, API 30 */ -> s.add("truehd")
                android.media.AudioFormat.ENCODING_DTS, android.media.AudioFormat.ENCODING_DTS_HD -> s.add("dts")
            }
            // Naprava, ki navaja samo PCM ali nic, o stisnjenih zapisih ne pove nicesar.
            if (s.isNotEmpty()) izid = (izid ?: HashSet()).apply { addAll(s) }
        }
        izid
    } catch (_: Throwable) {
        null
    }

    private fun imeKodiranja(k: Int): String = when (k) {
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3 -> "Dolby Digital Plus"
        C.ENCODING_E_AC3_JOC -> "Dolby Atmos (E-AC-3 JOC)"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        C.ENCODING_AC4 -> "Dolby AC-4"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        else -> "zapis $k"
    }

    /**
     * Poslusalec predvajalnika: ob vsaki spremembi sledi ali izhoda pove oznako za uporabnika
     * ([ZvokPravila.oznakaIzhoda]) in v dnevnik zapise, ali je tok predan zvocniku ali dekodiran (brez naslovov).
     */
    fun poslusalec(context: Context, naSpremembo: (String) -> Unit): AnalyticsListener = object : AnalyticsListener {
        private val app = context.applicationContext
        private val televizor = try { app.packageManager.hasSystemFeature("android.software.leanback") } catch (_: Throwable) { false }
        private var sled: Format? = null
        private var izhod: AudioSink.AudioTrackConfig? = null

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            if (sled == null) Log.i(TAG, "zvocni izhod sprejme nedotaknjeno: ${predaja(app).sorted().ifEmpty { listOf("nic (samo PCM)") }.joinToString(", ")}; PCM do ${kanalovPcm(app)} kanalov")
            sled = format
            objavi()
        }

        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            izhod = audioTrackConfig
            objavi()
        }

        override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            sled = null
            izhod = null
            naSpremembo("")
        }

        private fun objavi() {
            val s = sled ?: return
            val i = izhod ?: return
            val pcm = Util.isEncodingLinearPcm(i.encoding)
            val odprtih = Integer.bitCount(i.channelConfig)
            val kanalovIzhoda = if (pcm) minOf(odprtih, kanalovPcm(app)) else odprtih
            val zvocnik = if (pcm) null else zvocnik(app)
            val zmore = ZvokPravila.zvocnikZmore(s.sampleMimeType, zvocnik)
            val oznaka = ZvokPravila.oznakaIzhoda(s.sampleMimeType, s.channelCount, pcm, kanalovIzhoda, s.label, povejZmesanje = televizor,
                zvocnikZmore = zmore)
            Log.i(TAG, "zvocni izhod: sled ${s.sampleMimeType} ${s.channelCount}k -> " +
                (if (pcm) "PCM (dekodirano)" else if (zmore) "predano zvocniku (" + imeKodiranja(i.encoding) + ")"
                    else "predano sistemu (" + imeKodiranja(i.encoding) + "); zvocnik navaja ${zvocnik?.sorted()} - dekodira naprava") +
                " ${kanalovIzhoda}k (odprtih $odprtih) ${i.sampleRate} Hz; oznaka=\"$oznaka\"")
            naSpremembo(oznaka)
        }
    }
}
