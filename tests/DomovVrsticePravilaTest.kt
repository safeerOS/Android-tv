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
    fun `odmik in razmik`() {
        assertEquals(0, DomovVrsticePravila.leviOdmik())
        assertEquals(0, DomovVrsticePravila.odmikNaslova())
        assertEquals(14, DomovVrsticePravila.razmikGlaveZgoraj())
    }

    @Test
    fun `fokus po osvezi`() {
        assertEquals(DomovVrsticePravila.DelVrstice.PREMAKNI, DomovVrsticePravila.fokusPoOsvezi(true))
        assertEquals(DomovVrsticePravila.DelVrstice.STIKALO, DomovVrsticePravila.fokusPoOsvezi(false))
    }
}
