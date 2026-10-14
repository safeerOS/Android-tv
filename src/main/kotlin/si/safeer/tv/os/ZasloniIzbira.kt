package si.safeer.tv.os

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R

/**
 * Povezani zasloni na zaslonu (pravila: [ZasloniPravila]): izbira med vsemi napravami, katerih zaslon lahko ta naprava
 * gleda in upravlja. Racunalnik z deljenjem (in racunalnik brez njega - za prosnjo za dostop) odpre ZaslonActivity;
 * telefon, tablica in televizor dobijo `screen.start` in zaslon delijo sami, ko njihov uporabnik potrdi zajem.
 */
object ZasloniIzbira {

    fun kandidati(a: Activity, link: LinkUpravitelj): List<ZasloniPravila.Kandidat> =
        ZasloniPravila.kandidati(
            link.naprave.filter { !link.jeTaNaprava(it) }
                .map { ZasloniPravila.Naprava(it.id, DatotekeActivity.lepoIme(it.ime).ifBlank { it.ime }, it.vloga, it.platforma, it.zmoznosti) },
            Identiteta.id(a))

    /** Vrstica izbire: izklopljen racunalnik sivo, z enovrsticnim namigom pod imenom. */
    fun napis(a: Activity, k: ZasloniPravila.Kandidat): CharSequence {
        if (k.vrsta != ZasloniPravila.Vrsta.NAMIZJE_IZKLOPLJENO) return k.ime
        val b = SpannableStringBuilder(k.ime).append("\n")
        val zacetek = b.length
        b.append(a.getString(R.string.os_zasloni_izklopljeno))
        b.setSpan(RelativeSizeSpan(0.8f), zacetek, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.setSpan(ForegroundColorSpan(Color.GRAY), 0, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return b
    }

    /**
     * Odpre zaslon kandidata. [odpriRacunalnik] odpre ZaslonActivity za racunalnik z danim id-jem (tudi izklopljen:
     * obicajna zahteva, da racunalnik lahko pokaze prosnjo za dostop ali pove, da deljenje ni vklopljeno).
     */
    fun odpri(a: Activity, link: LinkUpravitelj, k: ZasloniPravila.Kandidat, odpriRacunalnik: (String) -> Unit) {
        if (k.vrsta == ZasloniPravila.Vrsta.ANDROID) glejNapravo(a, link, k) else odpriRacunalnik(k.id)
    }

    /** Ena moznost se odpre takoj; vec moznosti: izbira. Vrne false, ce kandidatov ni. */
    fun izberi(
        a: Activity, link: LinkUpravitelj, naslov: String, kandidati: List<ZasloniPravila.Kandidat>,
        pokazi: (AlertDialog) -> Unit, odpriRacunalnik: (String) -> Unit,
    ): Boolean {
        if (kandidati.isEmpty()) return false
        if (kandidati.size == 1) { odpri(a, link, kandidati[0], odpriRacunalnik); return true }
        val d = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(naslov)
            .setItems(kandidati.map { napis(a, it) }.toTypedArray()) { _, i -> kandidati.getOrNull(i)?.let { odpri(a, link, it, odpriRacunalnik) } }
            .setNegativeButton(a.getString(R.string.os_preklici), null)
            .create()
        pokazi(d)
        return true
    }

    /**
     * Telefon, tablica ali televizor: `screen.start`. Naprava vprasa svojega uporabnika za zajem zaslona (vsako sejo)
     * in sliko poslje tej napravi prek sredisca (share.screen); to tu odpremo, ker smo jo pravkar zahtevali sami
     * ([ZasloniPravila.pricakovani]).
     */
    fun glejNapravo(a: Activity, link: LinkUpravitelj, k: ZasloniPravila.Kandidat) {
        ZasloniPravila.pricakovani.pricakuj(k.id, System.currentTimeMillis())
        // Sporocilo pride tudi, ce je klicoci zaslon medtem zaprt (ZaslonActivity se po izbiri zapre): zato kontekst aplikacije.
        val c = a.applicationContext
        link.ukaz(k.id, "screen.start", JSONObject(), 15_000, LinkOdjemalec.Odgovor { izid, napaka ->
            val besedilo = when {
                izid == null -> c.getString(R.string.os_zasloni_napaka, k.ime, napaka)
                izid.optBoolean("ok") -> c.getString(R.string.os_zasloni_potrdi, k.ime)
                else -> si.safeer.tv.cast.ProsnjeDostopa.sporociloProsilcu(c, izid, k.ime)
                    ?: if (izid.optString("code") == "neznano_dejanje") c.getString(R.string.os_zasloni_posodobi, k.ime)
                    else c.getString(R.string.os_zasloni_napaka, k.ime, izid.optString("message").ifBlank { napaka })
            }
            try { Toast.makeText(c, besedilo, Toast.LENGTH_LONG).show() } catch (_: Throwable) { }
        })
    }
}
