package si.safeer.tv.link

import java.io.InputStream
import java.io.OutputStream

/** Nadomestek za JVM preizkuse: pravi streznik datotek (MediaStore) je samo za Android. */
object DatotekeStreznik {
    fun prekHuba(metoda: String, pot: String, glave: Map<String, String>, vhod: InputStream, izhod: OutputStream): Boolean = false
}
