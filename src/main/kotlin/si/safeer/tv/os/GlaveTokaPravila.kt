package si.safeer.tv.os

/**
 * Glave toka Stremio (`behaviorHints.proxyHeaders.request`: User-Agent, Referer, Cookie ...) po gostitelju (neodvisni pregled
 * 12. 11. 2026, N5). Prej so glave enega dodatka veljale za VSAK tok istega gostitelja, tudi drugega dodatka (skupen CDN,
 * strezniki na isti IP): dodatek A bi tako odnesel Cookie ali Referer dodatka B in obratno. Zasebni naslovi niso blokirani
 * (osebni dodatki so lahko v domacem omrezju ali 127.0.0.1); omejimo samo, KOMU glave gredo.
 */
object GlaveTokaPravila {
    /**
     * Glave na ravni gostitelja po registraciji toka z glavami [nove]: brez glav -> ni jih; prva ali enake -> te;
     * DRUGACNE glave istega gostitelja (drug dodatek) -> nobene (dvoumno; glave posameznega toka ostanejo v SpletniVir.glaveToka).
     */
    fun zdruzi(obstojece: Map<String, String>?, nove: Map<String, String>): Map<String, String>? = when {
        nove.isEmpty() -> null
        obstojece == null || obstojece == nove -> nove
        else -> null
    }

    /** Glave gostitelja gredo samo zahtevi, ki NI tok z lastnimi registriranimi glavami (ta jih dobi po toku, ne po gostitelju). */
    fun gostiteljSme(zahtevaJeRegistriranTok: Boolean): Boolean = !zahtevaJeRegistriranTok
}
