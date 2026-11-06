package si.safeer.tv.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import si.safeer.tv.os.DomovVrsticePravila.Vrstica

class DomovVrsticePravilaTest {
    @Test
    fun `prikaz vrstic Doma`() {
        // Prazne vrstice se ne pokaze
        assertFalse(DomovVrsticePravila.prikaziVrstico(true, 0))
        assertTrue(DomovVrsticePravila.prikaziVrstico(false, 5))
    }

    @Test
    fun `vrstni red v oknu urejanja vrstic`() {
        val vse = listOf(Vrstica.NEDAVNO, Vrstica.SEZNAMI, Vrstica.PRIPOROCENO)
        var izbrane = listOf(Vrstica.SEZNAMI, Vrstica.PRIPOROCENO)
        
        // 1. Skrijemo SEZNAMI: vrstni red okna (vse) je nespremenjen, izbrane = [PRIPOROCENO]
        izbrane = DomovVrsticePravila.preklopiVOkna(izbrane, Vrstica.SEZNAMI, vse)
        assertEquals(listOf(Vrstica.PRIPOROCENO), izbrane)
        
        // 2. Tudi ko je skrita, se njen polozaj v oknu ne spremeni
        val premaknjeneVse = DomovVrsticePravila.premakniVOkna(vse, Vrstica.SEZNAMI, -1)
        assertEquals(listOf(Vrstica.SEZNAMI, Vrstica.NEDAVNO, Vrstica.PRIPOROCENO), premaknjeneVse)
        
        // 3. Spet pokazemo NEDAVNO (prej skrito, zdaj druga v oknu)
        izbrane = DomovVrsticePravila.preklopiVOkna(izbrane, Vrstica.NEDAVNO, premaknjeneVse)
        // Vrstni red v izbranih in vrsticah za Dom mora vzdrzevati izbrani relativen vrstni red v oknu
        assertEquals(listOf(Vrstica.NEDAVNO, Vrstica.PRIPOROCENO), izbrane)
    }

    @Test
    fun `priporoceno izbira po zvrsti in izvajalcu brez ze gledanih`() {
        class PreizkusniNaslov(
            override val id: String,
            override val naslov: String,
            override val izvajalec: String,
            override val zvrsti: List<String>
        ) : DomovVrsticePravila.Naslov

        val s1 = PreizkusniNaslov("1", "Ena", "Tarantino", listOf("Action"))
        val s2 = PreizkusniNaslov("2", "Dva", "Nolan", listOf("Drama"))
        val s3 = PreizkusniNaslov("3", "Tri", "Tarantino", listOf("Comedy"))
        val s4 = PreizkusniNaslov("4", "Stiri", "Smith", listOf("Action"))
        val s5 = PreizkusniNaslov("5", "Ena", "Drugi", listOf("Action")) // Isti naslov

        val zgodovina = listOf(s1)
        val katalogi = listOf(s1, s2, s3, s4, s5)

        val rezultat = DomovVrsticePravila.priporoceno(katalogi, zgodovina)
        
        assertEquals(2, rezultat.size) // s3 (Tarantino), s4 (Action)
        assertTrue(rezultat.any { it.id == "3" })
        assertTrue(rezultat.any { it.id == "4" })
    }
}
