package si.safeer.tv.os

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method

class NedavnoTest {
    @Test
    fun ohraniImdbInEpizodo() {
        val s = Jamendo.Skladba(
            id = "tt12345:1:2",
            naslov = "The Prestige",
            izvajalec = "",
            slika = "",
            zvok = "",
            povezava = "",
            video = true,
            season = 1,
            episode = 2,
            imdbId = "tt12345"
        )
        
        val pisiMethod: Method = MedijskiViri::class.java.getDeclaredMethod("pisiSkladbe", List::class.java)
        pisiMethod.isAccessible = true
        val json = pisiMethod.invoke(MedijskiViri, listOf(s)) as String
        
        val beriMethod: Method = MedijskiViri::class.java.getDeclaredMethod("beriSkladbe", String::class.java)
        beriMethod.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val parsed = (beriMethod.invoke(MedijskiViri, json) as List<Jamendo.Skladba>)[0]
        
        assertEquals("tt12345:1:2", parsed.id)
        assertEquals(1, parsed.season)
        assertEquals(2, parsed.episode)
        assertEquals("tt12345", parsed.imdbId)
    }
}
