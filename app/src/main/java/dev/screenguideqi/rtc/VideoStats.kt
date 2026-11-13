package dev.screenguideqi.rtc

import org.webrtc.RTCStats

/** Only exposes counters and candidate types; never exposes addresses or raw reports. */
internal data class VideoStats(
    val message: String,
    val decodedVideo: Boolean,
) {
    companion object {
        fun summarize(stats: Map<String, RTCStats>, sending: Boolean): VideoStats {
            val transport = stats.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] is String }
            val selectedId = transport?.members?.get("selectedCandidatePairId") as? String
            val selected = selectedId?.let(stats::get) ?: stats.values.firstOrNull {
                it.type == "candidate-pair" && it.members["state"] == "succeeded" &&
                    (it.members["nominated"] == true || it.members["selected"] == true)
            }
            val local = (selected?.members?.get("localCandidateId") as? String)?.let(stats::get)
            val remote = (selected?.members?.get("remoteCandidateId") as? String)?.let(stats::get)
            fun candidateType(candidate: RTCStats?): String = when (candidate?.members?.get("candidateType")) {
                "host" -> "host"
                "srflx" -> "srflx"
                "prflx" -> "prflx"
                "relay" -> "relay"
                else -> "未知"
            }
            val path = if (selected == null) "路径待建立" else "路径 ${candidateType(local)}/${candidateType(remote)}"
            val media = stats.values.filter {
                it.type == (if (sending) "outbound-rtp" else "inbound-rtp") &&
                    (it.members["kind"] == "video" || it.members["mediaType"] == "video")
            }
            if (media.isEmpty()) return VideoStats("${if (sending) "尚无视频发送统计" else "尚未收到视频媒体"}；$path", false)
            val bytesKey = if (sending) "bytesSent" else "bytesReceived"
            val framesKey = if (sending) "framesEncoded" else "framesDecoded"
            val bytes = media.mapNotNull { it.members[bytesKey].asLong() }.takeIf { it.isNotEmpty() }?.sum()
            val frames = media.mapNotNull { it.members[framesKey].asLong() }.takeIf { it.isNotEmpty() }?.sum()
            val action = if (sending) "发送" else "收到"
            val frameLabel = if (sending) "编码" else "解码"
            return VideoStats("视频统计：$action ${bytes?.toString() ?: "未知"} 字节，$frameLabel ${frames?.toString() ?: "未知"} 帧；$path",
                !sending && frames != null && frames > 0)
        }

        private fun Any?.asLong(): Long? = when (this) {
            is Number -> toLong()
            is String -> toLongOrNull()
            else -> null
        }
    }
}
