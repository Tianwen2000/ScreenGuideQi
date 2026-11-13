package dev.screenguideqi.rtc

import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection

/**
 * Injectable ICE settings. TURN entries always come from the short-lived server
 * response attached to session.accepted; the long-term secret never reaches the APK.
 */
data class IceConfiguration(val servers: List<PeerConnection.IceServer>) {
    companion object {
        private const val MAX_URIS = 8
        private const val MAX_URI_LENGTH = 255
        private const val MAX_CREDENTIAL_LENGTH = 512

        fun direct() = IceConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        ))

        /**
         * Builds from the session.accepted ice payload
         * {"uris":["turn:host:3478?transport=udp",...],"username":"...","credential":"..."}.
         * Stage 0 servers send no ice object; null makes the caller fall back to direct().
         */
        fun fromSignaling(payload: JSONObject?): IceConfiguration? {
            val uris = parseTurnUris(payload) ?: return null
            val username = payload!!.optString("username", "")
            val credential = payload.optString("credential", "")
            if (username.isEmpty() || username.length > MAX_CREDENTIAL_LENGTH) return null
            if (credential.isEmpty() || credential.length > MAX_CREDENTIAL_LENGTH) return null
            val servers = ArrayList<PeerConnection.IceServer>(uris.size + 1)
            servers.add(direct().servers.first())
            for (uri in uris) {
                servers.add(PeerConnection.IceServer.builder(uri)
                    .setUsername(username).setPassword(credential).createIceServer())
            }
            return IceConfiguration(servers)
        }

        /** Pure payload validation without native types, unit-testable on the JVM. */
        fun parseTurnUris(payload: JSONObject?): List<String>? {
            if (payload == null) return null
            val array: JSONArray = payload.optJSONArray("uris") ?: return null
            if (array.length() !in 1..MAX_URIS) return null
            val uris = ArrayList<String>(array.length())
            for (index in 0 until array.length()) {
                val uri = array.optString(index, "")
                if (uri.length > MAX_URI_LENGTH || !(uri.startsWith("turn:") || uri.startsWith("turns:"))) return null
                uris.add(uri)
            }
            return uris
        }
    }
}
