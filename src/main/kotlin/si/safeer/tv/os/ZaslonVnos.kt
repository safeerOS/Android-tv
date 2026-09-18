package si.safeer.tv.os

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject

/**
 * Prevod tega, kar uporabnik naredi na televizorju, v dogodke za racunalnik. Televizor poslje samo
 * oznake (`gor`, `ok`, `nazaj`, `f5` ...), racunalnik pa jih prevede v tipke svojega namizja -
 * tako z omrezja nikoli ne pride nic, cesar racunalnik ne bi poznal.
 *
 * Podprti so daljinec (smerne tipke, OK, Nazaj, tipke predvajanja), tipkovnica (crke gredo kot
 * besedilo, posebne tipke po imenu), miska (relativni premik in kliki) in igralni plosek: leva
 * palica premika miskin kazalec, A klikne, B je Nazaj, X desni klik, ramena pa drsita.
 */
object ZaslonVnos {

    /** Tipke, ki jih znamo poimenovati; ime mora poznati tudi core/link_vnos.py. */
    private val TIPKE = mapOf(
        KeyEvent.KEYCODE_DPAD_UP to "gor",
        KeyEvent.KEYCODE_DPAD_DOWN to "dol",
        KeyEvent.KEYCODE_DPAD_LEFT to "levo",
        KeyEvent.KEYCODE_DPAD_RIGHT to "desno",
        KeyEvent.KEYCODE_DPAD_CENTER to "ok",
        KeyEvent.KEYCODE_ENTER to "vnasalka",
        KeyEvent.KEYCODE_NUMPAD_ENTER to "vnasalka",
        KeyEvent.KEYCODE_DEL to "vracalka",
        KeyEvent.KEYCODE_FORWARD_DEL to "brisalka",
        KeyEvent.KEYCODE_TAB to "tabulator",
        KeyEvent.KEYCODE_SPACE to "presledek",
        KeyEvent.KEYCODE_ESCAPE to "ubezna",
        KeyEvent.KEYCODE_PAGE_UP to "stran_gor",
        KeyEvent.KEYCODE_PAGE_DOWN to "stran_dol",
        KeyEvent.KEYCODE_MOVE_HOME to "zacetek",
        KeyEvent.KEYCODE_MOVE_END to "konec",
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE to "predvajaj",
        KeyEvent.KEYCODE_MEDIA_PLAY to "predvajaj",
        KeyEvent.KEYCODE_MEDIA_PAUSE to "predvajaj",
        KeyEvent.KEYCODE_MEDIA_STOP to "ustavi",
        KeyEvent.KEYCODE_MEDIA_NEXT to "naprej",
        KeyEvent.KEYCODE_MEDIA_PREVIOUS to "prejsnja",
        KeyEvent.KEYCODE_F1 to "f1", KeyEvent.KEYCODE_F2 to "f2", KeyEvent.KEYCODE_F3 to "f3",
        KeyEvent.KEYCODE_F4 to "f4", KeyEvent.KEYCODE_F5 to "f5", KeyEvent.KEYCODE_F6 to "f6",
        KeyEvent.KEYCODE_F7 to "f7", KeyEvent.KEYCODE_F8 to "f8", KeyEvent.KEYCODE_F9 to "f9",
        KeyEvent.KEYCODE_F10 to "f10", KeyEvent.KEYCODE_F11 to "f11", KeyEvent.KEYCODE_F12 to "f12",
        // Igralni plosek
        KeyEvent.KEYCODE_BUTTON_A to "ok",
        KeyEvent.KEYCODE_BUTTON_B to "nazaj",
        KeyEvent.KEYCODE_BUTTON_Y to "celozaslonsko",
        KeyEvent.KEYCODE_BUTTON_START to "meni",
    )

    /** Gumbi plosecka, ki delujejo kot miska. */
    private val KLIKI = mapOf(
        KeyEvent.KEYCODE_BUTTON_X to "desni",
        KeyEvent.KEYCODE_BUTTON_THUMBL to "levi",
    )

    /** Ramena plosecka drsita po strani. */
    private val KOLESCE = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to "gor",
        KeyEvent.KEYCODE_BUTTON_R1 to "dol",
    )

    /**
     * Tipke, ki jih je smiselno **drzati**, ne le pritisniti: igra pospesuje, dokler drzis, seznam
     * se pomika, dokler drzis. Za te posljemo pritisk in spust locena dogodka; racunalnik tipko
     * drzi natanko tako, kot bi jo prst na tipkovnici (za ponavljanje poskrbi X sam).
     *
     * Bliznjic s krmilkami (ctrl+s) tu ni: teh nihce ne drzi, ob prekinjeni povezavi pa bi
     * krmilka ostala pritisnjena.
     */
    private val DRZLJIVE = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_BUTTON_A,
    )

    /** Ali za to tipko posljemo pritisk in spust (in ne kratkega pritiska). */
    fun jeDrzljiva(koda: Int): Boolean = koda in DRZLJIVE && TIPKE.containsKey(koda)

    /** Pritisk ([dol] = true) ali spust drzane tipke; null, kadar tipke ne poznamo. */
    fun drzanje(koda: Int, dol: Boolean): JSONObject? {
        if (!jeDrzljiva(koda)) return null
        val ime = TIPKE[koda] ?: return null
        return JSONObject().put("vrsta", if (dol) "tipka_dol" else "tipka_gor").put("tipka", ime)
    }

    /** Daljinec brez miske: kazalec se ob drzanju smerne tipke pospesuje od mirne do hitre. */
    const val KAZALEC_ZACETNA = 5f
    const val KAZALEC_NAJVECJA = 38f
    const val KAZALEC_POSPESEK = 1.12f

    /** Smer smerne tipke kot enotski premik; null, kadar tipka ni smerna. */
    fun smer(koda: Int): Pair<Int, Int>? = when (koda) {
        KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
        KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
        KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
        KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
        else -> null
    }

    /** Tipke, ki preklopijo med kazalcem in tipkami; vsak daljinec ima vsaj eno od njih. */
    fun jePreklop(koda: Int): Boolean = koda == KeyEvent.KEYCODE_MENU ||
        koda == KeyEvent.KEYCODE_INFO || koda == KeyEvent.KEYCODE_BUTTON_START ||
        koda == KeyEvent.KEYCODE_BUTTON_SELECT || koda == KeyEvent.KEYCODE_GUIDE

    /** Gumb ali kolesce miske, prikljucene na televizor. */
    fun izMiskinihGumbov(dogodek: MotionEvent): JSONObject? {
        if (dogodek.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE) return null
        if (dogodek.actionMasked == MotionEvent.ACTION_SCROLL) {
            val koliko = dogodek.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (koliko == 0f) return null
            return JSONObject().put("vrsta", "kolesce")
                .put("smer", if (koliko > 0) "gor" else "dol")
                .put("koliko", kotlin.math.abs(koliko).toInt().coerceIn(1, 5))
        }
        if (dogodek.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) {
            val gumb = when (dogodek.actionButton) {
                MotionEvent.BUTTON_SECONDARY -> "desni"
                MotionEvent.BUTTON_TERTIARY -> "srednji"
                else -> "levi"
            }
            return klik(gumb)
        }
        return null
    }

    /** Kolikor daleč gre kazalec pri polnem odklonu palice v eni stotinki sekunde. */
    const val HITROST_PALICE = 22f
    /** Pod tem odklonom palice ne stejemo - palice v mirovanju nikoli ne kazejo natanko nic. */
    const val MRTVI_KOT = 0.18f

    /** Dogodek za tipko ali null, kadar je ne poznamo (takrat je ne posiljamo). */
    fun izTipke(koda: Int, dogodek: KeyEvent?): JSONObject? {
        KLIKI[koda]?.let { return JSONObject().put("vrsta", "klik").put("gumb", it) }
        KOLESCE[koda]?.let { return JSONObject().put("vrsta", "kolesce").put("smer", it).put("koliko", 3) }
        TIPKE[koda]?.let { return JSONObject().put("vrsta", "tipka").put("tipka", it) }
        // Crke, stevilke in locila s tipkovnice gredo kot besedilo: imen tipk ni treba poznati.
        val znak = dogodek?.unicodeChar ?: 0
        if (znak != 0 && znak >= 32) {
            return JSONObject().put("vrsta", "besedilo").put("besedilo", znak.toChar().toString())
        }
        return null
    }

    /** Premik miske, ki jo ima uporabnik prikljuceno na televizor (relativni premik). */
    fun izMiske(dogodek: MotionEvent): JSONObject? {
        if (dogodek.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE) return null
        val dx = dogodek.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val dy = dogodek.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
        if (dx == 0f && dy == 0f) return null
        return premik(dx.toInt(), dy.toInt())
    }

    /** Odklon leve palice plosecka; vrne null, kadar je palica v mirovanju. */
    fun izPalice(dogodek: MotionEvent): Pair<Float, Float>? {
        if (dogodek.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return null
        // Samo prava palica: smerni krizec pride tudi kot smerna tipka in bi kazalec premaknil dvakrat.
        val x = os(dogodek, MotionEvent.AXIS_X)
        val y = os(dogodek, MotionEvent.AXIS_Y)
        return if (x == 0f && y == 0f) null else (x to y)
    }

    private fun os(d: MotionEvent, glavna: Int): Float {
        val v = d.getAxisValue(glavna)
        return if (kotlin.math.abs(v) < MRTVI_KOT) 0f else v
    }

    fun premik(dx: Int, dy: Int): JSONObject? =
        if (dx == 0 && dy == 0) null
        else JSONObject().put("vrsta", "premik").put("dx", dx).put("dy", dy)

    fun klik(gumb: String = "levi"): JSONObject = JSONObject().put("vrsta", "klik").put("gumb", gumb)
}
