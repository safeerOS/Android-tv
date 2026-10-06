package si.safeer.tv.link

import org.json.JSONArray
import org.json.JSONObject

/**
 * Katalog aplikacij, ki ga naprava objavi svojemu Hubu (cast.register `apps` / apps.announce): objekt po imenu paketa,
 * {"<paket>": {"name": "...", "kind": "android"}}. Brez ikon - Hub jih ne hrani. Brez Androida, da pravilo preveri
 * navaden JVM preizkus skupaj s Hubom (tests/UsmerjevalnikTest.kt).
 *
 * Meje doloca prejemnik: Hub hrani najvec 200 vnosov, katalog, vecji od 32 KiB, pa zavrze V CELOTI
 * (HubUsmerjevalnik.preveriKatalog) - naprava z zelo veliko aplikacijami bi ostala brez kataloga. Zato tu stejemo
 * oboje; kar ne gre vec, odpade na koncu seznama. Celoten seznam gre po kosih z `apps.list`.
 */
object KatalogAplikacij {
    const val NAJVEC_VNOSOV = 200
    const val NAJVEC_ZNAKOV = 30_000

    /** [seznam]: [{"package", "label"}] v vrstnem redu prikaza. Vrne katalog v mejah Huba. */
    fun izSeznama(seznam: JSONArray): JSONObject {
        val k = JSONObject()
        var znakov = 2
        var vnosov = 0
        for (i in 0 until seznam.length()) {
            if (vnosov >= NAJVEC_VNOSOV) break
            val z = seznam.optJSONObject(i) ?: continue
            val paket = z.optString("package")
            if (paket.isBlank() || k.has(paket)) continue
            val vnos = JSONObject().put("name", z.optString("label")).put("kind", "android")
            // Toliko znakov doda vnos zapisanemu katalogu: "paket":{...}, - stejemo zapisano obliko (z ubeznimi znaki).
            val teza = JSONObject.quote(paket).length + vnos.toString().length + 2
            if (znakov + teza > NAJVEC_ZNAKOV) break
            znakov += teza
            k.put(paket, vnos)
            vnosov++
        }
        return k
    }
}
