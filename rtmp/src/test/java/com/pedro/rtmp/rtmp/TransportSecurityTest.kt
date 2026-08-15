/*
 * Copyright (C) 2024 pedroSG94.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pedro.rtmp.rtmp

import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.common.socket.base.SocketType
import com.pedro.rtmp.FakeRtmpSocket
import com.pedro.rtmp.amf.v0.AmfString
import com.pedro.rtmp.rtmp.message.command.CommandAmf0
import com.pedro.rtmp.utils.socket.TcpSocket
import com.pedro.rtmp.utils.RtmpConfig
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class TransportSecurityTest {

  @Before
  fun setUp() {
    Log.clearMessages()
  }

  @Test
  fun `RTMPS hostname verification is enabled by default`() {
    val client = RtmpClient(mock<ConnectChecker>())

    assertTrue(client.tlsHostVerification)

    // Local development endpoints retain an explicit opt-out. The insecure
    // behavior can no longer happen without a caller selecting it.
    client.tlsHostVerification = false
    assertFalse(client.tlsHostVerification)
  }

  @Test
  fun `default RTMPS policy rejects a trusted certificate for the wrong host`() = runTest {
    val client = RtmpClient(mock<ConnectChecker>())
    val serverIdentity = createServerIdentity("correct-host.invalid")
    val serverContext = createServerContext(serverIdentity)
    val server = serverContext.serverSocketFactory.createServerSocket(
      0,
      1,
      InetAddress.getLoopbackAddress()
    ) as SSLServerSocket
    val executor = Executors.newSingleThreadExecutor()
    val serverHandshake = executor.submit {
      runCatching {
        server.accept().use { (it as SSLSocket).startHandshake() }
      }
    }

    try {
      val socket = TcpSocket(
        SocketType.JAVA,
        "localhost",
        server.localPort,
        true,
        2_000,
        client.tlsHostVerification,
        trustManagerFor(serverIdentity.certificate)
      )

      val failure = runCatching { socket.connect() }.exceptionOrNull()

      assertNotNull(failure)
      assertTrue(failure.hasCause<SSLHandshakeException>())
    } finally {
      server.close()
      serverHandshake.get()
      executor.shutdownNow()
    }
  }

  @Test
  fun `AMF0 logs omit stream key and authentication payload`() = runTest {
    assertCommandLogsAreRedacted(CommandsManagerAmf0())
  }

  @Test
  fun `incoming command logs omit server authentication payload`() = runTest {
    val serverSecret = "server-challenge-8b277fd4"
    val command = CommandAmf0("_error")
    command.addData(AmfString(serverSecret))
    val writer = FakeRtmpSocket()
    command.writeHeader(writer)
    command.writeBody(writer, RtmpConfig.DEFAULT_CHUNK_SIZE)

    val reader = FakeRtmpSocket()
    reader.setInputBytes(writer.output.toByteArray())
    CommandsManagerAmf0().readMessageResponse(reader)

    val logs = Log.getMessages().joinToString("\n")
    assertFalse(logs.contains(serverSecret))
    assertTrue(logs.contains("read message type=COMMAND_AMF0"))
  }

  private suspend fun assertCommandLogsAreRedacted(manager: CommandsManager) {
    val streamKey = "stream-key-6f4f91d9"
    val authUser = "auth-user-4bb0382f"
    val authResponse = "auth-response-93c7b5f1"
    val customSecret = "custom-secret-dd55b478"
    val auth = "?authmod=adobe&user=$authUser&response=$authResponse"
    manager.appName = "live"
    manager.tcUrl = "rtmps://example.invalid/live"
    manager.streamName = streamKey
    manager.customAmfObject = mapOf("token" to customSecret)
    val socket = FakeRtmpSocket()

    manager.sendConnect(auth, socket)
    manager.createStream(socket)
    manager.sendPublish(socket)

    val logs = Log.getMessages().joinToString("\n")
    assertFalse(logs.contains(streamKey))
    assertFalse(logs.contains(authUser))
    assertFalse(logs.contains(authResponse))
    assertFalse(logs.contains(customSecret))
    assertTrue(logs.contains("send connect"))
    assertTrue(logs.contains("send publish"))
  }

  private fun createServerIdentity(hostname: String): ServerIdentity {
    val keyPair = KeyPairGenerator.getInstance("RSA").apply {
      initialize(2048)
    }.generateKeyPair()
    val subject = X500Name("CN=$hostname")
    val now = Instant.now()
    val certificateBuilder = JcaX509v3CertificateBuilder(
      subject,
      BigInteger.valueOf(now.toEpochMilli()),
      Date.from(now.minusSeconds(60)),
      Date.from(now.plusSeconds(3_600)),
      subject,
      keyPair.public
    ).apply {
      addExtension(
        Extension.subjectAlternativeName,
        false,
        GeneralNames(GeneralName(GeneralName.dNSName, hostname))
      )
    }
    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
    val certificate = JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer))
    certificate.verify(keyPair.public)
    return ServerIdentity(keyPair.private, certificate)
  }

  private fun createServerContext(identity: ServerIdentity): SSLContext {
    val password = "unit-test-only".toCharArray()
    val keyStore = KeyStore.getInstance("PKCS12").apply {
      load(null)
      setKeyEntry("server", identity.privateKey, password, arrayOf(identity.certificate))
    }
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
      init(keyStore, password)
    }
    return SSLContext.getInstance("TLS").apply {
      init(keyManagerFactory.keyManagers, null, SecureRandom())
    }
  }

  private fun trustManagerFor(certificate: X509Certificate): TrustManager {
    return object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
      override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain?.firstOrNull() != certificate) {
          throw CertificateException("Unexpected test server certificate")
        }
      }
      override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(certificate)
    }
  }

  private inline fun <reified T : Throwable> Throwable?.hasCause(): Boolean {
    var current = this
    while (current != null) {
      if (current is T) return true
      current = current.cause
    }
    return false
  }

  private data class ServerIdentity(
    val privateKey: java.security.PrivateKey,
    val certificate: X509Certificate
  )
}
