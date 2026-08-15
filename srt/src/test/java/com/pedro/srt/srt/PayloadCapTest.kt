package com.pedro.srt.srt

import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.common.frame.MediaFrame
import com.pedro.srt.mpeg2ts.MpegTsPacket
import com.pedro.srt.mpeg2ts.MpegTsPacketizer
import com.pedro.srt.utils.Constants
import com.pedro.srt.utils.SrtSocket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Roam's one functional change to this vendored module: the sender must never
 * emit an SRT payload larger than 6 TS packets (1128 bytes), so datagrams fit
 * cellular path MTUs. Feeds a video frame big enough to need many TS packets
 * and checks every written payload against the cap, including that the cap
 * actually engaged (full six-packet payloads were produced).
 */
@RunWith(MockitoJUnitRunner::class)
class PayloadCapTest {

    @Mock
    lateinit var connectChecker: ConnectChecker
    @Mock
    lateinit var socket: SrtSocket
    @Mock
    lateinit var commandsManager: CommandsManager

    @Test
    fun `GIVEN a large video frame WHEN sent THEN every payload fits six ts packets`() = runTest {
        val cap = 6 * MpegTsPacketizer.packetSize
        val sizes = mutableListOf<Int>()
        val latch = CountDownLatch(4)
        Mockito.`when`(commandsManager.audioCodec).thenReturn(AudioCodec.AAC)
        Mockito.`when`(commandsManager.videoCodec).thenReturn(VideoCodec.H264)
        Mockito.`when`(commandsManager.MTU).thenReturn(Constants.MTU)
        Mockito.lenient().`when`(commandsManager.writeData(any<MpegTsPacket>(), any<SrtSocket>())).then {
            val packet = it.arguments[0] as MpegTsPacket
            val size = packet.buffer.size
            synchronized(sizes) { sizes.add(size) }
            latch.countDown().let { size }
        }
        val srtSender = SrtSender(connectChecker, commandsManager)
        srtSender.setAudioInfo(44100, true)
        val sps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 103, 100, 0, 30, -84, -76, 15, 2, -115, 53, 2, 2, 2, 7, -117, 23, 8))
        val pps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 104, -18, 13, -117))
        srtSender.setVideoInfo(sps, pps, null)
        srtSender.socket = socket
        srtSender.start()

        val header = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x05)
        val videoData = ByteBuffer.wrap(header.plus(ByteArray(4000) { 0x11 }))
        val videoFrame = MediaFrame(videoData, MediaFrame.Info(0, videoData.remaining(), 0, true), MediaFrame.Type.VIDEO)
        srtSender.sendMediaFrame(videoFrame)
        latch.await(1000, TimeUnit.MILLISECONDS)
        srtSender.stop()

        val snapshot = synchronized(sizes) { sizes.toList() }
        assertTrue("no packets were written", snapshot.isNotEmpty())
        assertTrue(
            "payload over cap: max=${snapshot.max()} cap=$cap",
            snapshot.max() <= cap,
        )
        assertTrue(
            "cap never engaged; enlarge the test frame",
            snapshot.count { it == cap } >= 1,
        )
    }
}
