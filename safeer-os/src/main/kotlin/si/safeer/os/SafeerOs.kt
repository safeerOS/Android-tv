package si.safeer.os

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Proces Safeer OS: ena povezava v Safeer Link za vse zaslone (dom, datoteke, predvajalnik). */
class SafeerOs : Application() {
    val link: LinkUpravitelj by lazy { LinkUpravitelj(this) }

    companion object {
        fun link(context: Context): LinkUpravitelj = (context.applicationContext as SafeerOs).link
    }
}

/**
 * Ena povezava s srediscem za ves proces. Zasloni se prijavijo (dodaj) in odjavijo (odstrani);
 * povezava se vzpostavi ob prvem in pade sele nekaj sekund po zadnjem - prehod med zasloni
 * (dom -> datoteke -> predvajalnik) je brez ponovnega povezovanja. Zeton pride od Safeer
 * Browserja na tem televizorju (Sorodnik), brez kode.
 */
class LinkUpravitelj(private val app: Application) : LinkOdjemalec.Poslusalec {

    val odjemalec = LinkOdjemalec(app)
    var povezan = false
        private set
    var imeSredisca: String = ""
        private set
    var naprave: List<LinkOdjemalec.Naprava> = emptyList()
        private set
    /** Zadnje sporocilo o stanju: "ni_brskalnika", "ni", "povezujem", "povezan". */
    var stanje: String = "povezujem"
        private set

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
        tece = true
        if (!Sorodnik.jeBrskalnikNamescen(app)) { javi(false, "ni_brskalnika"); return }
        val shranjene = Identiteta.beri(app)
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
        Sorodnik.zahtevaj(app) { p ->
            prosimZaPoverilnice = false
            if (p == null) { javi(false, "ni"); return@zahtevaj }
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
