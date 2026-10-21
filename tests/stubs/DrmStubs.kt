package android.media

import java.util.UUID

open class MediaDrm(uuid: UUID) : AutoCloseable {
    override fun close() {}
    open fun release() {}
    open fun getPropertyString(propertyName: String): String = "L1"

    companion object {
        const val PROPERTY_SECURITY_LEVEL = "securityLevel"
        @JvmStatic
        fun isCryptoSchemeSupported(uuid: UUID): Boolean = true
    }
}
