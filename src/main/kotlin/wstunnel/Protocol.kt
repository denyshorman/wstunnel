package wstunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

//region Encoder
private val encoder = Json {
    classDiscriminator = "T"
}

fun Message.encode(): String {
    return encoder.encodeToString(this)
}

fun String.decode(): Message? {
    return encoder.decodeFromString(this)
}
//endregion

//region Messages
@Serializable
sealed class Message

@Serializable
@SerialName("0")
data class ListenConfig(
    @SerialName("r")
    val connType: SocketRole,

    @SerialName("i")
    val id: String
) : Message()

@Serializable
@SerialName("1")
data object ConnectionEstablished : Message()
//endregion

//region Other Models
@Serializable
enum class SocketRole {
    @SerialName("0")
    Listen,

    @SerialName("1")
    Forward,
}
//endregion
