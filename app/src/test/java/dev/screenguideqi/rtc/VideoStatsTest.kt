package dev.screenguideqi.rtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.RTCStats

class VideoStatsTest {
    private fun entry(type: String, id: String, vararg members: Pair<String, Any>) =
        RTCStats(0, type, id, mapOf(*members))

    private fun report(vararg entries: RTCStats) = entries.associateBy { it.id }

    @Test fun followsSelectedPairReferencesInsteadOfGuessingFromSucceededPairs() {
        val summary = VideoStats.summarize(report(
            entry("candidate-pair", "old", "state" to "succeeded", "nominated" to true,
                "localCandidateId" to "old-local", "remoteCandidateId" to "remote"),
            entry("local-candidate", "old-local", "candidateType" to "relay"),
            entry("transport", "transport", "selectedCandidatePairId" to "current"),
            entry("candidate-pair", "current", "localCandidateId" to "local", "remoteCandidateId" to "remote"),
            entry("local-candidate", "local", "candidateType" to "srflx", "address" to "192.0.2.123"),
            entry("remote-candidate", "remote", "candidateType" to "prflx", "address" to "198.51.100.123"),
            entry("inbound-rtp", "video", "kind" to "video", "bytesReceived" to 4096L, "framesDecoded" to 12L),
        ), sending = false)
        assertTrue(summary.message.contains("路径 srflx/prflx"))
        assertTrue(summary.message.contains("4096"))
        assertFalse(summary.message.contains("relay"))
        assertFalse(summary.message.contains("192.0.2.123"))
        assertFalse(summary.message.contains("198.51.100.123"))
        assertTrue(summary.decodedVideo)
    }

    @Test fun receivedPacketsAndFramesDoNotProveDecoding() {
        val summary = VideoStats.summarize(report(
            entry("inbound-rtp", "video", "kind" to "video", "bytesReceived" to 1000L, "framesReceived" to 10L),
        ), sending = false)
        assertFalse(summary.decodedVideo)
        assertTrue(summary.message.contains("解码 未知"))
    }

    @Test fun ownerUsesOutboundVideoInsteadOfAudioOrInboundStatistics() {
        val summary = VideoStats.summarize(report(
            entry("outbound-rtp", "video", "kind" to "video", "bytesSent" to 2048L, "framesEncoded" to 8L),
            entry("outbound-rtp", "audio", "kind" to "audio", "bytesSent" to 99999L),
            entry("inbound-rtp", "received", "kind" to "video", "bytesReceived" to 88888L, "framesDecoded" to 20L),
        ), sending = true)
        assertTrue(summary.message.contains("发送 2048"))
        assertTrue(summary.message.contains("编码 8"))
        assertFalse(summary.message.contains("99999"))
        assertFalse(summary.message.contains("88888"))
        assertFalse(summary.decodedVideo)
    }

    @Test fun selectedPathAloneDoesNotProveMediaArrived() {
        val summary = VideoStats.summarize(report(
            entry("candidate-pair", "pair", "state" to "succeeded", "nominated" to true,
                "localCandidateId" to "local", "remoteCandidateId" to "remote"),
            entry("local-candidate", "local", "candidateType" to "host"),
            entry("remote-candidate", "remote", "candidateType" to "host"),
        ), sending = false)
        assertTrue(summary.message.contains("尚未收到视频媒体"))
        assertTrue(summary.message.contains("host/host"))
        assertFalse(summary.decodedVideo)
    }

    @Test fun emptyOrZeroCountersAreNotReportedAsSuccessfulVideo() {
        assertFalse(VideoStats.summarize(emptyMap(), sending = false).decodedVideo)
        val zero = VideoStats.summarize(report(
            entry("inbound-rtp", "video", "kind" to "video", "bytesReceived" to 0L, "framesDecoded" to 0L),
        ), sending = false)
        assertFalse(zero.decodedVideo)
    }
}
