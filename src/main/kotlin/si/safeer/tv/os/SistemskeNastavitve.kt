package si.safeer.tv.os

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import si.safeer.tv.R

/**
 * Sistemske nastavitve Androida v Nastavitvah Safeer OS (lastnik, 19. 10. 2026: »Nastavitve morajo odrazati celotne
 * nastavitve operacijskega sistema«). Vsak vnos odpre sistemski zaslon z javno namero (Settings.ACTION_* in ustrezne
 * namere televizorja). Pokazemo samo vnose, ki jih na tej napravi res odpre prava dejavnost: neobstojece in
 * nadomestne (proizvajalec jih prestreze in ne naredijo nicesar, [VrsticaPravila.jeNadomestek]) bi bile mrtvi gumbi.
 */
object SistemskeNastavitve {

    /** Skupine, v katerih so nastavitve na zaslonu (vrstni red je vrstni red na zaslonu). */
    enum class Skupina(val naslov: Int) {
        SAFEER(R.string.os_app_name),
        OMREZJE(R.string.nast_sk_omrezje),
        NAPRAVE(R.string.nast_sk_naprave),
        ZASLON(R.string.nast_sk_zaslon),
        TELEVIZOR(R.string.nast_sk_tv),
        APLIKACIJE(R.string.nast_sk_aplikacije),
        SHRAMBA(R.string.nast_sk_shramba),
        ZASEBNOST(R.string.nast_sk_zasebnost),
        SISTEM(R.string.nast_sk_sistem),
    }

    /**
     * En sistemski vnos: [akcije] po vrsti - prva, ki jo naprava odpre, velja (npr. Dostopnost na telefonu in TV).
     * [namera] je za vnose, ki niso navadna akcija (stran te aplikacije, izbira vhoda televizorja).
     */
    class Vnos(
        val kljuc: String,
        val skupina: Skupina,
        val ime: Int,
        val opis: Int,
        val ikona: Int,
        val akcije: List<String> = emptyList(),
        val pogoj: (Context) -> Boolean = { true },
        val namera: ((Context) -> Intent)? = null,
    )

    private fun razvijalci(c: Context): Boolean = try {
        android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Throwable) { false }

    /** Televizor s sprejemnikom (vhodi HDMI, antena): izbira vira je nasa (ViriActivity), sistem je ne ponuja povsod. */
    private fun imaVhode(c: Context): Boolean = c.packageManager.hasSystemFeature("android.software.live_tv")

    val VNOSI: List<Vnos> = listOf(
        // Omrezje in internet
        Vnos("wifi", Skupina.OMREZJE, R.string.nast_wifi, R.string.nast_wifi_opis, R.drawable.os_ikona_wifi,
            listOf("android.settings.WIFI_SETTINGS")),
        Vnos("omrezje", Skupina.OMREZJE, R.string.nast_omrezje, R.string.nast_omrezje_opis, R.drawable.os_ikona_splet,
            listOf("android.settings.WIRELESS_SETTINGS")),
        Vnos("mobilno", Skupina.OMREZJE, R.string.nast_mobilno, R.string.nast_mobilno_opis, R.drawable.os_ikona_telefon,
            listOf("android.settings.NETWORK_OPERATOR_SETTINGS", "android.settings.DATA_ROAMING_SETTINGS")),
        Vnos("sim", Skupina.OMREZJE, R.string.nast_sim, R.string.nast_sim_opis, R.drawable.os_ikona_telefon,
            listOf("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS", "android.settings.MOBILE_NETWORK_LIST")),
        Vnos("podatki", Skupina.OMREZJE, R.string.nast_podatki, R.string.nast_podatki_opis, R.drawable.os_ikona_hitrost,
            listOf("android.settings.DATA_USAGE_SETTINGS")),
        Vnos("tocka", Skupina.OMREZJE, R.string.nast_tocka, R.string.nast_tocka_opis, R.drawable.os_ikona_wifi,
            listOf("android.settings.TETHER_SETTINGS")),
        Vnos("vpn", Skupina.OMREZJE, R.string.nast_vpn, R.string.nast_vpn_opis, R.drawable.os_ikona_zaklep,
            listOf("android.settings.VPN_SETTINGS")),
        // Povezane naprave
        Vnos("bluetooth", Skupina.NAPRAVE, R.string.nast_bluetooth, R.string.nast_bluetooth_opis, R.drawable.os_ikona_bluetooth,
            listOf("android.settings.BLUETOOTH_SETTINGS")),
        Vnos("daljinci", Skupina.NAPRAVE, R.string.nast_daljinci, R.string.nast_daljinci_opis, R.drawable.os_ikona_bluetooth,
            listOf("com.google.android.intent.action.CONNECT_INPUT")),
        Vnos("predvajanje", Skupina.NAPRAVE, R.string.nast_predvajanje, R.string.nast_predvajanje_opis, R.drawable.os_ikona_tv,
            listOf("android.settings.CAST_SETTINGS")),
        Vnos("nfc", Skupina.NAPRAVE, R.string.nast_nfc, R.string.nast_nfc_opis, R.drawable.os_ikona_link,
            listOf("android.settings.NFC_SETTINGS")),
        Vnos("tipkovnica_fiz", Skupina.NAPRAVE, R.string.nast_tipkovnica_fiz, R.string.nast_tipkovnica_fiz_opis, R.drawable.os_ikona_tipkovnica,
            listOf("android.settings.HARD_KEYBOARD_SETTINGS")),
        Vnos("tiskanje", Skupina.NAPRAVE, R.string.nast_tiskanje, R.string.nast_tiskanje_opis, R.drawable.os_ikona_datoteka,
            listOf("android.settings.ACTION_PRINT_SETTINGS")),
        // Zaslon in zvok
        Vnos("zaslon", Skupina.ZASLON, R.string.nast_zaslon, R.string.nast_zaslon_opis, R.drawable.os_ikona_zaslon,
            listOf("android.settings.DISPLAY_SETTINGS")),
        Vnos("temna", Skupina.ZASLON, R.string.nast_temna, R.string.nast_temna_opis, R.drawable.os_ikona_luna,
            listOf("android.settings.DARK_THEME_SETTINGS")),
        Vnos("nocna", Skupina.ZASLON, R.string.nast_nocna, R.string.nast_nocna_opis, R.drawable.os_ikona_luna,
            listOf("android.settings.NIGHT_DISPLAY_SETTINGS")),
        Vnos("pisava", Skupina.ZASLON, R.string.nast_pisava, R.string.nast_pisava_opis, R.drawable.os_ikona_zapiski,
            listOf("android.settings.TEXT_READING_SETTINGS")),
        Vnos("ohranjevalnik", Skupina.ZASLON, R.string.nast_ohranjevalnik, R.string.nast_ohranjevalnik_opis, R.drawable.os_ikona_slika,
            listOf("android.settings.DREAM_SETTINGS")),
        Vnos("zvok", Skupina.ZASLON, R.string.nast_zvok, R.string.nast_zvok_opis, R.drawable.os_ikona_zvocnik,
            listOf("android.settings.SOUND_SETTINGS")),
        // Televizor
        Vnos("vhodi", Skupina.TELEVIZOR, R.string.nast_vhodi, R.string.nast_vhodi_opis, R.drawable.os_ikona_tv,
            pogoj = { c -> Naprava.jeTelevizor(c) && imaVhode(c) }, namera = { c -> Intent(c, ViriActivity::class.java) }),
        // Philips (TP Vision): hitri meniji televizorja; drugje jih ni in se ne pokazejo. Slog in format slike ter
        // iskanje kanalov brez dodatkov namere ne delajo (»Nepodprta vrednost«, okno se takoj zapre - TV 19. 10. 2026).
        Vnos("ambilight", Skupina.TELEVIZOR, R.string.nast_ambilight, R.string.nast_ambilight_opis, R.drawable.os_ikona_slika,
            listOf("org.droidtv.action.AMBILIGHT_OSD")),
        Vnos("izhod_zvoka", Skupina.TELEVIZOR, R.string.nast_izhod_zvoka, R.string.nast_izhod_zvoka_opis, R.drawable.os_ikona_zvocnik,
            listOf("org.droidtv.action.QUICKSETTINGSPEAKER")),
        // Aplikacije in obvestila
        Vnos("aplikacije", Skupina.APLIKACIJE, R.string.nast_aplikacije, R.string.nast_aplikacije_opis, R.drawable.os_ikona_aplikacije,
            listOf("android.settings.MANAGE_APPLICATIONS_SETTINGS", "android.settings.APPLICATION_SETTINGS")),
        Vnos("privzete", Skupina.APLIKACIJE, R.string.nast_privzete, R.string.nast_privzete_opis, R.drawable.os_ikona_aplikacije,
            listOf("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")),
        Vnos("obvestila_vse", Skupina.APLIKACIJE, R.string.nast_obvestila_vse, R.string.nast_obvestila_vse_opis, R.drawable.os_ikona_zvonec,
            listOf("android.settings.NOTIFICATION_SETTINGS", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS")),
        Vnos("ne_moti", Skupina.APLIKACIJE, R.string.nast_ne_moti, R.string.nast_ne_moti_opis, R.drawable.os_ikona_luna,
            listOf("android.settings.ZEN_MODE_SETTINGS", "android.settings.ZEN_MODE_PRIORITY_SETTINGS")),
        Vnos("neznane", Skupina.APLIKACIJE, R.string.nast_neznane, R.string.nast_neznane_opis, R.drawable.os_ikona_zaklep,
            listOf("android.settings.MANAGE_UNKNOWN_APP_SOURCES")),
        Vnos("ta_aplikacija", Skupina.APLIKACIJE, R.string.nast_ta_aplikacija, R.string.nast_ta_aplikacija_opis, R.drawable.os_ikona_scit,
            namera = { c -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)) }),
        // Shramba in baterija
        Vnos("shramba", Skupina.SHRAMBA, R.string.nast_shramba, R.string.nast_shramba_opis, R.drawable.os_ikona_datoteke,
            listOf("android.settings.INTERNAL_STORAGE_SETTINGS")),
        Vnos("baterija", Skupina.SHRAMBA, R.string.nast_baterija, R.string.nast_baterija_opis, R.drawable.os_ikona_baterija,
            listOf("android.intent.action.POWER_USAGE_SUMMARY")),
        Vnos("varcevanje", Skupina.SHRAMBA, R.string.nast_varcevanje, R.string.nast_varcevanje_opis, R.drawable.os_ikona_baterija,
            listOf("android.settings.BATTERY_SAVER_SETTINGS")),
        // Zasebnost in varnost
        Vnos("lokacija", Skupina.ZASEBNOST, R.string.nast_lokacija, R.string.nast_lokacija_opis, R.drawable.os_ikona_lokacija,
            listOf("android.settings.LOCATION_SOURCE_SETTINGS")),
        Vnos("varnost", Skupina.ZASEBNOST, R.string.nast_varnost, R.string.nast_varnost_opis, R.drawable.os_ikona_zaklep,
            listOf("android.settings.SECURITY_SETTINGS")),
        Vnos("zasebnost", Skupina.ZASEBNOST, R.string.nast_zasebnost, R.string.nast_zasebnost_opis, R.drawable.os_ikona_scit,
            listOf("android.settings.PRIVACY_SETTINGS")),
        Vnos("racuni", Skupina.ZASEBNOST, R.string.nast_racuni, R.string.nast_racuni_opis, R.drawable.os_ikona_racun,
            listOf("android.settings.SYNC_SETTINGS")),
        Vnos("uporabniki", Skupina.ZASEBNOST, R.string.nast_uporabniki, R.string.nast_uporabniki_opis, R.drawable.os_ikona_racun,
            listOf("android.settings.USER_SETTINGS")),
        // Sistem
        Vnos("jezik", Skupina.SISTEM, R.string.nast_jezik, R.string.nast_jezik_opis, R.drawable.os_ikona_splet,
            listOf("android.settings.LOCALE_SETTINGS")),
        Vnos("tipkovnica", Skupina.SISTEM, R.string.nast_tipkovnica, R.string.nast_tipkovnica_opis, R.drawable.os_ikona_tipkovnica,
            listOf("android.settings.INPUT_METHOD_SETTINGS")),
        Vnos("glas", Skupina.SISTEM, R.string.nast_glas, R.string.nast_glas_opis, R.drawable.os_ikona_mikrofon,
            listOf("android.settings.VOICE_INPUT_SETTINGS")),
        Vnos("datum", Skupina.SISTEM, R.string.nast_datum, R.string.nast_datum_opis, R.drawable.os_ikona_casovnik,
            listOf("android.settings.DATE_SETTINGS")),
        Vnos("dostopnost", Skupina.SISTEM, R.string.nast_dostopnost, R.string.nast_dostopnost_opis, R.drawable.os_ikona_dostopnost,
            listOf("android.settings.ACCESSIBILITY_SETTINGS")),
        Vnos("podnapisi", Skupina.SISTEM, R.string.nast_podnapisi, R.string.nast_podnapisi_opis, R.drawable.os_ikona_podnapisi,
            listOf("android.settings.CAPTIONING_SETTINGS")),
        Vnos("razvijalci", Skupina.SISTEM, R.string.nast_razvijalci, R.string.nast_razvijalci_opis, R.drawable.os_ikona_koda,
            listOf("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"), pogoj = ::razvijalci),
        Vnos("o_napravi", Skupina.SISTEM, R.string.nast_o_napravi, R.string.nast_o_napravi_opis, R.drawable.os_ikona_naprava,
            listOf("android.settings.DEVICE_INFO_SETTINGS")),
        Vnos("posodobitev", Skupina.SISTEM, R.string.nast_posodobitev, R.string.nast_posodobitev_opis, R.drawable.os_ikona_posodobi,
            listOf("android.settings.SYSTEM_UPDATE_SETTINGS")),
        Vnos("vse", Skupina.SISTEM, R.string.nast_vse, R.string.nast_vse_opis, R.drawable.os_ikona_nastavitve,
            listOf("android.settings.SETTINGS")),
    )

    /**
     * Dejavnosti, ki se brez dodatkov namere (racun, izbrana nastavitev) takoj zaprejo - uporabnik ne vidi nicesar.
     * Izmerjeno na televizorju Philips (Android TV 11, 19. 10. 2026): Racuni, Uporabniki.
     */
    private val ZAPRE_SE = setOf(
        "com.android.tv.settings.accounts.AccountSyncActivity",
        "com.google.android.tv.settings.AccountActivity",
    )

    /** Dejavnost je uporabna: izvozena, nam dovoljena in ni nadomestek, ki ne naredi nicesar. */
    private fun uporabna(c: Context, a: android.content.pm.ActivityInfo): Boolean {
        if (a.packageName == c.packageName) return true
        if (a.name in ZAPRE_SE) return false
        // Brez a.enabled: to je vrednost iz manifesta. Sistem nekatere zaslone (npr. Moznosti za razvijalce) vklopi sele
        // med delovanjem; resolveActivity in queryIntentActivities izklopljenih ze ne vrneta.
        if (!a.exported) return false
        if (a.permission != null && c.checkSelfPermission(a.permission) != PackageManager.PERMISSION_GRANTED) return false
        return !VrsticaPravila.jeNadomestek(a.packageName, a.name)
    }

    /**
     * Namera, ki na tej napravi odpre pravo dejavnost, ali null. Kadar namero sprejme vec dejavnosti (sistem bi
     * pokazal izbirnik) in je med njimi uporabna ena sama - na televizorju sta to pogosto Googlov nadomestek in pravi
     * zaslon -, jo odpremo neposredno: izbirnik z mrtvo moznostjo bi bil za daljinec odvec korak.
     */
    fun cilj(c: Context, namera: Intent): Intent? {
        val pm = c.packageManager
        val r = try { pm.resolveActivity(namera, PackageManager.MATCH_DEFAULT_ONLY) } catch (_: Throwable) { null } ?: return null
        val a = r.activityInfo ?: return null
        if (!(a.packageName == "android" && a.name.endsWith("ResolverActivity"))) return if (uporabna(c, a)) namera else null
        val vse = try { pm.queryIntentActivities(namera, PackageManager.MATCH_DEFAULT_ONLY) } catch (_: Throwable) { emptyList() }
        var dobre = vse.mapNotNull { it.activityInfo }.filter { uporabna(c, it) }
        // Philips: datum in jezik sprejmeta tudi glavni meni nastavitev; pravi zaslon je tisti, ki ni splosni meni.
        if (dobre.size > 1) {
            val splosni = komponenta(c, Intent(android.provider.Settings.ACTION_SETTINGS))
            dobre.filter { it.packageName + "/" + it.name != splosni }.takeIf { it.isNotEmpty() }?.let { dobre = it }
        }
        return when (dobre.size) {
            0 -> null
            1 -> Intent(namera).setClassName(dobre[0].packageName, dobre[0].name)
            else -> namera
        }
    }

    private fun komponenta(c: Context, namera: Intent): String? = namera.component?.flattenToShortString() ?: try {
        c.packageManager.resolveActivity(namera, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let { it.packageName + "/" + it.name }
    } catch (_: Throwable) { null }

    /**
     * Vnosi, ki jih ta naprava res odpre, s pripadajoco namero; vrstni red kot v [VNOSI]. Vnos, ki odpre isti splosni
     * zaslon kot »Vse nastavitve« (Philips: Zaslon in Zvok odpreta glavni meni nastavitev), izpustimo - trije enaki
     * vnosi z razlicnimi imeni bi uporabnika zmedli.
     */
    fun naVoljo(c: Context): List<Pair<Vnos, Intent>> {
        val brez = ArrayList<String>()
        val najdeni = VNOSI.mapNotNull { v ->
            val kandidati = if (!v.pogoj(c)) emptyList() else v.namera?.let { listOf(it(c)) } ?: v.akcije.map { Intent(it) }
            val n = kandidati.firstNotNullOfOrNull { cilj(c, it) }
            if (n == null) brez += v.kljuc
            n?.let { v to it }
        }
        val splosni = najdeni.firstOrNull { it.first.kljuc == "vse" }?.let { komponenta(c, it.second) }
        val podvojeni = if (splosni == null) emptySet() else
            najdeni.filter { it.first.kljuc != "vse" && it.second.component == null && komponenta(c, it.second) == splosni }.map { it.first.kljuc }.toSet()
        // Za preverjanje na napravah (logcat -s SafeerNastavitve): kaj je na voljo in kaj ta naprava nima.
        android.util.Log.i("SafeerNastavitve", "sistemske: ${najdeni.size - podvojeni.size} na voljo; brez: ${brez.joinToString(",")}" +
            "; kot vse nastavitve: ${podvojeni.joinToString(",")}")
        return najdeni.filter { it.first.kljuc !in podvojeni }
    }

    /**
     * Besedila niza v vseh jezikih aplikacije (sl, en, de, es, fr, it), za iskanje: uporabnik najde nastavitev, ne
     * glede na to, v katerem jeziku jo isce (npr. »WLAN«, »Bildschirm«, »svetlost«).
     */
    class Jeziki(private val c: Context) {
        private val konteksti: List<Context> by lazy {
            si.safeer.tv.JezikVmesnika.JEZIKI.mapNotNull { (oznaka, _) ->
                try {
                    val k = android.content.res.Configuration(c.resources.configuration)
                    k.setLocale(java.util.Locale.forLanguageTag(oznaka))
                    c.createConfigurationContext(k)
                } catch (_: Throwable) { null }
            }
        }
        private val predpomnilnik = HashMap<Int, List<String>>()

        /** Klicana iz ozadja (iskanje sistemskih nastavitev) in z zaslona (nastavitve Safeer), zato zaklenjena. */
        @Synchronized
        fun vsa(id: Int): List<String> = predpomnilnik.getOrPut(id) {
            (listOf(c) + konteksti).mapNotNull { k -> try { k.getString(id) } catch (_: Throwable) { null } }.distinct()
        }
    }
}
