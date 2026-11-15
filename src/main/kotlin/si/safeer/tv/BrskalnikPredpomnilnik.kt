package si.safeer.tv

import android.content.Context
import android.os.Environment
import android.system.Os
import si.safeer.tv.os.PredpomnilnikPravila
import si.safeer.tv.os.UsbPredpomnilnik
import java.io.File

/**
 * Predpomnilnik spletnih strani (Chromium WebView) na USB disku, ce ga uporabnik ima (lastnik 15. 11. 2026: »ce uporabnik kot jaz
 * uporabi USB trdi disk«); brez diska nic ne spremenimo. Notranji pomnilnik TV je poln (77 %, 1,1 GB prostega), disk pa
 * je ze izmerjen (pisljiv in dovolj hiter, [UsbPredpomnilnik]) in izbran v nastavitvah (Samodejno / Ta TV - ista nastavitev kot
 * pri Medijskem centru; »Ta TV« vrne predpomnilnik v notranji pomnilnik).
 *
 * Kako: WebView hrani predpomnilnik v `cacheDir/WebView`; ta mapa postane simbolna povezava na mapo na USB (Android jo
 * dovoli v mapi aplikacije na zunanji shrambi brez dovoljenj). To MORA biti pred prvim WebView v procesu (Application ali prva
 * dejavnost). NEPREVERJENO na napravi: ali WebView sledi povezavi in ali predpomnilnik na FAT32 deluje - dokaz so datoteke
 * v `<USB>/Android/data/<paket>/cache/brskalnik-http` po brskanju. Varovalo: zagon z diskom, ki ga brskalnik ni potrdil
 * (prva nalozena stran), se naslednjic ne ponovi - povezava se odstrani in predpomnilnik ostane notranji.
 */
object BrskalnikPredpomnilnik {
    private const val PREFS = "safeer_brskalnik_predpomnilnik"
    private const val STANJE = "stanje"            // "<usbPot>|poskus" | "<usbPot>|uspel" | "<usbPot>|neuspel"
    @Volatile private var potrjeno = false

    /** Pred prvim WebView. Ne meri diska (odlocitev je shranjena), ne sme vreci izjeme. */
    fun priklopi(c: Context) {
        try {
            val app = c.applicationContext
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val usbPot = UsbPredpomnilnik.potZaBrskalnik(app)
            val d = usbPot?.let { File(it) }
            val priklopljen = d != null && Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED
            val odstranljiv = d != null && try { Environment.isExternalStorageRemovable(d) } catch (_: Throwable) { false }
            val prostih = if (priklopljen) try { d!!.usableSpace } catch (_: Throwable) { 0L } else 0L
            val (stanjePot, stanje) = (p.getString(STANJE, "") ?: "").let { it.substringBeforeLast('|') to it.substringAfterLast('|', "") }
            // Poskus, ki ga brskalnik ni potrdil, je neuspel; neuspeh velja samo za isti disk.
            val prejsnji: Boolean? = if (stanjePot != usbPot) null else when (stanje) { "uspel" -> true; "poskus", "neuspel" -> false; else -> null }
            val cilj = PredpomnilnikPravila.brskalnikCilj(UsbPredpomnilnik.mesto(app).nacin, usbPot, priklopljen, odstranljiv, prostih, prejsnji)
            if (stanje == "poskus" && stanjePot == usbPot) p.edit().putString(STANJE, "$usbPot|neuspel").apply()
            val povezava = File(app.cacheDir, "WebView")
            val trenutna = try { if (java.nio.file.Files.isSymbolicLink(povezava.toPath())) java.nio.file.Files.readSymbolicLink(povezava.toPath()).toString() else null } catch (_: Throwable) { null }
            when {
                cilj == null -> if (trenutna != null) {      // disk ni (vec) v uporabi: nazaj na notranji predpomnilnik
                    try { povezava.delete() } catch (_: Throwable) { }
                    android.util.Log.i("SafeerBrskalnikCache", "predpomnilnik brskalnika: notranji (povezava odstranjena)")
                }
                trenutna == cilj -> if (stanje != "uspel") p.edit().putString(STANJE, "$usbPot|poskus").apply()
                else -> {
                    val mapa = File(cilj)
                    if (!(mapa.isDirectory || mapa.mkdirs()) || !mapa.canWrite()) return
                    if (trenutna != null) povezava.delete() else if (povezava.exists()) povezava.deleteRecursively()   // samo predpomnilnik
                    Os.symlink(cilj, povezava.path)
                    p.edit().putString(STANJE, "$usbPot|poskus").apply()
                    android.util.Log.i("SafeerBrskalnikCache", "predpomnilnik brskalnika: USB ($cilj)")
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("SafeerBrskalnikCache", "priklop predpomnilnika ni uspel: " + t.javaClass.simpleName)
        }
    }

    /** Brskalnik je nalozil prvo stran: poskus z diskom je uspel (varovalo se ne sprozi). Poceni, klic ob vsaki strani. */
    fun potrdi(c: Context) {
        if (potrjeno) return
        potrjeno = true
        try {
            val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val s = p.getString(STANJE, "") ?: ""
            if (s.endsWith("|poskus")) p.edit().putString(STANJE, s.removeSuffix("poskus") + "uspel").apply()
        } catch (_: Throwable) { }
    }
}
