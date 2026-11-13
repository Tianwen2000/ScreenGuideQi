package dev.screenguideqi.signaling

import org.junit.Assert.assertThrows
import org.junit.Test

class SignalingAddressTest {
    @Test fun securePublicEndpointIsAccepted() {
        SignalingClient.validateAddress("wss://example.com/signal")
    }
    @Test fun publicCleartextIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SignalingClient.validateAddress("ws://example.com/signal") }
    }
    @Test fun domainWithPrivateLookingPrefixIsNotTreatedAsPrivateIp() {
        assertThrows(IllegalArgumentException::class.java) { SignalingClient.validateAddress("ws://10.attacker.example/signal") }
        assertThrows(IllegalArgumentException::class.java) { SignalingClient.validateAddress("ws://192.168.attacker.example/signal") }
    }
    @Test fun debugPrivateIpEndpointIsAccepted() {
        SignalingClient.validateAddress("ws://192.168.1.10:8787/signal")
        SignalingClient.validateAddress("ws://10.0.2.2:8787/signal")
    }
    @Test fun credentialsQueryAndWrongPathAreRejected() {
        for (address in listOf("wss://user:secret@example.com/signal", "wss://example.com/signal?token=secret", "wss://example.com/other")) {
            assertThrows(IllegalArgumentException::class.java) { SignalingClient.validateAddress(address) }
        }
    }
}
