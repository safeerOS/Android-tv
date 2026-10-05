// Media3: razclenjevalnik seznamov HLS je oznacen kot @UnstableApi.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable

/**
 * Razclenjevalnik seznamov HLS, ki pred branjem izpusti vrstice z neveljavnim datumom ([HlsPopravek]): kanal s takim
 * seznamom je prej odpovedal z napako razclenjevanja (crn zaslon), drugi predvajalniki pa ga predvajajo. Velja tudi za
 * vsako osvezitev seznama prenosa v zivo.
 */
class HlsSeznami : HlsPlaylistParserFactory {
    private val privzeta = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> = ovij(privzeta.createPlaylistParser())

    override fun createPlaylistParser(multivariantPlaylist: HlsMultivariantPlaylist, previousMediaPlaylist: HlsMediaPlaylist?): ParsingLoadable.Parser<HlsPlaylist> =
        ovij(privzeta.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun ovij(p: ParsingLoadable.Parser<HlsPlaylist>) = ParsingLoadable.Parser<HlsPlaylist> { uri, vhod ->
        val bajti = vhod.readBytes()
        val popravljen = HlsPopravek.pocisti(String(bajti, Charsets.UTF_8))
        if (popravljen != null) android.util.Log.i("SafeerOsMedia", "seznam HLS: izpuscene vrstice z neveljavnim datumom")
        p.parse(uri, java.io.ByteArrayInputStream(popravljen?.toByteArray(Charsets.UTF_8) ?: bajti))
    }
}
