package android.net
class Uri private constructor(private val uri: java.net.URI) {
    val host: String? get() = uri.host
    val scheme: String? get() = uri.scheme
    val port: Int get() = uri.port
    companion object { fun parse(value: String) = Uri(java.net.URI(value)) }
}
