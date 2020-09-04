package wstunnel

interface Protocol

enum class SocketRole {
    Listen,
    Forward,
}

data class ListenConfig(
    val connType: SocketRole,
    val id: String
) : Protocol

object ConnectionEstablished : Protocol


fun ListenConfig.serialize(): String {
    return "0,${connType.serialize()},$id"
}

fun SocketRole.serialize(): String {
    return when (this) {
        SocketRole.Listen -> "0"
        SocketRole.Forward -> "1"
    }
}

fun String?.deserializeSocketRole(): SocketRole? {
    if (this == null) return null
    if (this == "0") return SocketRole.Listen
    if (this == "1") return SocketRole.Forward
    return null
}

fun ConnectionEstablished.serialize(): String {
    return "1"
}

fun String?.deserializeProtocol(): Protocol? {
    if (this == null) return null
    val params = this.split(",")
    val msgType = params.getOrNull(0) ?: return null
    when (msgType) {
        "0" -> {
            val role = params.getOrNull(1)?.deserializeSocketRole() ?: return null
            val id = params.getOrNull(2) ?: return null
            if (id.length > 512) return null
            return ListenConfig(role, id)
        }
        "1" -> {
            return ConnectionEstablished
        }
        else -> return null
    }
}
