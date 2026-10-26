// Media3 oznacuje BaseDataSource kot @UnstableApi (se lahko spremeni med razlicicami); enako kot DvdVir.kt.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * ExoPlayer bere film z Useneta neposredno iz [UsenetBralnik.Bralnik] - brez lokalnega streznika HTTP. Meritev na JVM
 * 26. 10. 2026 (3 ponovitve izmenicno, clanki v RAM): neposredno 615-776 MiB/s in 0,44-0,59 s CPU za 96 MiB, prek
 * streznika HTTP 439-683 MiB/s in 0,53-0,83 s CPU (12-43 % vec CPU); prek NNTP/TLS razlike ni (prevlada Usenet),
 * promet enak. Na TV steje CPU, na Androidu pa lahko vrata na 127.0.0.1 doseze vsaka druga aplikacija - zato brez
 * streznika. Ovoj nad cistim
 * [UsenetBralnik.Branje], ki je preizkusen v tests/UsenetTokTest.kt; tu je samo pogodba Media3. Naslov `safeer-usenet:`
 * nima ne zetona ne imena - bralnik je ze odprt in ga poda klicatelj. ExoPlayer bere na svoji niti (nalagalnik), ne na
 * glavni.
 */
class UsenetMedia3Vir(private val bralnik: UsenetBralnik.Bralnik) : BaseDataSource(true) {
    companion object {
        const val SHEMA = "safeer-usenet"
        val NASLOV: Uri = Uri.parse("$SHEMA:film")
    }

    /**
     * Tovarna za ExoPlayer: vsak vir bere iz istega bralnika (skok = nov open). Lastnistvo (pregled Grok 61 W4): bralnik
     * je v lasti tovarne - kdor jo ustvari, ob koncu predvajanja (po player.release()) poklice [close], ki zapre bralnik
     * (niti, LRU). Posamezen vir bralnika nikoli ne zapre, ker ExoPlayer ob vsakem skoku zapre in odpre nov vir.
     * Preklic nalaganja: ExoPlayer prekine nit nalagalnika; bralnik cakanje prekine z java.io.InterruptedIOException
     * (Media3 jo razume kot preklic, ne kot napako vira), branje vnaprej pa se ob skoku zavrze.
     */
    class Tovarna(private val bralnik: UsenetBralnik.Bralnik) : DataSource.Factory, java.io.Closeable {
        override fun createDataSource(): DataSource = UsenetMedia3Vir(bralnik)
        override fun close() = bralnik.close()
    }

    private val branje = UsenetBralnik.Branje(bralnik)
    private var uri: Uri? = null
    private var odprto = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val dolzina = if (dataSpec.length == C.LENGTH_UNSET.toLong()) -1L else dataSpec.length
        val n = branje.open(dataSpec.position, dolzina)
        odprto = true
        transferStarted(dataSpec)
        return n
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = branje.read(buffer, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        branje.close()
        uri = null
        if (odprto) {
            odprto = false
            transferEnded()
        }
    }
}
