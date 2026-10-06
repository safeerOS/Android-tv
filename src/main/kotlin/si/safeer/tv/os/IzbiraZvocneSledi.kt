// Media3 oznacuje izbiro sledi (DefaultTrackSelector) kot @UnstableApi; ob posodobitvi Media3 to datoteko preverimo.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.content.Context
import android.util.Log
import android.util.Pair
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/**
 * Izbira sledi kot v Media3, z eno razliko pri zvoku: med sledmi ISTEGA jezika dobi prednost tista, ki jo izhod te
 * naprave odda bolje ([ZvokPravila.boljsaSled]) - npr. Dolby Digital Plus 5.1, predan zvocniku, pred stereo sledjo AAC,
 * ki jo datoteka oznaci kot privzeto (izmerjeno 6. 10. 2026: taka datoteka je na televizorju z zvocnikom na eARC igrala
 * stereo). Izbira se zgodi v isti izbiri sledi (zvocni izhod se ne odpre dvakrat); rocna izbira uporabnika jo preglasi.
 */
class IzbiraZvocneSledi(context: Context) : DefaultTrackSelector(context) {
    private val app = context.applicationContext

    override fun selectAudioTrack(
        mappedTrackInfo: MappedTrackInfo,
        rendererFormatSupports: Array<Array<IntArray>>,
        rendererMixedMimeTypeAdaptationSupports: IntArray,
        params: Parameters,
    ): Pair<ExoTrackSelection.Definition, Int>? {
        val privzeta = super.selectAudioTrack(mappedTrackInfo, rendererFormatSupports, rendererMixedMimeTypeAdaptationSupports, params)
            ?: return null
        return try {
            val izbira = privzeta.first
            val r = privzeta.second
            // Prilagodljiv zvok (vec kakovosti iste sledi v HLS/DASH): izbira ostane Media3.
            if (izbira.tracks.size != 1) return privzeta
            val skupine = mappedTrackInfo.getTrackGroups(r)
            val sledi = ArrayList<ZvokPravila.Sled>()
            val kje = ArrayList<kotlin.Pair<TrackGroup, Int>>()
            var izbrana = -1
            for (g in 0 until skupine.length) {
                val skupina = skupine[g]
                for (t in 0 until skupina.length) {
                    val f = skupina.getFormat(t)
                    if (skupina == izbira.group && t == izbira.tracks[0]) izbrana = sledi.size
                    sledi.add(ZvokPravila.Sled(f.sampleMimeType, f.channelCount, f.language, f.label,
                        komentar = f.roleFlags and KOMENTAR != 0,
                        podprta = RendererCapabilities.getFormatSupport(rendererFormatSupports[r][g][t]) == C.FORMAT_HANDLED))
                    kje.add(skupina to t)
                }
            }
            if (sledi.size < 2) return privzeta
            val boljsa = ZvokPravila.boljsaSled(sledi, izbrana, ZvokIzhod.predaja(app), ZvokIzhod.kanalovPcm(app)) ?: return privzeta
            Log.i("SafeerOsMedia", "zvocna sled: ${sledi[boljsa].mime} ${sledi[boljsa].kanalov}k namesto privzete " +
                "${sledi[izbrana].mime} ${sledi[izbrana].kanalov}k (isti jezik, izhod odda vec)")
            Pair(ExoTrackSelection.Definition(kje[boljsa].first, kje[boljsa].second), r)
        } catch (e: Throwable) {
            Log.w("SafeerOsMedia", "zvocna sled: izbira ostane privzeta (${e.javaClass.simpleName})")
            privzeta
        }
    }

    private companion object {
        const val KOMENTAR = C.ROLE_FLAG_COMMENTARY or C.ROLE_FLAG_DESCRIBES_VIDEO or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND
    }
}
