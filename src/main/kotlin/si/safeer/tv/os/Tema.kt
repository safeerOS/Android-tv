package si.safeer.tv.os

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import si.safeer.tv.R

/**
 * Teme Safeer OS: privzeta Safeer + pet najbolj razsirjenih shem, ki jih uporabniki poznajo iz drugih programov
 * (Catppuccin Mocha, Tokyo Night, Gruvbox Dark, Nord, Dracula; uradne palete, vse MIT). Enak nabor kot Safeer OS
 * na racunalniku (Nastavitve › Videz). Barve v kodi gredo prek [osBarva] (atribut teme), v XML prek ?attr/osX.
 */
object Tema {
    const val KLJUC = "os_tema"
    data class Vnos(val id: String, val ime: String, val stil: Int, val stilPredvajalnik: Int, val vir: String)
    val VSE = listOf(
        Vnos("safeer", "Safeer", R.style.Theme_SafeerOs, R.style.Theme_SafeerOs_Predvajalnik, ""),
        Vnos("catppuccin", "Catppuccin Mocha", R.style.Theme_SafeerOs_Catppuccin, R.style.Theme_SafeerOs_Catppuccin_Predvajalnik, "catppuccin.com"),
        Vnos("tokyonight", "Tokyo Night", R.style.Theme_SafeerOs_Tokyonight, R.style.Theme_SafeerOs_Tokyonight_Predvajalnik, "tokyonight (enkia)"),
        Vnos("gruvbox", "Gruvbox Dark", R.style.Theme_SafeerOs_Gruvbox, R.style.Theme_SafeerOs_Gruvbox_Predvajalnik, "gruvbox (morhetz)"),
        Vnos("nord", "Nord", R.style.Theme_SafeerOs_Nord, R.style.Theme_SafeerOs_Nord_Predvajalnik, "nordtheme.com"),
        Vnos("dracula", "Dracula", R.style.Theme_SafeerOs_Dracula, R.style.Theme_SafeerOs_Dracula_Predvajalnik, "draculatheme.com"),
    )
    private val ATRIBUT = mapOf(
        R.color.os_ozadje to R.attr.osOzadje,
        R.color.os_kartica to R.attr.osKartica,
        R.color.os_kartica_dvignjena to R.attr.osKarticaDvignjena,
        R.color.os_mint to R.attr.osMint,
        R.color.os_mint_temna to R.attr.osMintTemna,
        R.color.os_besedilo to R.attr.osBesedilo,
        R.color.os_umirjeno to R.attr.osUmirjeno,
        R.color.os_crta to R.attr.osCrta,
        R.color.os_opozorilo to R.attr.osOpozorilo,
        R.color.os_sij to R.attr.osSij,
        R.color.os_kartica_steklo to R.attr.osKarticaSteklo,
        R.color.os_kartica_steklo_fokus to R.attr.osKarticaStekloFokus,
        R.color.os_kartica_obroba to R.attr.osKarticaObroba,
        R.color.os_meni_ozadje to R.attr.osMeniOzadje,
        R.color.os_meni_izbran_ozadje to R.attr.osMeniIzbranOzadje,
        R.color.os_meni_fokus_ozadje to R.attr.osMeniFokusOzadje,
        R.color.os_siva_pika to R.attr.osSivaPika,
        R.color.os_ploscica_app_ozadje to R.attr.osPloscicaAppOzadje
    )

    fun izbrana(c: Context): Vnos {
        val id = c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE).getString(KLJUC, "safeer")
        return VSE.firstOrNull { it.id == id } ?: VSE[0]
    }

    fun nastavi(c: Context, id: String) {
        c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE).edit().putString(KLJUC, id).apply()
    }

    /** Klici PRED super.onCreate/setContentView. [predvajalnik] = celozaslonska crna razlicica. */
    fun uporabi(a: Activity, predvajalnik: Boolean = false) {
        val v = izbrana(a)
        a.setTheme(if (predvajalnik) v.stilPredvajalnik else v.stil)
    }

    /** Barva iz teme za barvni vir os_*; brez preslikave ali izven teme vrne sam vir. */
    fun barva(c: Context, id: Int): Int {
        val attr = ATRIBUT[id] ?: return c.getColor(id)
        val tv = TypedValue()
        return if (c.theme.resolveAttribute(attr, tv, true) && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) tv.data else c.getColor(id)
    }
}

fun Context.osBarva(id: Int): Int = Tema.barva(this, id)
