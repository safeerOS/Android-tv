package si.safeer.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Vprasanja PackageManagerju za [AplikacijaPovezave]: kdo odpre naslov in kdo je brskalnik. Android 11+ vidi
 * aplikacije za VIEW https zaradi vnosa <queries> v AndroidManifest.xml.
 */
object AplikacijaPovezaveSistem {
    /** Naslov, ki ga ne odpre nobena aplikacija z App Linkom, samo brskalniki (RFC 2606: .invalid ne obstaja). */
    private const val SPLOSEN_NASLOV = "https://safeer-brskalnik-preizkus.invalid/"

    private fun paketi(ctx: Context, naslov: String): Set<String> = try {
        val namera = Intent(Intent.ACTION_VIEW, Uri.parse(naslov)).addCategory(Intent.CATEGORY_BROWSABLE)
        ctx.packageManager.queryIntentActivities(namera, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }.toSet()
    } catch (_: Exception) { emptySet() }

    fun razreseni(ctx: Context, naslov: String): Set<String> = paketi(ctx, naslov)

    fun brskalniki(ctx: Context): Set<String> = paketi(ctx, SPLOSEN_NASLOV)

    /** Paket aplikacije, ki ga odpre [naslov] (glej [AplikacijaPovezave.ponudiAplikacijo]), ali null. */
    fun aplikacijaZa(ctx: Context, izvor: String?, naslov: String?): String? {
        if (izvor == null || !AplikacijaPovezave.jeSplet(naslov)) return null
        return AplikacijaPovezave.ponudiAplikacijo(izvor, razreseni(ctx, naslov!!), brskalniki(ctx), naslov, ctx.packageName)
    }

    /** Ime aplikacije za gumb; ce ga ni, paket. */
    fun ime(ctx: Context, paket: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(paket, 0)).toString()
    } catch (_: Exception) { paket }

    /** Odpre [naslov] v aplikaciji [paket] (samo ta paket, BROWSABLE). */
    fun odpri(ctx: Context, paket: String, naslov: String): Boolean = try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(naslov))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(paket)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        android.util.Log.w("SafeerBrskalnik", "aplikacija ni prevzela povezave: ${e.javaClass.simpleName}")
        false
    }
}
