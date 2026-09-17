package si.safeer.tv.os

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Ena povezava s srediscem za ves proces. Zasloni se prijavijo (dodaj) in odjavijo (odstrani);
 * povezava se vzpostavi ob prvem in pade sele nekaj sekund po zadnjem - prehod med zasloni
 * (dom -> datoteke -> predvajalnik) je brez ponovnega povezovanja. Zeton pride od Safeer
 * Browserja na tem televizorju (Sorodnik), brez kode.
 */
class LinkUpravitelj private constructor(private val app: Application) : LinkOdjemalec.Poslusalec {

    companion object {
        @Volatile private var primerek: LinkUpravitelj? = null
        /** Ena povezava v Link za ves proces brskalnika (domaci zaslon, datoteke, predvajalnik). */
        fun pridobi(context: Context): LinkUpravitelj = primerek ?: synchronized(this) {
            primerek ?: LinkUpravitelj(context.applicationContext as Application).also { primerek = it }
        }
    }

    val odjemalec = LinkOdjemalec(app)
    var povezan = false
        private set
    var imeSredisca: String = ""
        private set
    var naprave: List<LinkOdjemalec.Naprava> = emptyList()
        private set
    /** Zadnje sporocilo o stanju: "povezan", "povezujem", "ni", "ni_linka", "krajevni". */
    var stanje: String = "povezujem"
        private set

    /** Brez Safeer Linka: Safeer OS dela samo z viri televizorja. */
    fun jeKrajevni(): Boolean = Nacin.jeKrajevni(app)

    /** Uporabnika je treba enkrat vprasati (sredisce ne tece in se ni izbral). */
    fun vprasamoZaNacin(): Boolean = Nacin.vprasamo(app)

    /** Uporabnik je izbral Safeer Link: ce sredisce ne tece, ga prizgemo, in se povezemo. */
    fun vklopiLink() {
        Nacin.nastavi(app, Nacin.LINK)
        tece = false
        zazeni()
    }

    /** Uporabnik je izbral krajevni nacin: povezave ni in je ne vzpostavljamo. */
    fun krajevniNacin() {
        Nacin.nastavi(app, Nacin.KRAJEVNI)
        ustaviZares()
        pozabiNaprave()
        javi(false, "krajevni")
    }

    /** Naprave iz prejsnje povezave niso vec dosegljive: seznam pocistimo, da ga zasloni ne kazejo. */
    private fun pozabiNaprave() {
        naprave = emptyList()
        for (p in poslusalci) p.naNaprave(naprave)
    }

    private val poslusalci = CopyOnWriteArraySet<LinkOdjemalec.Poslusalec>()
    private val glavna = Handler(Looper.getMainLooper())
    private var prosimZaPoverilnice = false
    private var tece = false
    private val ustavitev = Runnable { if (poslusalci.isEmpty()) ustaviZares() }

    init { odjemalec.poslusalec = this }

    fun dodaj(p: LinkOdjemalec.Poslusalec) {
        poslusalci.add(p)
        glavna.removeCallbacks(ustavitev)
        if (!tece) zazeni() else p.naStanje(povezan, stanje)
        if (naprave.isNotEmpty()) p.naNaprave(naprave)
    }

    fun odstrani(p: LinkOdjemalec.Poslusalec) {
        poslusalci.remove(p)
        if (poslusalci.isEmpty()) glavna.postDelayed(ustavitev, 6_000)
    }

    /** Racunalniki v Linku, ki delijo datoteke (Safeer Control z izbranimi mapami). */
    fun racunalnikiZDatotekami(): List<LinkOdjemalec.Naprava> =
        naprave.filter { it.zmoznosti.contains("files") && it.id != Identiteta.id(app) }

    fun ukaz(cilj: String, dejanje: String, parametri: JSONObject, potekMs: Long = 10_000, odgovor: LinkOdjemalec.Odgovor) =
        odjemalec.ukaz(cilj, dejanje, parametri, potekMs, odgovor)

    private fun zazeni() {
        // Sredisce ze tece (uporabnik ima Safeer Link vklopljen v brskalniku): vstopimo brez vprasanja.
        // Ce ne tece, ga prizgemo samo, kadar je uporabnik Safeer Link izrecno izbral.
        if (Nacin.jeKrajevni(app)) { tece = false; pozabiNaprave(); javi(false, "krajevni"); return }
        if (!Nacin.linkZeTece() && !Nacin.jeLink(app)) { tece = false; pozabiNaprave(); javi(false, "ni_linka"); return }
        tece = true
        val shranjene = if (Nacin.linkZeTece()) Identiteta.beri(app) else null
        if (shranjene != null) {
            javi(false, "povezujem")
            odjemalec.zazeni(shranjene)
        } else {
            zahtevajPoverilnice()
        }
    }

    private fun ustaviZares() {
        tece = false
        odjemalec.ustavi()
        povezan = false
    }

    private fun zahtevajPoverilnice() {
        if (prosimZaPoverilnice) return
        prosimZaPoverilnice = true
        javi(false, "povezujem")
        Sorodnik.zahtevaj(app, dovoliZagon = Nacin.jeLink(app)) { p ->
            prosimZaPoverilnice = false
            if (p == null) { javi(false, if (Nacin.linkZeTece()) "ni" else "ni_linka"); return@zahtevaj }
            Identiteta.shrani(app, p)
            if (tece) odjemalec.zazeni(p)
        }
    }

    private fun javi(povezan: Boolean, stanje: String) {
        this.povezan = povezan
        this.stanje = stanje
        for (p in poslusalci) p.naStanje(povezan, stanje)
    }

    // -------------------------------------------------------------- iz odjemalca (glavna nit)

    override fun naStanje(povezan: Boolean, sporocilo: String) {
        imeSredisca = odjemalec.imeSredisca
        javi(povezan, if (povezan) "povezan" else "povezujem")
    }

    override fun naNaprave(naprave: List<LinkOdjemalec.Naprava>) {
        imeSredisca = odjemalec.imeSredisca
        this.naprave = naprave
        for (p in poslusalci) p.naNaprave(naprave)
    }

    override fun naNaslov(url: String, naslov: String, od: String) { for (p in poslusalci) p.naNaslov(url, naslov, od) }

    override fun naBesedilo(besedilo: String, od: String) { for (p in poslusalci) p.naBesedilo(besedilo, od) }

    override fun naZavrnitev() {
        // Sredisce je bilo ponastavljeno ali je Safeer OS odstranjen s seznama: vstopimo znova brez kode.
        Identiteta.pozabi(app)
        zahtevajPoverilnice()
    }
}
