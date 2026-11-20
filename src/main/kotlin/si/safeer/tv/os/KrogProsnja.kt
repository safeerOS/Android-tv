package si.safeer.tv.os

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import si.safeer.tv.R
import si.safeer.tv.cast.Dostop
import si.safeer.tv.cast.DostopPravila
import si.safeer.tv.link.KrogProsnjaPravila as P
import si.safeer.tv.link.LinkVarnostPravila as D
import java.util.concurrent.ConcurrentHashMap

/**
 * »Zahtevaj poln dostop« (lastnik 20. 11. 2026): en klik na napravi v Safeer Linku; na drugi napravi uporabnik potrdi
 * (tiha pasica Dovoli / Zavrni); nato imata napravi obojestranski ozji krog in potrjeno usklajevanje kljucev.
 * Pravila (brez Androida): link/KrogProsnjaPravila. Prosnja pride samo od naprave, ki je ze v Link krogu zaupanja in je
 * dokazala kljuc (DostopPravila.samoZasciteno); odgovor velja samo za prosnjo, ki smo jo res poslali.
 */
object KrogProsnja {
    private const val TAG = "SafeerKrogProsnja"
    private val glavna = Handler(Looper.getMainLooper())

    /** Prosnja, ki caka na uporabnikovo odlocitev na tej napravi. */
    class Cakajoca(val id: String, val jedro: String, val ime: String, val nastala: Long)

    private val cakajoce = ConcurrentHashMap<String, Cakajoca>()    // po jedru posiljatelja
    private val zadnja = ConcurrentHashMap<String, Long>()          // jedro -> kdaj je nazadnje prosila
    private val poslane = ConcurrentHashMap<String, Long>()         // id cilja -> kdaj smo mu poslali prosnjo

    /** Zaslon v ospredju, ki prosnjo pokaze kot tiho pasico (OsActivity); null = pokaze se ob naslednjem odprtju. */
    @Volatile var prikaz: ((Cakajoca) -> Unit)? = null

    /** Najstarejsa se veljavna prosnja ali null. */
    fun cakajoca(): Cakajoca? {
        val zdaj = System.currentTimeMillis()
        cakajoce.values.removeIf { !P.velja(it.nastala, zdaj) }
        return cakajoce.values.minByOrNull { it.nastala }
    }

    // ------------------------------------------------------------------ prejemnik

    /** `circle.request` (glavna nit): odgovor je `status`: asked | ze | ni_krog | prehitro | prevec | napaka. */
    fun prejmiProsnjo(ctx: Context, posiljatelj: String, p: JSONObject): JSONObject {
        val a = ctx.applicationContext
        val zdaj = System.currentTimeMillis()
        fun odg(s: String) = JSONObject().put("status", s)
        if (posiljatelj.isBlank()) return odg("napaka")
        val jedro = try { Dostop.jedro(a, posiljatelj) } catch (_: Throwable) { "" }
        if (jedro.isBlank()) return odg("ni_krog")
        val clan = try { Dostop.clanKroga(a, jedro) } catch (_: Throwable) { false }
        val zeOdprto = try {
            Dostop.zmoznosti(a, posiljatelj).containsAll(DostopPravila.VSE_ZMOZNOSTI) && UskladitevKljucev.potrjena(a, posiljatelj)
        } catch (_: Throwable) { false }
        cakajoce.values.removeIf { !P.velja(it.nastala, zdaj) }
        val cakajocih = cakajoce.values.count { it.jedro != jedro }
        val o = P.odziv(clan, zeOdprto, zadnja[jedro] ?: 0L, cakajocih, zdaj)
        Log.i(TAG, "circle.request od ${D.oznakaZaDnevnik(posiljatelj)}: $o")
        return when (o) {
            P.Odziv.ZE_ODPRTO -> odg("ze")
            P.Odziv.ZAVRNI_NI_V_KROGU -> odg("ni_krog")
            P.Odziv.ZAVRNI_PREHITRO -> odg("prehitro")
            P.Odziv.ZAVRNI_PREVEC -> odg("prevec")
            P.Odziv.VPRASAJ -> {
                val ime = LinkUpravitelj.pridobi(a).naprave.firstOrNull { it.id == posiljatelj }?.ime?.takeIf { it.isNotBlank() }
                    ?: p.optString("from").take(60).ifBlank { posiljatelj.take(12) }
                val c = Cakajoca(posiljatelj, jedro, DatotekeActivity.lepoIme(ime), zdaj)
                cakajoce[jedro] = c
                zadnja[jedro] = zdaj
                prikaz?.invoke(c)
                odg("asked")
            }
        }
    }

    /** Uporabnik je pritisnil Dovoli: napravi odpremo vse, potrdimo kljuce in posljemo `circle.grant`. */
    fun dovoli(a: Activity, c: Cakajoca) {
        cakajoce.remove(c.jedro) ?: return
        if (!P.velja(c.nastala, System.currentTimeMillis()) || !(try { Dostop.clanKroga(a, c.jedro) } catch (_: Throwable) { false })) {
            Toast.makeText(a, a.getString(R.string.os_krog_stara), Toast.LENGTH_LONG).show(); return
        }
        odpri(a, c.id, c.ime)
        si.safeer.tv.link.DatotekeStreznik.poPrivolitvi(a)
        si.safeer.tv.link.VnosVklop.poPrivolitvi(a)
        LinkUpravitelj.pridobi(a.applicationContext).ukaz(c.id, P.DEJANJE_ODGOVOR, JSONObject().put("from", jaz(a)), 8_000,
            LinkOdjemalec.Odgovor { izid, napaka ->
                Log.i(TAG, "circle.grant -> ${D.oznakaZaDnevnik(c.id)}: ok=${izid?.optBoolean("ok")} napaka=${napaka.take(30)}")
                if (izid?.optBoolean("ok") != true) glavna.post { Toast.makeText(a, a.getString(R.string.os_krog_odgovor_ni, c.ime), Toast.LENGTH_LONG).show() }
            })
        Toast.makeText(a, a.getString(R.string.os_krog_dovoljeno, c.ime), Toast.LENGTH_LONG).show()
    }

    fun zavrni(c: Cakajoca) { cakajoce.remove(c.jedro) }

    // ------------------------------------------------------------------ posiljatelj

    /** Posljemo prosnjo; [nato] dobi: ok | ok_odpri | ze | ni_krog | prehitro | prevec | stara | ni_povezave | napaka. */
    fun poslji(ctx: Context, cilj: LinkOdjemalec.Naprava, nato: (String) -> Unit) {
        val a = ctx.applicationContext
        poslane[cilj.id] = System.currentTimeMillis()
        LinkUpravitelj.pridobi(a).ukaz(cilj.id, P.DEJANJE_PROSNJA, JSONObject().put("from", jaz(a)), 8_000, LinkOdjemalec.Odgovor { izid, napaka ->
            val d = izid?.optJSONObject("data")
            val s = d?.optString("status").orEmpty()
            Log.i(TAG, "circle.request -> ${D.oznakaZaDnevnik(cilj.id)}: ok=${izid?.optBoolean("ok")} status=$s napaka=${napaka.take(30)}")
            val izid2 = when {
                izid?.optBoolean("ok") == true && s == "asked" -> "ok"
                izid?.optBoolean("ok") == true && s in setOf("ze", "ni_krog", "prehitro", "prevec") -> s
                izid?.optString("code") == "neznano_dejanje" -> "stara"
                napaka == "ni_povezave" || napaka == "potek" -> "ni_povezave"
                else -> "napaka"
            }
            if (izid2 != "ok") poslane.remove(cilj.id)
            glavna.post { nato(izid2) }
        })
    }

    /** `circle.grant` (glavna nit): druga naprava je potrdila - odpremo ji vse in potrdimo kljuce. */
    fun prejmiOdgovor(ctx: Context, posiljatelj: String): JSONObject {
        val a = ctx.applicationContext
        val kdaj = poslane.remove(posiljatelj)
        if (!P.smeOdgovor(kdaj, System.currentTimeMillis())) return JSONObject().put("ok", false).put("reason", "ni_prosnje")
        val jedro = try { Dostop.jedro(a, posiljatelj) } catch (_: Throwable) { "" }
        if (jedro.isBlank() || !(try { Dostop.clanKroga(a, jedro) } catch (_: Throwable) { false })) return JSONObject().put("ok", false).put("reason", "ni_krog")
        val ime = DatotekeActivity.lepoIme(LinkUpravitelj.pridobi(a).naprave.firstOrNull { it.id == posiljatelj }?.ime.orEmpty())
        odpri(a, posiljatelj, ime)
        glavna.post { Toast.makeText(a, a.getString(R.string.os_krog_odobreno, ime), Toast.LENGTH_LONG).show() }
        return JSONObject().put("ok", true)
    }

    // ------------------------------------------------------------------ skupno

    private fun odpri(ctx: Context, id: String, ime: String) {
        Dostop.nastavi(ctx, id, DostopPravila.VSE_ZMOZNOSTI)
        UskladitevKljucev.potrdiNapravo(ctx, id, ime)
    }

    private fun jaz(ctx: Context): String = try {
        LinkUpravitelj.pridobi(ctx).naprave.firstOrNull { it.id == Identiteta.id(ctx) }?.ime
    } catch (_: Throwable) { null } ?: android.os.Build.MODEL
}
