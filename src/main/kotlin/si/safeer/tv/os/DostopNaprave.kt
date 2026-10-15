package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import org.json.JSONObject
import si.safeer.tv.R
import si.safeer.tv.os.ProsnjaDostopa.Stanje

/**
 * Zavrnitev druge naprave (racunalnika) zaradi dostopa - skupno za Zaslon, Datoteke in vse, kar prosi racunalnik.
 * Pravila so v [ProsnjaDostopa]; tu so branje odgovora, besedila in ponavljanje zahteve, dokler prosnja caka.
 *
 * Ko racunalnik odgovori "access_request": "pending", uporabnik na njem vidi obvestilo »Dovoli / Zavrni«. Zahtevo
 * ([poskusi]) ponavljamo na [ProsnjaDostopa.PONOVI_MS] najvec [ProsnjaDostopa.NAJDLJE_MS]: takoj ko uporabnik na
 * racunalniku pritisne Dovoli, se zaslon odpre sam, brez dotika na tej napravi. Ko uporabnik zaslon zapusti, klicatelj
 * poklice [ustavi] (nic vec ne prosimo v ozadju).
 */
class DostopNaprave(private val glavna: Handler, private val poskusi: () -> Unit) {
    private var zacetek = 0L
    private val ponovno = Runnable { poskusi() }

    /** Prosnja caka in naslednji poskus je ze razporejen. */
    val caka: Boolean get() = zacetek != 0L

    /**
     * Racunalnik je odgovoril "pending": razporedi naslednji poskus in vrne preostali cas za prikaz ("1:57").
     * null: cakanja je konec (prosnja na racunalniku ostane - ko jo potrdi, uporabnik odpre znova).
     */
    fun cakaj(): String? {
        val zdaj = SystemClock.elapsedRealtime()
        if (zacetek == 0L) zacetek = zdaj
        glavna.removeCallbacks(ponovno)
        val zamik = ProsnjaDostopa.naslednjiPoskus(zacetek, zdaj)
        if (zamik == null) {
            zacetek = 0L
            return null
        }
        glavna.postDelayed(ponovno, zamik)
        return ProsnjaDostopa.preostalo(zacetek, zdaj)
    }

    /** Odgovor je prisel ali je uporabnik odsel: ne ponavljamo vec. */
    fun ustavi() {
        glavna.removeCallbacks(ponovno)
        zacetek = 0L
    }

    companion object {
        /** Stanje zavrnitve iz odgovora na ukaz; null, ce odgovor ni zavrnitev dostopa. */
        fun stanje(izid: JSONObject?): Stanje? {
            if (izid == null) return null
            val podatki = izid.optJSONObject("data")
            val prosnja = izid.optString("access_request", "")
                .ifBlank { podatki?.optString("access_request", "").orEmpty() }
            return ProsnjaDostopa.stanje(izid.optString("code", ""), podatki?.optString("reason", ""), prosnja)
        }

        /** Med cakanjem: kje uporabnik potrdi in koliko casa se cakamo. */
        fun besediloCakanja(c: Context, ime: String, preostalo: String): String =
            c.getString(R.string.os_prosnja_caka, ime, preostalo)

        /**
         * Besedilo za koncno stanje. [izklopljeno]: besedilo te zmoznosti, ko jo racunalnik sploh ne deli (stara
         * zavrnitev brez dostopa naprave); CAKA tu pomeni, da je cakanja konec.
         */
        fun besedilo(c: Context, stanje: Stanje, ime: String, izklopljeno: Int): String = when (stanje) {
            Stanje.CAKA -> c.getString(R.string.os_prosnja_poteklo, ime)
            Stanje.ZAVRNJENO -> c.getString(R.string.os_prosnja_zavrnjeno, ime)
            Stanje.NI_DOSTOPA -> c.getString(R.string.os_prosnja_ni_dovoljeno, ime)
            Stanje.IZKLOPLJENO -> c.getString(izklopljeno, ime)
        }
    }
}
