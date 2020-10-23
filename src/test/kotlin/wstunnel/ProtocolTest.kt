package wstunnel

import io.kotest.assertions.json.shouldMatchJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ProtocolTest : FunSpec({
    //region Message Tests
    test("serialize message") {
        val encodedMsg = ListenConfig(SocketRole.Listen, "1234").encode()
        encodedMsg.shouldMatchJson("""{"T":"0","r":"0","i":"1234"}""")
    }

    test("deserialize message") {
        val encoded = """{"T":"0","r":"0","i":"x1234"}"""
        val encodedMsg = encoded.decode()
        encodedMsg.shouldNotBeNull()
        encodedMsg.shouldBeInstanceOf<ListenConfig>()
        encodedMsg.id.shouldBe("x1234")
        encodedMsg.connType.shouldBe(SocketRole.Listen)
    }
    //endregion
})
