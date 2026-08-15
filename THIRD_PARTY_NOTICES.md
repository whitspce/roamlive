# Third-party notices

Roam is licensed under GPL-3.0-or-later. The software below retains its own license and copyright notices. The complete Apache License 2.0 text is included at [rtmp/LICENSE.txt](rtmp/LICENSE.txt).

## RootEncoder 2.8.0

Roam uses RootEncoder 2.8.0 under Apache-2.0. The application resolves RootEncoder's `library`, `common`, `encoder`, `rtsp`, `udp`, and `whip` artifacts, and substitutes local copies for its `rtmp` and `srt` artifacts.

- Source: https://github.com/pedroSG94/RootEncoder/tree/2.8.0
- Tag commit: `073be1db0b7bd69764f9f30e182b7dae4f32cc38`
- Source archive: https://github.com/pedroSG94/RootEncoder/archive/refs/tags/2.8.0.tar.gz
- Source archive SHA-256: `e2be5b579b37342f926dbebd41d20bb6b056fec27304ebce5bab8ea4cb12efa1`
- Upstream license: Apache-2.0
- Upstream copyright notices are retained in the source files.

The local RTMP copy enables hostname verification by default and removes credentials and authentication material from transport logs. Its changed production files are:

- `rtmp/src/main/java/com/pedro/rtmp/rtmp/CommandsManager.kt`
- `rtmp/src/main/java/com/pedro/rtmp/rtmp/CommandsManagerAmf0.kt`
- `rtmp/src/main/java/com/pedro/rtmp/rtmp/CommandsManagerAmf3.kt`
- `rtmp/src/main/java/com/pedro/rtmp/rtmp/RtmpClient.kt`
- `rtmp/src/main/java/com/pedro/rtmp/utils/socket/TcpTunneledSocket.kt`

The local SRT copy bounds packet parsing and retransmission work, caps MPEG-TS payloads for minimum-MTU paths, validates encryption settings, and keeps credentials out of Stream IDs and logs. Its changed production files are:

- `srt/src/main/java/com/pedro/srt/srt/CommandsManager.kt`
- `srt/src/main/java/com/pedro/srt/srt/SrtClient.kt`
- `srt/src/main/java/com/pedro/srt/srt/SrtConnectionOptions.kt` (new)
- `srt/src/main/java/com/pedro/srt/srt/SrtSender.kt`
- `srt/src/main/java/com/pedro/srt/srt/packets/SrtPacket.kt`
- `srt/src/main/java/com/pedro/srt/srt/packets/control/Nak.kt`
- `srt/src/main/java/com/pedro/srt/srt/packets/control/handshake/extension/HandshakeExtension.kt`
- `srt/src/main/res/drawable/file_search_icon.xml`
- `srt/src/main/res/drawable/sync_icon.xml`

The two vector resources use a 24 dp intrinsic size instead of the upstream 800 dp size. The modified upstream regression-test inputs are `rtmp/src/test/java/android/util/Log.java`, `srt/src/test/java/com/pedro/srt/srt/SrtPacketTest.kt`, and `srt/src/test/java/com/pedro/srt/srt/control/NakTest.kt`. Other local test stubs and regression tests are additions.

The local module build files are Roam integration files rather than copies of upstream build tooling. Modified upstream files carry a dated modification notice as required by Apache-2.0.

## Runtime libraries

Versions below describe the resolved release runtime classpath for this source tree. Gradle's dependency report is authoritative if the version catalog changes.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| AndroidX, Jetpack Compose, CameraX, and Media3, except the DataStore artifact below | Various | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX DataStore external Protocol Buffers runtime | 1.2.1 | BSD-3-Clause | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library | 2.4.10 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| kotlinx.serialization | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.serialization |
| kotlinx-io | 0.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx-io |
| kotlinx.atomicfu | 0.28.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx-atomicfu |
| OkHttp | 5.4.0 | Apache-2.0 | https://github.com/square/okhttp |
| Okio | 3.17.0 | Apache-2.0 | https://github.com/square/okio |
| Ktor | 3.5.1 | Apache-2.0 | https://github.com/ktorio/ktor |
| Bouncy Castle `bcprov-jdk15to18` | 1.85.2 | Bouncy Castle Licence | https://www.bouncycastle.org/licence.html |
| Bouncy Castle `bcutil-jdk15to18` | 1.85.1 | Bouncy Castle Licence | https://www.bouncycastle.org/licence.html |
| Bouncy Castle `bcpkix-jdk15to18` | 1.85 | Bouncy Castle Licence | https://www.bouncycastle.org/licence.html |
| Bouncy Castle `bctls-jdk15to18` | 1.85 | Bouncy Castle Licence | https://www.bouncycastle.org/licence.html |
| SLF4J API | 2.0.18 | MIT | https://www.slf4j.org/license.html |
| Checker Framework qualifiers | 3.43.0 | MIT | https://github.com/typetools/checker-framework |
| JetBrains annotations | 23.0.0 | Apache-2.0 | https://github.com/JetBrains/java-annotations |
| Dagger | 2.59 | Apache-2.0 | https://github.com/google/dagger |
| AutoValue annotations | 1.6.3 | Apache-2.0 | https://github.com/google/auto |
| Error Prone annotations | 2.28.0 | Apache-2.0 | https://github.com/google/error-prone |
| J2ObjC annotations | 3.0.0 | Apache-2.0 | https://github.com/google/j2objc |
| JSR-305 annotations | 3.0.2 | Apache-2.0 | https://github.com/findbugsproject/findbugs |
| Guava | 33.3.1-android | Apache-2.0 | https://github.com/google/guava |
| FailureAccess | 1.0.2 | Apache-2.0 | https://github.com/google/guava |
| ListenableFuture empty compatibility artifact | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | https://github.com/google/guava |
| Jakarta Inject | 2.0.1 | Apache-2.0 | https://github.com/jakartaee/injection |
| javax.inject | 1 | Apache-2.0 | https://github.com/javax-inject/javax-inject |
| JSpecify | 1.0.0 | Apache-2.0 | https://github.com/jspecify/jspecify |

### Protocol Buffers notice

The DataStore external Protocol Buffers runtime contains code derived from [Protocol Buffers](https://github.com/protocolbuffers/protobuf).

Copyright 2008 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
- Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Code generated by the Protocol Buffer compiler is owned by the owner of the input file used when generating it. This code is not standalone and requires a support library to be linked with it. This support library is itself covered by the above license.

### Bouncy Castle notice

Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### SLF4J notice

Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland). All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### Checker Framework qualifiers notice

Copyright 2004-present by the Checker Framework developers.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Test-only libraries

These tools are used by unit tests and are not packaged in release APKs.

| Component | Version | License | Source |
| --- | --- | --- | --- |
| JUnit 4 | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4 |
| Hamcrest Core | 1.3 | BSD-3-Clause | https://github.com/hamcrest/JavaHamcrest |
| Mockito Kotlin | 6.3.0 | MIT | https://github.com/mockito/mockito-kotlin |
| Mockito Core | 5.23.0 | MIT | https://github.com/mockito/mockito |
| Byte Buddy | 1.17.7 | Apache-2.0 | https://github.com/raphw/byte-buddy |
| Objenesis | 3.3 | Apache-2.0 | https://github.com/easymock/objenesis |
| Kotlin reflection | 2.1.20 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines test support | 1.11.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |

## Build tooling

| Component | Version | License | Source |
| --- | --- | --- | --- |
| Gradle Wrapper | 9.7.0 | Apache-2.0 | https://github.com/gradle/gradle/tree/v9.7.0 |

The Gradle wrapper JAR is checked into this repository and includes the Apache-2.0 text at `META-INF/LICENSE`. Android Gradle Plugin 9.3.1, the Android SDK, and Kotlin build plugins 2.4.10 are downloaded build tools and are not packaged in the application.
