package si.safeer.tv.os

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsenetNacrtPravilaTest {
    @Test
    fun testShraniInPreberi() {
        UsenetNacrtPravila.pocisti()
        UsenetNacrtPravila.shraniNacrt("tt123", true, "ok")
        val n = UsenetNacrtPravila.preberiNacrt("tt123")
        assertEquals(true, n?.predvajljiv)
        assertEquals("ok", n?.razlog)
        
        UsenetNacrtPravila.shraniNacrt("tt456", false, "manjka segment")
        val n2 = UsenetNacrtPravila.preberiNacrt("tt456")
        assertEquals(false, n2?.predvajljiv)
        assertEquals("manjka segment", n2?.razlog)
        
        assertNull(UsenetNacrtPravila.preberiNacrt("tt789"))
    }
}
