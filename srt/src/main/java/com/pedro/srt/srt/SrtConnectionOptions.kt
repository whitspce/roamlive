/*
 * Copyright (C) 2026 Whitespace.
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

package com.pedro.srt.srt

import com.pedro.common.UrlParser
import com.pedro.srt.srt.packets.control.handshake.EncryptionType

internal const val MIN_PASSPHRASE_LENGTH = 10
internal const val MAX_PASSPHRASE_LENGTH = 79
internal const val MAX_LATENCY_MS = 65_535

/** Connection-scoped SRT URL values. Only `streamid` can override the URI path. */
internal data class SrtConnectionOptions(
  val streamId: String,
  val latency: Int?,
  /** Null means use the value configured through SrtClient.setPassphrase. */
  val passphrase: String?,
  val encryptionType: EncryptionType,
) {
  // Passphrases are credentials. Keep accidental diagnostics safe.
  override fun toString(): String {
    return "SrtConnectionOptions(streamId=<redacted>, latency=$latency, passphrase=<redacted>, encryptionType=$encryptionType)"
  }
}

internal fun validateSrtPassphrase(passphrase: String) {
  require(passphrase.isEmpty() || passphrase.length in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) {
    "SRT passphrase must be empty or $MIN_PASSPHRASE_LENGTH to $MAX_PASSPHRASE_LENGTH characters"
  }
}

internal fun parseSrtConnectionOptions(urlParser: UrlParser): SrtConnectionOptions {
  val passphrase = urlParser.getQuery("passphrase")
  if (passphrase != null) validateSrtPassphrase(passphrase)
  val latencyValue = urlParser.getQuery("latency")
  val latency = latencyValue?.toIntOrNull()
  require(latencyValue == null || latency != null && latency in 0..MAX_LATENCY_MS) {
    "SRT latency must be between 0 and $MAX_LATENCY_MS milliseconds"
  }
  val encryptionType = if (passphrase.isNullOrEmpty()) {
    EncryptionType.NONE
  } else {
    when (urlParser.getQuery("pbkeylen")?.toIntOrNull()) {
      192 -> EncryptionType.AES192
      256 -> EncryptionType.AES256
      else -> EncryptionType.AES128
    }
  }
  return SrtConnectionOptions(
    // getFullPath() includes every query parameter in RootEncoder 2.7.x.
    // Only an explicit streamid is allowed to override the URI path.
    streamId = urlParser.getQuery("streamid") ?: urlParser.path,
    latency = latency,
    passphrase = passphrase,
    encryptionType = encryptionType,
  )
}

internal fun applyConnectionEncryption(
  commandsManager: CommandsManager,
  options: SrtConnectionOptions,
  configuredPassphrase: String,
  configuredEncryptionType: EncryptionType,
) {
  val passphrase = options.passphrase ?: configuredPassphrase
  val encryptionType = if (options.passphrase == null) {
    configuredEncryptionType
  } else {
    options.encryptionType
  }
  // Applying NONE matters: it prevents an encrypted destination from
  // contaminating a later connection without a passphrase.
  commandsManager.setPassphrase(passphrase, encryptionType)
}
