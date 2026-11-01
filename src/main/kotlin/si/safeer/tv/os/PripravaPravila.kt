package si.safeer.tv.os

object PripravaPravila {
    fun odpriTakoj(video: Boolean, rocno: Boolean): Boolean {
        return video && !rocno
    }
}
