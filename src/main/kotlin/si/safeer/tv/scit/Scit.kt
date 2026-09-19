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

    /** VPN dovoljenje je dano (sistemsko okno je bilo potrjeno) - prepare vrne null. */
    fun jePripravljen(context: Context): Boolean = try { VpnService.prepare(context.applicationContext) == null } catch (_: Throwable) { false }

    fun imaNabor(context: Context): Boolean = naborDatoteka(context).length() > DomenskiNabor.GLAVA

    /** Vklop (dovoljenje mora biti ze dano) - zapise zastavico in zazene storitev. */
    fun vklopi(context: Context): Boolean {
        if (!jePripravljen(context)) return false
        try { zastavica(context).writeText("1") } catch (e: Throwable) { Log.w(TAG, "Zastavice ni mogoce zapisati: ${e.message}") }
        ScitStoritev.zazeni(context)
        return true
    }

    fun izklopi(context: Context) {
        zastavica(context).delete()
        ScitStoritev.ustavi(context)
    }

    /** Po vklopu televizorja / posodobitvi: ce je bil Scit vklopljen in ima dovoljenje, ga zazene. */
    fun zagotovi(context: Context) {
        if (jeVklopljen(context) && jePripravljen(context)) ScitStoritev.zazeni(context)
    }

    /** Iz seznamov brskalnika zgradi nabor domen (v ozadju klicatelja) in storitvi naroci, naj ga znova nalozi. */
    fun osveziNabor(context: Context, seznami: List<PlainList>): Int {
        val dns = seznami.filter { !it.source.raw && !it.source.ipv4 }
        if (dns.isEmpty()) return 0
        val cilj = naborDatoteka(context)
        val n = try {
            DomenskiNabor.zapisi(cilj, dns.asSequence().map { it.entries.asSequence() })
        } catch (e: Throwable) {
            Log.w(TAG, "Nabora ni bilo mogoce zgraditi: ${e.message}"); return 0
        }
        Log.i(TAG, "Nabor domen: $n domen iz ${dns.size} seznamov (${cilj.length() / 1024} KiB)")
        if (jeVklopljen(context) && jePripravljen(context)) ScitStoritev.osvezi(context)
        return n
    }

    class Statistika(val blokiranih: Long, val poizvedb: Long, val dan: String, val stanje: String, val domen: Int)

    fun statistika(context: Context): Statistika {
        val p = Properties()
        try { statistikaDatoteka(context).inputStream().use { p.load(it) } } catch (_: Throwable) { }
        return Statistika(
            p.getProperty("blokiranih", "0").toLongOrNull() ?: 0,
            p.getProperty("poizvedb", "0").toLongOrNull() ?: 0,
            p.getProperty("dan", ""),
            p.getProperty("stanje", "ustavljen"),
            p.getProperty("domen", "0").toIntOrNull() ?: 0,
        )
    }

    fun zapisiStatistiko(context: Context, s: Statistika) {
        try {
            val p = Properties()
            p.setProperty("blokiranih", s.blokiranih.toString()); p.setProperty("poizvedb", s.poizvedb.toString())
            p.setProperty("dan", s.dan); p.setProperty("stanje", s.stanje); p.setProperty("domen", s.domen.toString())
            val cilj = statistikaDatoteka(context)
            val zacasna = File(cilj.parentFile, cilj.name + ".tmp")
            zacasna.outputStream().use { p.store(it, null) }
            if (!zacasna.renameTo(cilj)) { cilj.delete(); zacasna.renameTo(cilj) }
        } catch (e: Throwable) { Log.w(TAG, "Statistike ni mogoce zapisati: ${e.message}") }
    }

    /** Namera za sistemsko okno z dovoljenjem (null = ze dano). */
    fun dovoljenjeNamera(context: Context): Intent? = try { VpnService.prepare(context) } catch (_: Throwable) { null }
}
