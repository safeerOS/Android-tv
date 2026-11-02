package si.safeer.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Vprasanja PackageManagerju za [AplikacijaPovezave]. Vedno s setPackage(izvor): cilj izbere samo paket, ki je zavihek
 * odprl, nikoli vsebina strani. Android 11+ vidi aplikacije za VIEW https zaradi vnosa <queries> v AndroidManifest.xml.
 */
object AplikacijaPovezaveSistem {
    /** Naslov, ki ga ne odpre nobena aplikacija z App Linkom, samo brskalniki (RFC 2606: .invalid ne obstaja). */
    private const val SPLOSEN_NASLOV = "https://safeer-brskalnik-preizkus.invalid/"

    private fun namera(naslov: String, paket: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(naslov)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(paket)

    /** Dejavnost paketa [paket] odpre [naslov] (filtri App Links aplikacije). */
    fun odpre(ctx: Context, paket: String, naslov: String): Boolean = try {
        ctx.packageManager.queryIntentActivities(namera(naslov, paket), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    } catch (_: Exception) { false }

    /** Paket odpre vsak spletni naslov - je brskalnik. */
    fun jeBrskalnik(ctx: Context, paket: String): Boolean = odpre(ctx, paket, SPLOSEN_NASLOV)

    /** Samodejna predaja navigacije glavnega okvirja ([AplikacijaPovezave.predajNavigacijo]). */
    fun predajNavigacijo(ctx: Context, izvor: String?, glavniOkvir: Boolean, naslov: String): String? {
        if (izvor == null || !glavniOkvir || !AplikacijaPovezave.jeHttps(naslov) || AplikacijaPovezave.jeKoren(naslov)) return null
        return AplikacijaPovezave.predajNavigacijo(izvor, odpre(ctx, izvor, naslov), jeBrskalnik(ctx, izvor), glavniOkvir,
            naslov, ctx.packageName)
    }

    /** Paket aplikacije za gumb (glej [AplikacijaPovezave.ponudiAplikacijo]), ali null. */
    fun aplikacijaZa(ctx: Context, izvor: String?, naslov: String?): String? {
        if (izvor == null || !AplikacijaPovezave.jeHttps(naslov)) return null
        return AplikacijaPovezave.ponudiAplikacijo(izvor, odpre(ctx, izvor, naslov!!), jeBrskalnik(ctx, izvor), naslov, ctx.packageName)
    }

    /** Ime aplikacije za gumb; ce ga ni, paket. */
    fun ime(ctx: Context, paket: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(paket, 0)).toString()
    } catch (_: Exception) { paket }

    /** Odpre [naslov] (tak, kot je, s poizvedbo) v aplikaciji [paket] - samo ta paket, BROWSABLE. */
    fun odpri(ctx: Context, paket: String, naslov: String): Boolean = try {
        ctx.startActivity(namera(naslov, paket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        android.util.Log.w("SafeerBrskalnik", "aplikacija ni prevzela povezave: ${e.javaClass.simpleName}")
        false
    }
}
