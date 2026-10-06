package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import si.safeer.tv.R

/** Izbira zvocne sledi (vec jezikov ali komentar): gumb na telefonu, tipka na daljincu. Kot Podnapisi. */
object ZvocneSledi {

    fun posnetki(p: Player): List<Pair<Tracks.Group, Int>> =
        try {
            p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        } catch (_: Throwable) { emptyList() }

    /** Izbira ima smisel le pri vec kot eni sledi. */
    fun imaIzbiro(p: Player?): Boolean = p != null && posnetki(p).size > 1

    fun ime(a: Activity, g: Tracks.Group, i: Int, k: Int): String {
        val f = g.getTrackFormat(i)
        // Jezik ali ime sledi, kanali in zapis (»Angleščina · 5.1 · Dolby Atmos«): uporabnik ve, katero sled izbira.
        return ZvokPravila.opisSledi(f.label?.ifBlank { null } ?: Podnapisi.imeJezika(a, f.language.orEmpty()),
            f.channelCount, f.sampleMimeType, f.label).ifBlank { a.getString(R.string.os_mediji_zvocna_sled_st, k + 1) }
    }

    fun izberi(a: Activity, p: Player, poIzbiri: () -> Unit = {}) {
        val posnetki = posnetki(p)
        if (posnetki.size < 2) return
        val imena = posnetki.mapIndexed { k, (g, i) -> ime(a, g, i, k) }
        val trenutni = posnetki.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
        AlertDialog.Builder(a).setTitle(R.string.os_mediji_zvocna_sled)
            .setSingleChoiceItems(imena.toTypedArray(), trenutni) { d, k ->
                val (g, i) = posnetki[k]
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
                d.dismiss(); poIzbiri()
            }.show()
    }
}
