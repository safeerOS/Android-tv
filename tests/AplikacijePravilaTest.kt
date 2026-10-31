package si.safeer.tv.os

import org.junit.Assert.assertEquals
import org.junit.Test

class AplikacijePravilaTest {

    @Test
    fun dejanjeOdstranitve() {
        assertEquals(AplikacijePravila.DejanjeOdstranitve.ODSTRANI, AplikacijePravila.dejanjeOdstranitve(jeSistemska = false, jePosodobljenaSistemska = false))
        assertEquals(AplikacijePravila.DejanjeOdstranitve.ODSTRANI, AplikacijePravila.dejanjeOdstranitve(jeSistemska = false, jePosodobljenaSistemska = true))
        assertEquals(AplikacijePravila.DejanjeOdstranitve.ONEMOGOCI, AplikacijePravila.dejanjeOdstranitve(jeSistemska = true, jePosodobljenaSistemska = false))
        assertEquals(AplikacijePravila.DejanjeOdstranitve.ODSTRANI, AplikacijePravila.dejanjeOdstranitve(jeSistemska = true, jePosodobljenaSistemska = true))
    }
}
