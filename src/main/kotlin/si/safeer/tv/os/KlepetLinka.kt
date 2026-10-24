package si.safeer.tv.os

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import si.safeer.tv.R
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Safeer Chat: sporocila med napravami v Safeer Linku (telefon, tablica, TV, racunalnik).
 * Sredisce jih le posreduje (in pocaka, ce naprava ni povezana); hrani jih vsaka naprava sama
 * v Sporocilih, kot kanal "Safeer Link", en pogovor na napravo.
 */
object KlepetLinka {
    const val KANAL = "safeer-link"
    const val VRSTA = "safeer"
    const val ZMOZNOST = "chat"
    /** Pregled D5: ta aplikacija zna zahtevo za izbris (payload.delete) - objavi jo LinkOdjemalec ob prijavi. */
    const val ZMOZNOST_IZBRIS = SporocilaPravila.ZMOZNOST_IZBRIS
    private const val OBVESTILA = "safeer_sporocila"
    /** Skupina obvestil Sporocil in obvestilo-povzetek skupine. */
    private const val SKUPINA = "safeer_sporocila"
    private const val ID_POVZETKA = 4052

    /** Zaslon Sporocila se prijavi, da ob novem sporocilu takoj osvezi prikaz. */
    val poslusalci = CopyOnWriteArraySet<() -> Unit>()
    /** Pogovor, ki je trenutno odprt na zaslonu (zanj ne pokazemo obvestila). */
    @Volatile var odprtPogovor: String? = null

    fun zagotoviKanal(s: SporocilaShramba) {
        if (s.kanali().none { it.id == KANAL }) s.dodajKanal(SporocilaShramba.Kanal(KANAL, VRSTA, "Safeer Link", "povezan", JSONObject()))
    }

    /** Cas posiljatelja v nasi obliki (UTC, do sekunde); ob nerazumljivem casu cas prejema. */
    fun cas(iso: String): String {
        val t = iso.trim()
        return if (Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d.*""").matches(t) && (t.endsWith("Z") || t.contains("+00:00")))
            t.take(19) + "+00:00" else SporocilaKanali.cas(null)
    }

    /** optString vrne "null" za JSON null; tega ne smemo vzeti za naslov naprave. */
    private fun niz(o: JSONObject, kljuc: String): String = if (o.isNull(kljuc)) "" else o.optString(kljuc).trim()

    /**
     * chat.send ali share.text s sredisca (na niti povezave). Besedilo, poslano z »Poslji besedilo«, gre v isti
     * pogovor kot Safeer Chat: prej je bilo na zaslonu le nekaj sekund in ga potem ni bilo nikjer.
     * [obvestilo] = false, kadar je besedilo ze na zaslonu (okno v brskalniku).
     */
    fun prejmi(c: Context, json: JSONObject, obvestilo: Boolean = true) {
        val telo = json.optJSONObject("payload") ?: return
        val besedilo = telo.optString("text").take(16 * 1024)
        val posiljatelj = niz(json, "sender")
        val naprave = try { LinkUpravitelj.pridobi(c).naprave } catch (_: Throwable) { emptyList<LinkOdjemalec.Naprava>() }
        // Pogovor je fizicna naprava (kljuc), ce jo hub pozna - ne glede na to, katera aplikacija na njej pise.
        // chat.send jo pove sam (sender_device); share.text je ne nosi, zato jo poiscemo v seznamu naprav ali
        // izpeljemo iz id-ja prijave (n-<kljuc>-control -> n-<kljuc>), da obe vrsti pristaneta v istem pogovoru.
        val od = niz(json, "sender_device")
            .ifBlank { naprave.firstOrNull { it.id == posiljatelj }?.naprava.orEmpty() }
            .ifBlank { LinkUpravitelj.fizicnaNaprava(posiljatelj) }
        val izbrisi = niz(telo, "delete")
        if (izbrisi.isNotBlank()) { izbrisiNaZahtevo(c, od, izbrisi); return }
        if (besedilo.isBlank() || od.isBlank()) return
        // Ime, kot ga uporabnik vidi v seznamu naprav (vzdevek), sicer ime, ki ga je dal hub.
        val izSeznama = imeNaprave(naprave, od) ?: naprave.firstOrNull { it.id == posiljatelj }?.ime
        val cas = cas(telo.optString("created_at"))
        val s = SporocilaShramba(c)
        val znano = s.pogovori().firstOrNull { it.kanalId == KANAL && it.id == od }?.ime
        // Hub pozna trenutno ime (tudi vzdevek); shranjeno ime je lahko zastarelo, zato pride sele za njim.
        val odHuba = niz(json, "sender_name").takeIf { it.isNotBlank() && it != posiljatelj && it != od }
        val ime = izSeznama?.takeIf { it.isNotBlank() } ?: odHuba ?: znano?.takeIf { it.isNotBlank() } ?: od
        zagotoviKanal(s)
        val id = "chat:" + json.optString("id").ifBlank { System.nanoTime().toString() }
        // Pregled D4: posiljatelj ga je izbrisal, se preden je prislo - ne shranimo ga.
        if (s.porabiIzbrisVnaprej(KANAL, id, od, System.currentTimeMillis())) return
        val odprt = odprtPogovor == od
        // Pregled D2: ponovna dostava (ze shranjeno) ali krajevno izbrisano - brez predogleda, neprebranega in obvestila.
        if (!s.shraniSporocilo(KANAL, SporocilaShramba.Sporocilo(id, od, "noter", besedilo, cas), samoNovo = true)) return
        s.posodobiPogovor(SporocilaShramba.Pogovor(od, KANAL, od, ime, "", besedilo.replace('\n', ' ').take(240),
            if (odprt) 0 else 1, cas), pristej = true)
        for (p in poslusalci) try { p() } catch (_: Throwable) { }
        if (!odprt && obvestilo) obvesti(c, od, ime, besedilo)
    }

    /**
     * Pogovori Linka po seznamu naprav: vsak dobi trenutno ime naprave (kot v seznamu naprav), pogovori iste
     * fizicne naprave pod starimi id-ji (id prijave namesto kljuca) pa se zdruzijo v enega. Vrne true ob spremembi.
     */
    /**
     * Ime fizicne naprave za pogovor: ena naprava ima lahko vec prijav (Safeer Control, zaslon, brskalnik).
     * Prednost ima prijava, ki zna Safeer Chat, in ime z oznako naprave (»Safeer Control (pisarna)«)
     * pred splosnim imenom programa.
     */
    fun imeNaprave(naprave: List<LinkOdjemalec.Naprava>, kljuc: String): String? =
        naprave.filter { it.ime.isNotBlank() && (it.naprava.ifBlank { it.id } == kljuc || it.id == kljuc) }
            .sortedWith(compareByDescending<LinkOdjemalec.Naprava> { it.zmoznosti.contains(ZMOZNOST) }
                .thenByDescending { it.ime.contains('(') }
                .thenByDescending { it.zmoznosti.size })
            .firstOrNull()?.ime

    /** Ime za prikaz: pri racunalnikih je ime programa odvec (kanal ze pove »Safeer Link«), pomembno je ime racunalnika. */
    fun prikaznoIme(ime: String): String =
        Regex("^Safeer (?:Control|Link|OS(?: Mobile)?) \\((.+)\\)$").find(ime.trim())?.groupValues?.get(1) ?: ime

    fun uskladi(s: SporocilaShramba, naprave: List<LinkOdjemalec.Naprava>): Boolean {
        if (naprave.isEmpty()) return false
        var spremenjeno = false
        for (p in s.pogovori().filter { it.kanalId == KANAL }) {
            val n = naprave.firstOrNull { it.naprava == p.id || it.id == p.id }
            if (n == null) {
                // Pogovor pod neznanim id (npr. iz starejse razlicice): pridruzi ga pogovoru iste naprave z enakim imenom.
                val isti = s.pogovori().firstOrNull { it.kanalId == KANAL && it.id != p.id && it.ime == p.ime &&
                    naprave.any { d -> d.naprava == it.id || d.id == it.id } }
                if (isti != null) spremenjeno = s.zdruziPogovor(KANAL, p.id, isti.id, isti.ime) || spremenjeno
                continue
            }
            val kljuc = n.naprava.ifBlank { n.id }
            val ime = imeNaprave(naprave, kljuc) ?: n.ime
            if (kljuc != p.id) {
                spremenjeno = s.zdruziPogovor(KANAL, p.id, kljuc, ime) || spremenjeno
                if (odprtPogovor == p.id) odprtPogovor = kljuc
            } else if (ime.isNotBlank() && ime != p.ime) {
                s.preimenujPogovor(KANAL, p.id, ime); spremenjeno = true
            }
        }
        return spremenjeno
    }

    /** Nova naprava v pogovoru (uporabnik ji pise prvi): pogovor obstaja, preden je kaj poslano. */
    fun zacniPogovor(s: SporocilaShramba, napravaId: String, ime: String): SporocilaShramba.Pogovor {
        zagotoviKanal(s)
        s.pogovori().firstOrNull { it.kanalId == KANAL && it.id == napravaId }?.let { obstojeci ->
            if (ime.isBlank() || ime == obstojeci.ime) return obstojeci
            // Uporabnik je izbral napravo po imenu iz seznama: pogovor naj ga nosi tudi naprej.
            val p = obstojeci.copy(ime = ime, neprebrano = 0)
            s.posodobiPogovor(p, pristej = true)
            return p
        }
        val p = SporocilaShramba.Pogovor(napravaId, KANAL, napravaId, ime, "", "", 0, SporocilaKanali.cas(null))
        s.posodobiPogovor(p, pristej = false)
        return p
    }

    /**
     * Poslje po Linku; vrne sporocilo za lokalni zapis ali vrze izjemo (brez povezave, zavrnjeno). Krajevni id nosi id
     * sporocila chat.send, pod katerim ga hrani prejemnik (naloga 116: izbris na vseh napravah).
     */
    fun poslji(c: Context, pogovorId: String, besedilo: String): Pair<SporocilaShramba.Sporocilo, String> {
        val cas = SporocilaKanali.cas(null)
        val skupni = java.util.UUID.randomUUID().toString()
        val izid = LinkUpravitelj.pridobi(c).odjemalec.posljiKlepet(pogovorId, besedilo, cas, id = skupni)
        if (izid != "accepted" && izid != "queued") throw NapakaKlepeta(izid)
        return SporocilaShramba.Sporocilo(SporocilaPravila.PREDPONA_POSLANO + skupni, pogovorId, "ven", besedilo, cas) to izid
    }

    // ------------------------------------------------------------------ izbris na vseh napravah (naloga 116)
    /**
     * Besedilo zahteve za izbris: sredisce sprejme samo neprazen chat.send. Naprava brez 116 ga pokaze kot sporocilo s
     * kosem - razumljivo, ne prazno.
     */
    const val BESEDILO_IZBRISA = "\uD83D\uDDD1"

    /** Ena posiljka cakajocih izbrisov hkrati (seznam naprav pride ob vsaki spremembi). */
    private val posiljaCakajoca = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * »Izbrisi na vseh mojih napravah« za lastno poslano sporocilo [s] v pogovoru [pogovorId]: izbrise ga tu in poslje
     * zahtevo napravi, ki ga je prejela. Samo ta naprava ga ima (Safeer Chat je pogovor dveh naprav); drugim napravam
     * zahteve ne posiljamo, ker bi v vrsti sredisca za napravo brez povezave izrinile prava sporocila.
     * Vrne true, ce je sredisce zahtevo sprejelo (dostavljeno ali v vrsti do povezave naprave); false, ce caka tu
     * (ta naprava je brez povezave) - poslje jo [posljiCakajocaBrisanja] ob naslednji uskladitvi. Ne klici na glavni niti.
     */
    fun izbrisiNaVseh(c: Context, pogovorId: String, s: SporocilaShramba.Sporocilo): Boolean {
        val skupni = SporocilaPravila.skupniId(s.id, s.smer) ?: return false
        val shramba = SporocilaShramba(c)
        shramba.izbrisiSporocilo(KANAL, pogovorId, s.id)
        val b = SporocilaShramba.CakajoceBrisanje(skupni, pogovorId, System.currentTimeMillis())
        shramba.dodajCakajoceBrisanje(b.id, b.cilj, b.zapisano)
        return posljiBrisanje(c, shramba, b)
    }

    /** Vse cakajoce izbrise poskusi poslati znova (LinkUpravitelj ob seznamu naprav). Ne klici na glavni niti. */
    fun posljiCakajocaBrisanja(c: Context) {
        if (!posiljaCakajoca.compareAndSet(false, true)) return
        try {
            val shramba = SporocilaShramba(c)
            for (b in shramba.cakajocaBrisanja()) posljiBrisanje(c, shramba, b)
        } catch (_: Throwable) {
        } finally { posiljaCakajoca.set(false) }
    }

    /**
     * Pregled D5: ali naprava pogovora [pogovorId] zna izbris ([SporocilaPravila.izbrisNaDrugi]) - po vseh njenih prijavah
     * v seznamu Linka [naprave] (pogovor je fizicna naprava, kot v [imeNaprave]).
     */
    fun izbrisNaDrugi(naprave: List<LinkOdjemalec.Naprava>, pogovorId: String): Int =
        SporocilaPravila.izbrisNaDrugi(naprave.filter { it.naprava.ifBlank { it.id } == pogovorId || it.id == pogovorId }.map { it.zmoznosti })

    private fun posljiBrisanje(c: Context, shramba: SporocilaShramba, b: SporocilaShramba.CakajoceBrisanje): Boolean {
        // Naprava je v seznamu, a izbrisa ne zna (npr. starejse namizje): zahteve ne posljemo - pokazala bi jo kot sporocilo.
        val naprave = try { LinkUpravitelj.pridobi(c).naprave } catch (_: Throwable) { emptyList<LinkOdjemalec.Naprava>() }
        // Pregled 4: naprava izbrisa (se) ne javi - zahteva ostane v cakanju (ne zavrzemo je), dokler ni prestara.
        if (izbrisNaDrugi(naprave, b.cilj) == SporocilaPravila.NA_DRUGI_NE) {
            if (SporocilaPravila.brisanjeBrezZmoznosti(b.zapisano, System.currentTimeMillis()) == SporocilaPravila.BRISANJE_OPUSTI)
                shramba.odstraniCakajoceBrisanje(b.id)
            return false
        }
        val izid = try {
            LinkUpravitelj.pridobi(c).odjemalec.posljiKlepet(b.cilj, BESEDILO_IZBRISA, SporocilaKanali.cas(null), izbrisi = b.id)
        } catch (_: Throwable) { "napaka" }
        val odlocitev = SporocilaPravila.izidBrisanja(izid, b.zapisano, System.currentTimeMillis())
        if (odlocitev != SporocilaPravila.BRISANJE_PONOVI) shramba.odstraniCakajoceBrisanje(b.id)
        return odlocitev == SporocilaPravila.BRISANJE_KONCANO
    }

    /**
     * Zahteva naprave [od] za izbris sporocila s skupnim id-jem [skupni]: izbrise se samo, ce ga je poslala ta naprava
     * ([SporocilaPravila.zahtevaZaIzbrisVelja]). Ze izbrisano sporocilo se prezre; se neznano (zahteva je prisla pred njim,
     * pregled D4) pusti zapis, da se izvirnik ob prihodu ne shrani. Obvestilo o njem izgine.
     */
    private fun izbrisiNaZahtevo(c: Context, od: String, skupni: String) {
        if (od.isBlank()) return
        val s = SporocilaShramba(c)
        val id = SporocilaPravila.prejetiId(skupni)
        val sp = s.sporocilo(KANAL, id)
        if (sp == null) {
            if (!s.izbrisano(KANAL, id) && SporocilaPravila.smeZapisatiVnaprej(od, skupni))
                s.dodajIzbrisVnaprej(KANAL, id, od, System.currentTimeMillis())
            return
        }
        if (!SporocilaPravila.zahtevaZaIzbrisVelja(sp.smer, sp.pogovorId, od)) return
        s.izbrisiSporocilo(KANAL, sp.pogovorId, id)
        umakniObvestilo(c, sp.pogovorId)
        for (p in poslusalci) try { p() } catch (_: Throwable) { }
    }

    class NapakaKlepeta(val koda: String) : Exception(koda)

    /** Pogovor je odprt: njegovo obvestilo izgine. Povzetek skupine gre z zadnjim sporocilom (tudi, ce je ostal sam). */
    fun umakniObvestilo(c: Context, pogovorId: String? = null) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val id = pogovorId?.hashCode()
            if (id != null) nm.cancel(id)
            val ostala = nm.activeNotifications.count { it.notification.group == SKUPINA && it.id != ID_POVZETKA && it.id != id }
            if (ostala == 0) nm.cancel(ID_POVZETKA)
        } catch (_: Throwable) { }
    }

    private fun obvesti(c: Context, od: String, ime: String, besedilo: String) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(OBVESTILA) == null) {
                nm.createNotificationChannel(NotificationChannel(OBVESTILA, c.getString(R.string.os_meni_sporocila),
                    NotificationManager.IMPORTANCE_DEFAULT))
            }
            val namera = Intent(c, SporocilaActivity::class.java).putExtra(SporocilaActivity.EXTRA_POGOVOR, od)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = PendingIntent.getActivity(c, od.hashCode(), namera, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val g = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, OBVESTILA) else android.app.Notification.Builder(c)
            val obvestilo = g.setSmallIcon(R.drawable.os_ikona_sporocila)
                .setContentTitle(ime).setContentText(besedilo.take(200))
                .setStyle(android.app.Notification.BigTextStyle().bigText(besedilo.take(1000)))
                .setContentIntent(pi).setAutoCancel(true)
                // Svoja skupina: brez nje je Android sporocilo zdruzil z obvestili storitev Safeerja in dotik zdruzene
                // vrstice je odprl Domov namesto pogovora (izmerjeno 5. 10. 2026 na telefonu).
                .setGroup(SKUPINA).setCategory(android.app.Notification.CATEGORY_MESSAGE).build()
            nm.notify(od.hashCode(), obvestilo)
            // Povzetek skupine (Android ga pokaze sele pri vec sporocilih): dotik odpre Sporocila.
            val vsa = PendingIntent.getActivity(c, 0, Intent(c, SporocilaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val gp = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, OBVESTILA) else android.app.Notification.Builder(c)
            gp.setSmallIcon(R.drawable.os_ikona_sporocila).setContentTitle(c.getString(R.string.os_meni_sporocila))
                .setGroup(SKUPINA).setGroupSummary(true).setContentIntent(vsa).setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= 26) gp.setGroupAlertBehavior(android.app.Notification.GROUP_ALERT_CHILDREN)
            nm.notify(ID_POVZETKA, gp.build())
        } catch (_: Throwable) {
            // Brez dovoljenja za obvestila sporocilo vseeno ostane v Sporocilih.
        }
    }
}
