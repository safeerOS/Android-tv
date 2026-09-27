package si.safeer.tv.link

/*
 * Safeer Global Mesh (temelj, se ni vklopljen): kako doseci seznanjeno napravo, kadar ni v istem
 * omrezju. Vrstni red je vedno LOCAL -> DIRECT_INTERNET -> RELAY -> OFFLINE; LAN zmaga vedno,
 * internet in rele sta izrecna izbira uporabnika, neseznanjena naprava ali naprava brez kljuca
 * ni dosegljiva nikoli. Ni se povezano z Linkom: manjkajo koordinacija (services/coordination v
 * safeer-lms), ICE/STUN/TURN in E2EE, vezan na kljuce naprav. Glej docs/LINK-CORE.md 12.
 */

enum class MeshReachability { LOCAL, DIRECT_INTERNET, RELAY, OFFLINE }

data class MeshPeer(
    val deviceId: String,
    val paired: Boolean,
    val publicKeyFingerprint: String,
    val localReachable: Boolean = false,
    val directInternetReachable: Boolean = false,
    val relayReachable: Boolean = false
)

data class MeshPolicy(
    val internetEnabled: Boolean = false,
    val relayEnabled: Boolean = false
) {
    fun route(peer: MeshPeer): MeshReachability {
        if (!peer.paired || peer.publicKeyFingerprint.isBlank()) return MeshReachability.OFFLINE
        if (peer.localReachable) return MeshReachability.LOCAL
        if (!internetEnabled) return MeshReachability.OFFLINE
        if (peer.directInternetReachable) return MeshReachability.DIRECT_INTERNET
        if (relayEnabled && peer.relayReachable) return MeshReachability.RELAY
        return MeshReachability.OFFLINE
    }
}

/**
 * Presence records deliberately contain no private keys or content.
 * They are suitable for a coordination service that only helps paired peers meet.
 */
data class PresenceRecord(
    val deviceId: String,
    val publicKeyFingerprint: String,
    val expiresAtEpochSeconds: Long,
    val candidateHints: List<String> = emptyList()
)
