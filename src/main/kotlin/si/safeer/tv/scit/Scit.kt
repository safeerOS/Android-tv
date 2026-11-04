package si.safeer.tv.scit

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.safeer.threatfeed.PlainList
import java.io.File
import java.util.Properties

/**
 * Safeer Scit: filter DNS za ves televizor (vse aplikacije), brez roota.
 *
 * Deluje kot navidezno omrezje (VpnService), ki prestreze SAMO poizvedbe DNS: v tunel je usmerjen
 * en sam naslov - navidezni streznik DNS. Vsak drug promet gre mimo, kot prej. Poizvedbo za domeno
 * z oglasnih/sledilnih/zlonamernih seznamov (hagezi PRO, TIF, Fake, URLhaus, Phishing Army,
 * SI-CERT - isti seznami kot v brskalniku) zavrnemo (NXDOMAIN), vse drugo posredujemo pravemu
 * strezniku DNS omrezja. Ni oblaka, ni tujega streznika, nic ne zapusti televizorja.
 *
 * Stanje je v datotekah v filesDir/scit (storitev tece v svojem procesu, zato ne SharedPreferences):
 *  - vklopljen        obstaja, kadar je uporabnik Scit vklopil (po vklopu televizorja se zazene sam)
 *  - domene.bin       nabor blokiranih domen (DomenskiNabor), zgrajen iz seznamov brskalnika
 *  - statistika.properties  blokiranih/poizvedb danes, dan, stanje storitve
 *  - izjeme.txt       Webtor rezerva: gostitelji tokov, ki jih Scit ne blokira ([ScitIzjeme]; stalne izjeme so v kodi)
 *  - grozne.bin       samo seznami grozenj (ne oglasov): izjema jih nikoli ne umakne (pregled V1)
 *  - zadnja.txt       zadnjih najvec 20 ustavljenih imen z uro (pregled Scita 4. 11. 2026); osebni podatek: samo tu, brez
 *                     sinhronizacije in varnostne kopije (res/xml), ob izklopu pobrisano
 *  - izjeme_uporabnika.txt  imena, ki jih je uporabnik na zaslonu Scita spustil (30 dni, nikoli groznje; [ScitPregled])
 */
object Scit {
    private const val TAG = "SafeerScit"
    const val DNS_V4 = "10.111.222.2"
    const val DNS_V6 = "fd66:5afe:e2::2"

    fun mapa(context: Context): File = File(context.applicationContext.filesDir, "scit").apply { mkdirs() }
    fun naborDatoteka(context: Context): File = File(mapa(context), "domene.bin")
    private fun zastavica(context: Context): File = File(mapa(context), "vklopljen")
    fun statistikaDatoteka(context: Context): File = File(mapa(context), "statistika.properties")

    fun jeVklopljen(context: Context): Boolean = zastavica(context).exists()

    fun izjemeDatoteka(context: Context): File = File(mapa(context), "izjeme.txt")
    fun grozneDatoteka(context: Context): File = File(mapa(context), "grozne.bin")

    /**
     * Webtor rezerva (pregled V1): koncni gostitelj toka Webtor (zahteva se je zacela na https://webtor.io/.../resolve/,
     * odgovor 2xx - [si.safeer.tv.os.WebtorPravila.naucenGostitelj]) gre NATANKO med izjeme Scita ([ScitIzjeme.dodaj]: ni
     * javna pripona, ni na nasih seznamih grozenj in oglasov, najvec 20, 30 dni). Vrne true, ce je bil dodan na novo (proces
     * Scita izjeme prebere znova). Z delovne niti.
     */
    @Synchronized fun dovoliGostitelja(context: Context, gostitelj: String): Boolean {
        val f = izjemeDatoteka(context)
        val zdaj = System.currentTimeMillis()
        val prej = ScitIzjeme.preberi(try { if (f.isFile) f.readText() else null } catch (_: Throwable) { null }, zdaj)
        // Domene z nasih seznamov (oglasi in grozenje) nikoli ne umaknemo: preverimo oba nabora (mapirana, brez kopice).
        val nabori = listOf(naborDatoteka(context), grozneDatoteka(context)).mapNotNull { try { DomenskiNabor.Poizvedba.odpri(it) } catch (_: Throwable) { null } }
        val novo = ScitIzjeme.dodaj(prej, gostitelj, zdaj) { g -> nabori.any { it.jeBlokirana(g) } } ?: return false
        try {
            val zacasna = File(f.parentFile, f.name + ".tmp")
            zacasna.writeText(ScitIzjeme.zapis(novo))
            if (!zacasna.renameTo(f)) { f.delete(); if (!zacasna.renameTo(f)) return false }
        } catch (e: Throwable) { Log.w(TAG, "izjeme: ${e.javaClass.simpleName}"); return false }
        Log.i(TAG, "izjema Scita za gostitelja toka Webtor, izjem ${novo.size}")   // brez imena: imena ne gredo v logcat
        if (jeVklopljen(context) && jePripravljen(context)) ScitStoritev.osvezi(context)
        return true
    }

    /** VPN dovoljenje je dano (sistemsko okno je bilo potrjeno) - prepare vrne null. */
    fun jePripravljen(context: Context): Boolean = try { VpnService.prepare(context.applicationContext) == null } catch (_: Throwable) { false }

    fun imaNabor(context: Context): Boolean = naborDatoteka(context).length() > DomenskiNabor.GLAVA

    /** Vklop (dovoljenje mora biti ze dano) - zapise zastavico in zazene storitev. */
    fun vklopi(context: Context): Boolean {
        if (!jePripravljen(context)) return false
        oznaciPonujeno(context)
        try { zastavica(context).writeText("1") } catch (e: Throwable) { Log.w(TAG, "Zastavice ni mogoce zapisati: ${e.message}") }
        ScitStoritev.zazeni(context)
        return true
    }

    fun izklopi(context: Context) {
        oznaciPonujeno(context)   // uporabnikova izbira: ob zagonu ga ne ponudimo znova
        zastavica(context).delete()
        pocistiZadnja(context)   // tudi ce storitev ze ne tece (proces :scit jih pocisti ob ustavitvi se enkrat)
        ScitStoritev.ustavi(context)
    }

    // ------------------------------------------------------------------ zaslon Scita (pregled 4. 11. 2026)

    /** Privzeto vklopljen (4. 11. 2026): ponudba ob prvem zagonu je bila ze pokazana (ali je uporabnik sam odlocil). */
    fun ponujeno(context: Context): Boolean = File(mapa(context), "ponujeno").exists()
    fun oznaciPonujeno(context: Context) { try { File(mapa(context), "ponujeno").writeText("1") } catch (_: Throwable) { } }
    /** Scit je na tej napravi ze kdaj tekel (statistika obstaja): izklopljen je po uporabnikovi izbiri. */
    fun prejUporabljen(context: Context): Boolean = statistikaDatoteka(context).isFile

    fun zadnjaDatoteka(context: Context): File = File(mapa(context), "zadnja.txt")
    fun izjemeUporabnikaDatoteka(context: Context): File = File(mapa(context), "izjeme_uporabnika.txt")

    fun zadnja(context: Context): List<ScitPregled.Zadnje> =
        try { zadnjaDatoteka(context).takeIf { it.isFile }?.readText()?.let { ScitPregled.preberiZadnje(it) } ?: emptyList() } catch (_: Throwable) { emptyList() }

    fun zapisiZadnja(context: Context, seznam: List<ScitPregled.Zadnje>) = zapisiAtomsko(zadnjaDatoteka(context), ScitPregled.zapisZadnjih(seznam))

    fun pocistiZadnja(context: Context) { try { zadnjaDatoteka(context).delete() } catch (_: Throwable) { } }

    fun izjemeUporabnika(context: Context): Map<String, Long> =
        try { ScitPregled.preberiIzjeme(izjemeUporabnikaDatoteka(context).takeIf { it.isFile }?.readText(), System.currentTimeMillis()) } catch (_: Throwable) { emptyMap() }

    /** Webtorjeve naucene izjeme (za prikaz in preklic na zaslonu Scita). */
    fun izjemeWebtor(context: Context): Map<String, Long> =
        try { ScitIzjeme.preberi(izjemeDatoteka(context).takeIf { it.isFile }?.readText(), System.currentTimeMillis()) } catch (_: Throwable) { emptyMap() }

    /** Ime je na seznamu grozenj (grozne.bin); null = seznama grozenj se ni (gumba za izjemo takrat ni). */
    fun jeGroznja(context: Context, ime: String): Boolean? {
        val g = try { DomenskiNabor.Poizvedba.odpri(grozneDatoteka(context)) } catch (_: Throwable) { null } ?: return null
        return g.jeBlokirana(ime)
    }

    /** OK na ustavljenem imenu: spusti ga 30 dni (ne, ce je groznja). Vrne true, ce je izjema dodana. Z delovne niti. */
    @Synchronized fun spusti(context: Context, ime: String): Boolean {
        val g = try { DomenskiNabor.Poizvedba.odpri(grozneDatoteka(context)) } catch (_: Throwable) { null }
        val novo = ScitPregled.dodajIzjemo(izjemeUporabnika(context), ime, System.currentTimeMillis(), g != null) { g?.jeBlokirana(it) ?: true }
            ?: return false
        if (!zapisiAtomsko(izjemeUporabnikaDatoteka(context), ScitPregled.zapisIzjem(novo))) return false
        if (jeVklopljen(context) && jePripravljen(context)) ScitStoritev.osvezi(context)
        return true
    }

    /** Preklic izjeme (uporabnikove ali Webtorjeve naucene); stalnih Webtorjevih ni mogoce preklicati. */
    @Synchronized fun preklici(context: Context, ime: String) {
        zapisiAtomsko(izjemeUporabnikaDatoteka(context), ScitPregled.zapisIzjem(ScitPregled.prekliciIzjemo(izjemeUporabnika(context), ime)))
        val w = izjemeWebtor(context)
        val d = ScitIzjeme.normaliziraj(ime)
        if (d in w) zapisiAtomsko(izjemeDatoteka(context), ScitIzjeme.zapis(LinkedHashMap(w).apply { remove(d) }))
        if (jeVklopljen(context) && jePripravljen(context)) ScitStoritev.osvezi(context)
    }

    private fun zapisiAtomsko(f: File, besedilo: String): Boolean = try {
        val zacasna = File(f.parentFile, f.name + ".tmp")
        zacasna.writeText(besedilo)
        if (!zacasna.renameTo(f)) { f.delete(); zacasna.renameTo(f) } else true
    } catch (e: Throwable) { Log.w(TAG, "${f.name}: ${e.javaClass.simpleName}"); false }

    /** Po vklopu televizorja / posodobitvi: ce je bil Scit vklopljen in ima dovoljenje, ga zazene. */
    fun zagotovi(context: Context) {
        if (jeVklopljen(context) && jePripravljen(context)) ScitStoritev.zazeni(context)
    }

    /**
     * Iz seznamov brskalnika zgradi nabor domen (v ozadju klicatelja) in storitvi naroci, naj ga znova nalozi.
     * Gradi samo, ko se vhodni seznami (SHA-256) razlikujejo od zadnje gradnje: prej ob vsakem zagonu brskalnika,
     * ~15-20 MB prehodno in ~8 MB zapisa na flash (raziskava 4. 11. 2026).
     */
    /**
     * Nabor domen in nabor grozenj sta zgrajena iz teh seznamov (isti kljuc kot v [osveziNabor], iz metapodatkov agenta,
     * brez branja seznamov). Ce da, ob zagonu brskalnika domenskih seznamov ni treba brati (4. 11. 2026).
     */
    fun naborVeljaven(context: Context, seznami: List<com.safeer.threatfeed.PlainListInfo>): Boolean {
        val dns = seznami.filter { ScitViri.zaNabor(it.source) }
        if (dns.isEmpty()) return true
        val grozenj = dns.filter { ScitViri.zaGrozne(it.source) }
        return DomenskiNabor.jeZgrajenIz(naborDatoteka(context), DomenskiNabor.kljucVira(dns.map { it.source.id to it.sha256 })) &&
            DomenskiNabor.jeZgrajenIz(grozneDatoteka(context), DomenskiNabor.kljucVira(grozenj.map { it.source.id to it.sha256 }))
    }

    fun osveziNabor(context: Context, seznami: List<PlainList>): Int {
        val dns = seznami.filter { ScitViri.zaNabor(it.source) }
        if (dns.isEmpty()) return 0
        val grozenj = dns.filter { ScitViri.zaGrozne(it.source) }
        val cilj = naborDatoteka(context)
        val n = try {
            DomenskiNabor.zapisiCeSpremenjeno(cilj, DomenskiNabor.kljucVira(dns.map { it.source.id to it.sha256 }),
                dns.asSequence().map { it.entries.asSequence() }, dns.sumOf { it.entries.size })
        } catch (e: Throwable) {
            Log.w(TAG, "Nabora ni bilo mogoce zgraditi: ${e.message}"); return 0
        }
        // Pregled V1: seznami grozenj (ne oglasov) posebej - izjema Scita jih nikoli ne umakne.
        val g = try {
            DomenskiNabor.zapisiCeSpremenjeno(grozneDatoteka(context), DomenskiNabor.kljucVira(grozenj.map { it.source.id to it.sha256 }),
                grozenj.asSequence().map { it.entries.asSequence() }, grozenj.sumOf { it.entries.size })
        } catch (e: Throwable) { Log.w(TAG, "Nabora grozenj ni bilo mogoce zgraditi: ${e.javaClass.simpleName}"); 0 }
        if (n < 0 && g < 0) {
            Log.i(TAG, "Nabor domen nespremenjen (seznami enaki kot ob zadnji gradnji): brez gradnje")
            return DomenskiNabor.steviloVGlavi(cilj) ?: 0
        }
        Log.i(TAG, "Nabor domen: ${if (n < 0) "nespremenjen" else "$n domen"} iz ${dns.size} seznamov (${cilj.length() / 1024} KiB)" +
            if (g < 0) ", grozenj nespremenjen" else ", grozenj $g")
        if (jeVklopljen(context) && jePripravljen(context)) ScitStoritev.osvezi(context)
        return if (n < 0) DomenskiNabor.steviloVGlavi(cilj) ?: 0 else n
    }

    class Statistika(val blokiranih: Long, val poizvedb: Long, val dan: String, val stanje: String, val domen: Int,
                     /** Pregled Scita 4. 11. 2026: omrezje ne javi DNS (poizvedbe dobijo SERVFAIL, tujega ne izberemo). */
                     val brezKrajevnegaDns: Boolean = false)

    fun statistika(context: Context): Statistika {
        val p = Properties()
        try { statistikaDatoteka(context).inputStream().use { p.load(it) } } catch (_: Throwable) { }
        return Statistika(
            p.getProperty("blokiranih", "0").toLongOrNull() ?: 0,
            p.getProperty("poizvedb", "0").toLongOrNull() ?: 0,
            p.getProperty("dan", ""),
            p.getProperty("stanje", "ustavljen"),
            p.getProperty("domen", "0").toIntOrNull() ?: 0,
            p.getProperty("brez_dns", "0") == "1",
        )
    }

    fun zapisiStatistiko(context: Context, s: Statistika) {
        try {
            val p = Properties()
            p.setProperty("blokiranih", s.blokiranih.toString()); p.setProperty("poizvedb", s.poizvedb.toString())
            p.setProperty("dan", s.dan); p.setProperty("stanje", s.stanje); p.setProperty("domen", s.domen.toString())
            p.setProperty("brez_dns", if (s.brezKrajevnegaDns) "1" else "0")
            val cilj = statistikaDatoteka(context)
            val zacasna = File(cilj.parentFile, cilj.name + ".tmp")
            zacasna.outputStream().use { p.store(it, null) }
            if (!zacasna.renameTo(cilj)) { cilj.delete(); zacasna.renameTo(cilj) }
        } catch (e: Throwable) { Log.w(TAG, "Statistike ni mogoce zapisati: ${e.message}") }
    }

    /** Namera za sistemsko okno z dovoljenjem (null = ze dano). */
    fun dovoljenjeNamera(context: Context): Intent? = try { VpnService.prepare(context) } catch (_: Throwable) { null }
}
