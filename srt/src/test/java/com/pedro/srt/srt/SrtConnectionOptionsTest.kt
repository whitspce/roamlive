package com.pedro.srt.srt

import com.pedro.common.UrlParser
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import com.pedro.srt.srt.packets.control.handshake.Handshake
import com.pedro.srt.srt.packets.control.handshake.extension.HandshakeExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SrtConnectionOptionsTest {

  private val schemes = arrayOf("srt")
  private val secret = "correct-horse-battery-staple"

  @Test
  fun `query options do not become the stream id`() {
    val parser = UrlParser.parse(
      "srt://example.test:1234/live?passphrase=$secret&pbkeylen=256&latency=2000",
      schemes,
    )

    val options = parseSrtConnectionOptions(parser)

    assertEquals("live", options.streamId)
    assertEquals(secret, options.passphrase)
    assertEquals(EncryptionType.AES256, options.encryptionType)
    assertEquals(2000, options.latency)
    assertFalse(options.streamId.contains("passphrase"))
    assertFalse(options.toString().contains(secret))
  }

  @Test
  fun `explicit streamid is the only query value used as stream id`() {
    val parser = UrlParser.parse(
      "srt://example.test:1234/?streamid=publish-route&passphrase=$secret",
      schemes,
    )

    assertEquals("publish-route", parseSrtConnectionOptions(parser).streamId)
  }

  @Test
  fun `missing passphrase keeps programmatic configuration available`() {
    val options = parseSrtConnectionOptions(
      UrlParser.parse("srt://example.test:1234/live", schemes),
    )

    assertNull(options.passphrase)
    assertEquals(EncryptionType.NONE, options.encryptionType)
  }

  @Test
  fun `empty passphrase explicitly disables URL encryption`() {
    val options = parseSrtConnectionOptions(
      UrlParser.parse("srt://example.test:1234/live?passphrase=", schemes),
    )

    assertEquals("", options.passphrase)
    assertEquals(EncryptionType.NONE, options.encryptionType)
  }

  @Test
  fun `invalid non-empty passphrase is rejected instead of downgraded`() {
    val parser = UrlParser.parse(
      "srt://example.test:1234/live?passphrase=short",
      schemes,
    )

    assertThrows(IllegalArgumentException::class.java) {
      parseSrtConnectionOptions(parser)
    }
  }

  @Test
  fun `latency is milliseconds and invalid values are rejected`() {
    val valid = parseSrtConnectionOptions(
      UrlParser.parse("srt://example.test:1234/live?latency=2000", schemes),
    )
    val invalid = UrlParser.parse(
      "srt://example.test:1234/live?latency=-1",
      schemes,
    )

    assertEquals(2000, valid.latency)
    assertThrows(IllegalArgumentException::class.java) {
      parseSrtConnectionOptions(invalid)
    }
  }

  @Test
  fun `connection reset clears active encryption`() {
    val manager = CommandsManager()
    manager.setPassphrase(secret, EncryptionType.AES256)
    assertTrue(manager.encryptionEnabled())

    manager.reset()

    assertFalse(manager.encryptionEnabled())
    assertEquals(EncryptionType.NONE, manager.getEncryptType())
  }

  @Test
  fun `encrypted URL followed by plain URL does not retain the old key`() {
    val encrypted = parseSrtConnectionOptions(
      UrlParser.parse("srt://example.test/live?passphrase=$secret&pbkeylen=256", schemes),
    )
    val plain = parseSrtConnectionOptions(
      UrlParser.parse("srt://example.test/live", schemes),
    )
    val manager = CommandsManager()

    applyConnectionEncryption(manager, encrypted, "", EncryptionType.NONE)
    assertEquals(EncryptionType.AES256, manager.getEncryptType())

    applyConnectionEncryption(manager, plain, "", EncryptionType.NONE)
    assertFalse(manager.encryptionEnabled())
    assertEquals(EncryptionType.NONE, manager.getEncryptType())
  }

  @Test
  fun `handshake diagnostics redact stream ids`() {
    val handshake = Handshake(
      handshakeExtension = HandshakeExtension(path = "route-$secret"),
    )

    assertFalse(handshake.toString().contains(secret))
    assertTrue(handshake.toString().contains("path=<redacted>"))
  }
}
