package wstunnel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
data class ConnectConfig(
    @SerialName("i")
    val id: String,

    @SerialName("t")
    val type: Type,
) : Message() {
    @Serializable
    sealed class Type {
        @Serializable
        @SerialName("L")
        object Listen: Type()

        @Serializable
        @SerialName("F")
        object Forward: Type()

        @Serializable
        @SerialName("DL")
        object DynamicListen: Type()

        @Serializable
        @SerialName("DF")
        data class DynamicForward(
            val host: String,
            val port: Int,
        ): Type()
    }
}

@Serializable
@SerialName("1")
data class ConnectionEstablished(
    @SerialName("t")
    val type: Type,
) : Message() {
    @Serializable
    sealed class Type {
        @Serializable
        @SerialName("L")
        object Listen: Type()

        @Serializable
        @SerialName("F")
        object Forward: Type()

        @Serializable
        @SerialName("DL")
        object DynamicListen: Type()

        @Serializable
        @SerialName("DF")
        data class DynamicForward(
            val host: String,
            val port: Int,
        ): Type()
    }
}
//endregion
