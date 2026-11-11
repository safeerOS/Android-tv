package android.net

/** Nadomestek: omrezje preizkusa je omrezje racunalnika (vticnice ne vezemo). */
class Network {
    fun getAllByName(gostitelj: String): Array<java.net.InetAddress> = java.net.InetAddress.getAllByName(gostitelj)
    fun bindSocket(vticnica: java.net.Socket) { }
}
