package si.safeer.tv.os

/**
 * Vnosi, ki jih dodatki Stremio podajo med tokovi, a niso vsebina: prosnja za donacijo, vabilo v Discord,
 * "No streams found". Uporabnik jih ne vidi nikjer - ne kot izbire ne kot okna. Cisto pravilo (brez Androida),
 * da ga preverja tests/ObvestilaTokovTest.kt.
 */
object ObvestilaTokov {
    /** Strani prosenj in skupnosti: povezava tja ni tok, tudi ce jo dodatek poda kot `url`. */
    private val GOSTITELJI = listOf("discord.gg", "discord.com", "discordapp.com", "ko-fi.com", "patreon.com",
        "buymeacoffee.com", "paypal.com", "paypal.me", "t.me", "telegram.me", "opencollective.com", "liberapay.com", "boosty.to")
    private val RX_BESEDILO = Regex("(?i)\\bdonat(e|ion)s?\\b|discord|no streams? found|buy me a coffee|\\bko-?fi\\b|patreon")
    private val RX_MEDIJ = Regex("(?i)\\.(m3u8|mpd|mp4|mkv|webm|avi|mov|m4v|ts|mp3|m4a|aac|flac|ogg|opus|wav)(\\?|$)")
    private val RX_GOSTITELJ = Regex("(?i)^[a-z][a-z0-9+.-]*://(?:[^/@]*@)?([^/:?#]+)")

    fun gostitelj(url: String): String = RX_GOSTITELJ.find(url.trim())?.groupValues?.get(1)?.lowercase() ?: ""

    /**
     * [besedilo] = ime in opis vnosa; [znakiToka] = dodatek je podal podatke prave datoteke (ime datoteke, velikost,
     * zgoscena vrednost, glave posrednika). Obvestilo je povezava na stran skupnosti ali pa besedilo obvestila brez
     * znakov pravega toka - pravi tok, ki v opisu le omeni Discord, ostane.
     */
    fun je(url: String, besedilo: String, znakiToka: Boolean): Boolean {
        val g = gostitelj(url)
        if (GOSTITELJI.any { g == it || g.endsWith(".$it") } || g.startsWith("donate.")) return true
        if (!RX_BESEDILO.containsMatchIn(besedilo)) return false
        return !(znakiToka || RX_MEDIJ.containsMatchIn(url))
    }
}
