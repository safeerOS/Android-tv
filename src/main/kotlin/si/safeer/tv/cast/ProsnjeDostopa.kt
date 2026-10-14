package si.safeer.tv.cast

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.R
import si.safeer.tv.cast.DostopPravila.Zahteva
import si.safeer.tv.cast.DostopPravila.Zmoznost
import si.safeer.tv.cast.ProsnjeDostopaPravila.Odlocitev
import si.safeer.tv.cast.ProsnjeDostopaPravila.Prosnja
import java.lang.ref.WeakReference

/**
 * Prosnja za dostop na TEJ napravi (pravila: [ProsnjeDostopaPravila]). Naprava v Linku, ki ji zmoznost manjka, dobi
 * zavrnitev kot doslej in polje `access_request`; tu pa uporabnik dobi obvestilo z gumboma Dovoli / Zavrni. Dotik
 * obvestila odpre Naprave (nikoli ne odobri). Kadar je program v ospredju, se odpre okno z istim vprasanjem; na
 * televizorju, kjer obvestil pogosto ni videti, se cakajoce prosnje pokazejo ob odprtju programa in na zaslonu Naprave.
 *
 * Odobri lahko samo dejanje uporabnika na tej napravi: prejemnik gumbov ni izvozen, namere so nespremenljive
 * (FLAG_IMMUTABLE), in odobri se samo prosnja, ki res caka.
 */
object ProsnjeDostopa {
    private const val TAG = "SafeerDostop"
    private const val PREFS = "safeer_dostop"         // iste nastavitve kot Dostop
    private const val KLJUC_ZAVRNJENO = "zavrnjeno"   // {"<jedro>|<crka>": epochMillis}
    private const val KLJUC_CAKAJOCE = "prosnje"      // [{"j","i","z","t"}]
    private const val KANAL = "safeer_dostop_prosnje"
    private const val OSNOVA_ID = 4790
    const val DEJANJE_DOVOLI = "si.safeer.tv.cast.DOSTOP_DOVOLI"
    const val DEJANJE_ZAVRNI = "si.safeer.tv.cast.DOSTOP_ZAVRNI"
    const val KLJUC_JEDRO = "jedro"
    const val KLJUC_ZMOZNOST = "zmoznost"

    private var shramba: ProsnjeDostopaPravila.Shramba? = null
    private val glavna by lazy { Handler(Looper.getMainLooper()) }

    /** Zaslon tega programa, ki je zdaj v ospredju (za okno ob novi prosnji). */
    @Volatile private var vOspredju: WeakReference<Activity>? = null
    /** Odprto okno s prosnjo (najvec eno naenkrat). */
    private var okno: WeakReference<AlertDialog>? = null
    /** Prosnje (kljuc -> cas), ki jih je uporabnik v oknu ze videl: ob vsakem zaslonu jih ne ponavljamo. */
    private val pokazano = HashMap<String, Long>()

    @Synchronized
    private fun nalozena(ctx: Context): ProsnjeDostopaPravila.Shramba {
        shramba?.let { return it }
        val s = ProsnjeDostopaPravila.Shramba()
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            val o = JSONObject(prefs.getString(KLJUC_ZAVRNJENO, "{}") ?: "{}")
            for (k in o.keys()) {
                val t = o.optLong(k, 0L)
                if (t > 0 && k.length <= DostopPravila.DOLZINA_JEDRA + 2) s.zavrnjeno[k] = t
            }
        } catch (_: Throwable) { }
        try {
            val a = JSONArray(prefs.getString(KLJUC_CAKAJOCE, "[]") ?: "[]")
            for (i in 0 until minOf(a.length(), ProsnjeDostopaPravila.NAJVEC_CAKAJOCIH)) {
                val o = a.optJSONObject(i) ?: continue
                val j = o.optString("j"); val z = ProsnjeDostopaPravila.zmoznostIzCrke(o.optString("z")) ?: continue
                if (!DostopPravila.jeJedro(j)) continue
                s.cakajoce.add(Prosnja(j, ProsnjeDostopaPravila.cistoIme(o.optString("i")).ifBlank { j }, z, o.optLong("t", 0L)))
            }
        } catch (_: Throwable) { }
        shramba = s
        return s
    }

    private fun shrani(ctx: Context, s: ProsnjeDostopaPravila.Shramba) {
        val zavrnjeno = JSONObject().apply { for ((k, v) in s.zavrnjeno) put(k, v) }
        val cakajoce = JSONArray().apply {
            for (p in s.cakajoce) put(JSONObject().put("j", p.jedro).put("i", p.ime).put("z", p.zmoznost.crka.toString()).put("t", p.cas))
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KLJUC_ZAVRNJENO, zavrnjeno.toString()).putString(KLJUC_CAKAJOCE, cakajoce.toString()).apply()
    }

    /** Ime naprave z oznako [id] iz kroga TE naprave (prazno, ce je krog ne pozna) - nikoli iz sporocila. */
    fun imeNaprave(ctx: Context, id: String): String =
        try { imeIzKroga(ctx.applicationContext, Dostop.jedro(ctx.applicationContext, id)).orEmpty() } catch (_: Throwable) { "" }

    /** Ime naprave s tem jedrom iz kroga TE naprave (null: ni clan). */
    private fun imeIzKroga(ctx: Context, jedro: String): String? = try {
        val krog = KrogNaprave.krog(ctx)
        ProsnjeDostopaPravila.imeIzKroga(krog.clani().map { ProsnjeDostopaPravila.Clan(it.id, it.kljuc, it.ime, it.imenovano) },
            jedro, KrogZaupanja::idIzKljuca)
    } catch (_: Throwable) { null }

    /**
     * Dejanje je bilo zavrnjeno, ker posiljatelju manjka dostop. [zascita] je jedro iz preverjenega kljuca (prazno, ce
     * sporocilo ni prislo zasciteno - takrat vprasanja ni: oznako posiljatelja vpise sredisce in je ne moremo preveriti).
     * Vrne, kaj povemo prosilcu; ce je prosnja nova, pokaze obvestilo (in okno, ce je program v ospredju).
     */
    fun obZavrnitvi(ctx: Context, zascita: String, zahteva: Zahteva): Odlocitev {
        val c = ctx.applicationContext
        val odlocitev = try {
            val ime = if (DostopPravila.jeJedro(zascita) && !Dostop.jeTaNaprava(c, zascita)) imeIzKroga(c, zascita) else null
            val obstojece = if (ime != null) Dostop.zmoznostiJedra(c, zascita) else emptySet()
            synchronized(this) {
                val s = nalozena(c)
                val o = ProsnjeDostopaPravila.obZavrnitvi(s, zascita, ime, zahteva, obstojece, System.currentTimeMillis())
                if (o.novaProsnja != null) shrani(c, s)
                o
            }
        } catch (t: Throwable) {
            Log.w(TAG, "prosnja: ${t.javaClass.simpleName}")
            Odlocitev(ProsnjeDostopaPravila.Stanje.NONE, ProsnjeDostopaPravila.zmoznostZahteve(zahteva))
        }
        odlocitev.novaProsnja?.let { p ->
            Log.i(TAG, "Prosnja za dostop: ${E2e.zakrij(p.jedro)} (${p.zmoznost.crka})")
            obvesti(c, p)
            glavna.post { vOspredju?.get()?.let { pokaziCakajoce(it, vse = false) } }
        }
        return odlocitev
    }

    fun cakajoce(ctx: Context): List<Prosnja> = synchronized(this) { nalozena(ctx.applicationContext).cakajoce.toList() }

    private fun idObvestila(p: Prosnja) = OSNOVA_ID + (p.kljuc.hashCode() and 0x3f)

    fun imeZmoznosti(ctx: Context, z: Zmoznost): String = ctx.getString(when (z) {
        Zmoznost.DATOTEKE -> R.string.os_dostop_kratko_datoteke
        Zmoznost.PROGRAMI -> R.string.os_dostop_kratko_programi
        Zmoznost.PREDVAJALNIK -> R.string.os_dostop_kratko_predvajalnik
        Zmoznost.ZASLON -> R.string.os_dostop_kratko_zaslon
    })

    private fun besedilo(ctx: Context, p: Prosnja) = ctx.getString(R.string.os_prosnja_vprasanje, p.ime, imeZmoznosti(ctx, p.zmoznost))

    /** Dotik obvestila: zaslon Naprave (ce ga ta program ima), sicer program. Nikoli ne odobri. */
    private fun namera(ctx: Context): Intent? {
        val naprave = Intent().setClassName(ctx, "si.safeer.tv.os.NapraveActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val ima = try { naprave.resolveActivity(ctx.packageManager) != null } catch (_: Throwable) { false }
        return if (ima) naprave else ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: ctx.packageManager.getLeanbackLaunchIntentForPackage(ctx.packageName)
    }

    private fun obvesti(c: Context, p: Prosnja) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            // Android 13+: brez dovoljenja za obvestila Android obvestilo tiho zavrze - prosnja ostane v Napravah in v oknu.
            if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(KANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(KANAL, c.getString(R.string.os_prosnja_kanal), NotificationManager.IMPORTANCE_HIGH))
            }
            val zastavice = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val nid = idObvestila(p)
            val dotik = namera(c)?.let { PendingIntent.getActivity(c, nid, it, zastavice) }
            fun gumb(dejanje: String, zamik: Int) = PendingIntent.getBroadcast(c, nid * 4 + zamik,
                Intent(c, Prejemnik::class.java).setAction(dejanje).putExtra(KLJUC_JEDRO, p.jedro)
                    .putExtra(KLJUC_ZMOZNOST, p.zmoznost.crka.toString()), zastavice)
            val besedilo = besedilo(c, p)
            @Suppress("DEPRECATION")
            val g = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, KANAL) else Notification.Builder(c)
            g.setSmallIcon(R.drawable.os_znak)
                .setContentTitle(c.getString(R.string.os_prosnja_naslov))
                .setContentText(besedilo)
                .setStyle(Notification.BigTextStyle().bigText(besedilo))
                .setAutoCancel(true)
                .setGroup("safeer_dostop_" + p.kljuc)
                .addAction(Notification.Action.Builder(null, c.getString(R.string.os_prosnja_dovoli), gumb(DEJANJE_DOVOLI, 1)).build())
                .addAction(Notification.Action.Builder(null, c.getString(R.string.os_prosnja_zavrni), gumb(DEJANJE_ZAVRNI, 2)).build())
            if (dotik != null) g.setContentIntent(dotik)
            nm.notify(nid, g.build())
        } catch (t: Throwable) {
            Log.w(TAG, "prosnja (obvestilo): ${t.javaClass.simpleName}")
        }
    }

    private fun umakni(c: Context, p: Prosnja) {
        try { c.getSystemService(NotificationManager::class.java)?.cancel(idObvestila(p)) } catch (_: Throwable) { }
    }

    /** Odlocitev uporabnika te naprave (gumb v obvestilu ali v oknu). */
    fun odloci(ctx: Context, jedro: String, zmoznost: Zmoznost, dovoli: Boolean) {
        val c = ctx.applicationContext
        if (!DostopPravila.jeJedro(jedro)) return
        val p = synchronized(this) { nalozena(c).cakajoce.firstOrNull { it.jedro == jedro && it.zmoznost == zmoznost } } ?: run {
            // Prosnja je ze odlocena (npr. v oknu in nato se v obvestilu): nic vec ne spremenimo.
            try { c.getSystemService(NotificationManager::class.java)?.cancel(OSNOVA_ID + (ProsnjeDostopaPravila.kljuc(jedro, zmoznost).hashCode() and 0x3f)) } catch (_: Throwable) { }
            return
        }
        if (dovoli) {
            val nov = synchronized(this) {
                val s = nalozena(c)
                val n = ProsnjeDostopaPravila.dovoli(s, jedro, zmoznost, Dostop.zmoznostiJedra(c, jedro))
                shrani(c, s); n
            }
            // Dostop.nastavi ohrani druge zmoznosti (nov = obstojece + ta ena) in sprozi Dostop.naSpremembo.
            if (nov != null) Dostop.nastavi(c, jedro, nov)
        } else {
            synchronized(this) {
                val s = nalozena(c)
                ProsnjeDostopaPravila.zavrni(s, jedro, zmoznost, System.currentTimeMillis())
                shrani(c, s)
            }
        }
        umakni(c, p)
        Log.i(TAG, "Prosnja ${E2e.zakrij(jedro)} (${zmoznost.crka}): ${if (dovoli) "dovoljeno" else "zavrnjeno"}")
        try {
            Toast.makeText(c, if (dovoli) c.getString(R.string.os_prosnja_dovoljeno, p.ime, imeZmoznosti(c, zmoznost))
                else c.getString(R.string.os_prosnja_zavrnjeno, p.ime), Toast.LENGTH_LONG).show()
        } catch (_: Throwable) { }
    }

    /** Klice CastReceiverService iz povratnih klicev zaslonov (ActivityLifecycleCallbacks). */
    fun naZaslon(a: Activity) {
        // Prosojno okno z vprasanjem o internetu se zapre samo: nanj okna ne odpiramo.
        if (a is si.safeer.tv.link.InternetDovoljenjeActivity) return
        vOspredju = WeakReference(a)
        pokaziCakajoce(a, vse = a.javaClass.simpleName == "NapraveActivity")
    }

    fun zaslonUmaknjen(a: Activity) {
        if (vOspredju?.get() === a) vOspredju = null
    }

    /**
     * Pokaze okno s prvo cakajoco prosnjo. [vse]: tudi tisto, ki jo je uporabnik ze videl (zaslon Naprave); sicer samo
     * novo. Gumb »Pozneje« prosnjo pusti cakati.
     */
    fun pokaziCakajoce(a: Activity, vse: Boolean) {
        if (a.isFinishing || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed)) return
        if (okno?.get()?.isShowing == true) return
        val p = try {
            cakajoce(a).firstOrNull { p -> vse || synchronized(pokazano) { pokazano[p.kljuc] != p.cas } }
        } catch (_: Throwable) { null } ?: return
        synchronized(pokazano) { pokazano[p.kljuc] = p.cas }
        try {
            umakni(a.applicationContext, p)
            val d = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.getString(R.string.os_prosnja_naslov))
                .setMessage(besedilo(a, p))
                .setPositiveButton(a.getString(R.string.os_prosnja_dovoli)) { _, _ -> odloci(a, p.jedro, p.zmoznost, true) }
                .setNegativeButton(a.getString(R.string.os_prosnja_zavrni)) { _, _ -> odloci(a, p.jedro, p.zmoznost, false) }
                .setNeutralButton(a.getString(R.string.os_prosnja_pozneje), null)
                .setOnDismissListener {
                    okno = null
                    // Naslednja cakajoca prosnja (ce je), samo na zaslonu Naprave ali ce je nova.
                    glavna.post { if (vOspredju?.get() === a) pokaziCakajoce(a, vse = false) }
                }
                .create()
            okno = WeakReference(d)
            d.show()
        } catch (t: Throwable) {
            Log.w(TAG, "prosnja (okno): ${t.javaClass.simpleName}")
        }
    }

    /**
     * Ta naprava kot PROSILEC: druga naprava ([imeCilja] iz nasega seznama naprav) je ukaz zavrnila. Ce je ob tem
     * poslala prosnjo uporabniku (`access_request`), vrne besedilo za zaslon; sicer null (velja dosedanje sporocilo).
     */
    fun sporociloProsilcu(ctx: Context, odgovor: JSONObject?, imeCilja: String): String? =
        when (ProsnjeDostopaPravila.stanjeOdgovora(odgovor)) {
            ProsnjeDostopaPravila.Stanje.PENDING -> ctx.getString(R.string.os_prosnja_poslana, imeCilja)
            ProsnjeDostopaPravila.Stanje.DECLINED -> ctx.getString(R.string.os_prosnja_zavrnjena, imeCilja)
            else -> null
        }

    /** Gumba Dovoli / Zavrni v obvestilu (prejemnik ni izvozen: sprozi ga lahko samo obvestilo tega programa). */
    class Prejemnik : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val jedro = intent.getStringExtra(KLJUC_JEDRO).orEmpty()
            val z = ProsnjeDostopaPravila.zmoznostIzCrke(intent.getStringExtra(KLJUC_ZMOZNOST).orEmpty()) ?: return
            when (intent.action) {
                DEJANJE_DOVOLI -> odloci(context, jedro, z, true)
                DEJANJE_ZAVRNI -> odloci(context, jedro, z, false)
            }
        }
    }
}
