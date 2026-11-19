package si.safeer.tv.cast

import android.content.Context
import org.json.JSONObject
import si.safeer.tv.BuildConfig
import si.safeer.tv.os.LinaVmesnikPravila as P
import si.safeer.tv.os.LinaVmesnikShramba
import si.safeer.tv.os.UskladitevKljucev
import java.util.UUID

/**
 * Zapiski aplikacije Lina po ozjem krogu (kategorija [P.KATEGORIJA_ZAPISKI], isti prenos kot [SafeerMediaSync]).
 * Pravila zdruzevanja, meje in vektorji so v [P] (skupni vektorji Line); tu je samo lepilo. Zapiski se sprejmejo in
 * objavijo SAMO, ce je na tej napravi Lina z istim podpisom (lastnik 19. 11. 2026: »ce je nalozena na napravo«).
 * Posiljanje in dovoljene prejemnike (ozji krog) doloci [CastReceiverService.posljiUsklajevanje]; sprejem gre skozi Dostop.
 */
class LinaZapiskiSinh(private val context: Context, private val send: (JSONObject) -> Boolean) {
    private fun linaJe(): Boolean = BuildConfig.FLAVOR in setOf("os", "tablica", "telefon") && UskladitevKljucev.linaNalozena(context)

    fun requestLatest(): Boolean = linaJe() && send(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "sync.request")
        .put("payload", JSONObject().put("category", P.KATEGORIJA_ZAPISKI).put("since_version", 0)))

    /** Objavi trenutno stanje krogu (zapiski se zdruzijo pri prejemniku). */
    fun publish(): Boolean {
        if (!linaJe()) return false
        val shramba = LinaVmesnikShramba.get(context)
        val zapiski = shramba.zapiski()
        if (zapiski.isEmpty()) return false
        val ura = System.currentTimeMillis()
        return send(JSONObject().put("id", UUID.randomUUID().toString()).put("type", "sync.data").put("target", "all")
            .put("payload", JSONObject().put("category", P.KATEGORIJA_ZAPISKI).put("version", ura).put("timestamp", ura / 1000.0)
                .put("data", JSONObject(P.vJson(zapiski)))))
    }

    /** Sprejem sync.data: zdruzi v shrambo; vrne true, ce se je stanje spremenilo. Brez Line ali s slabim vhodom se ne zgodi nic. */
    fun accept(payload: JSONObject): Boolean {
        if (payload.optString("category") != P.KATEGORIJA_ZAPISKI || !linaJe()) return false
        val vhod = P.izTovora(P.KATEGORIJA_ZAPISKI, payload.optJSONObject("data")?.toString()) ?: return false
        val shramba = LinaVmesnikShramba.get(context)
        val prej = shramba.zapiski()
        val zdruzeno = shramba.sprejmi(vhod) ?: return false
        // Prejemnik ima kaj vec od posiljatelja: objavi zdruzeno stanje, da se obe strani ujameta (konvergenca, glej potrebnaObjava).
        if (P.potrebnaObjava(vhod, zdruzeno)) publish()
        return zdruzeno != prej
    }
}
