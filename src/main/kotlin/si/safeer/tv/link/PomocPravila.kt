package si.safeer.tv.link

import si.safeer.tv.cast.JsonLahki

/**
 * »Pomagaj drugim napravam v Linku pri povezavi« (isto kot core/link_mesh.py na racunalniku, link-pomoc.json): zapis
 * {"vklopljeno": bool, "vprasano": bool}. Privzeto vklopljeno, uporabnik se ni bil vprasan; neberljiv zapis velja kot
 * privzeto (pomoc ostane vklopljena). Samo pravila brez Androida (tests/MeshTest.kt).
 */
object PomocPravila {
    class Stanje(val vklopljeno: Boolean, val vprasano: Boolean)

    fun izZapisa(zapis: String?): Stanje {
        val o = try { if (zapis.isNullOrBlank()) null else JsonLahki.objekt(zapis) } catch (_: Throwable) { null }
        // Kot na racunalniku: izklopljeno samo, ce je v zapisu izrecno false.
        return Stanje(vklopljeno = o?.logicno("vklopljeno") != false, vprasano = o?.logicno("vprasano") == true)
    }

    fun vZapis(vklopljeno: Boolean): String =
        JsonLahki.Zapis().logicno("vklopljeno", vklopljeno).logicno("vprasano", true).toString()
}
