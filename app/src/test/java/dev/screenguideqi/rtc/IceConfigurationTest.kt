package dev.screenguideqi.rtc

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IceConfigurationTest {
    private fun payload(uris: Any?, username: String = "1731500000", credential: String = "abc123==") =
        JSONObject().put("uris", if (uris is JSONArray) uris else JSONArray(uris as List<*>))
            .put("username", username).put("credential", credential)

    @Test fun parsesValidTurnUriList() {
        val uris = IceConfiguration.parseTurnUris(payload(
            listOf("turn:turn.example.com:3478?transport=udp", "turns:turn.example.com:443?transport=tcp")))
        assertEquals(listOf("turn:turn.example.com:3478?transport=udp", "turns:turn.example.com:443?transport=tcp"), uris)
    }

    @Test fun stage0ServerWithoutIcePayloadFallsBackToDirect() {
        assertNull(IceConfiguration.parseTurnUris(null))
        assertNull(IceConfiguration.parseTurnUris(JSONObject()))
        assertNull(IceConfiguration.parseTurnUris(JSONObject().put("uris", "not-an-array")))
    }

    @Test fun nonTurnSchemesAreRejectedWholesale() {
        assertNull(IceConfiguration.parseTurnUris(payload(listOf("turn:ok.example.com:3478", "http://evil.example.com"))))
        assertNull(IceConfiguration.parseTurnUris(payload(listOf("stun:stun.example.com:3478"))))
    }

    @Test fun oversizedOrEmptyListsAreRejected() {
        assertNull(IceConfiguration.parseTurnUris(payload(emptyList<Any>())))
        assertNull(IceConfiguration.parseTurnUris(payload(List(9) { "turn:host$it.example.com:3478" })))
        assertNull(IceConfiguration.parseTurnUris(payload(listOf("turn:${"a".repeat(256)}:3478"))))
    }

    @Test fun missingCredentialsPreventTurnInjection() {
        val withUris = IceConfiguration.parseTurnUris(payload(listOf("turn:turn.example.com:3478")))
        assertEquals(listOf("turn:turn.example.com:3478"), withUris)
        assertNull(IceConfiguration.fromSignaling(JSONObject().put("uris", JSONArray(listOf("turn:turn.example.com:3478")))))
        assertNull(IceConfiguration.fromSignaling(payload(listOf("turn:turn.example.com:3478"), username = "")))
        assertNull(IceConfiguration.fromSignaling(payload(listOf("turn:turn.example.com:3478"), credential = "")))
    }
}
