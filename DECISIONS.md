# DECISIONS.md — ARCLink HMAC/핸드셰이크 통신 실패 진단

## 증상

`ARCLink`(클라이언트 모드)와 `ARCLinkPlugin`(서버 플러그인) 양쪽 `presharedKeyHex`를 동일하게
맞췄는데도 마크 서버 접속 시 ARCLink 통신이 이루어지지 않는다고 보고됨.

## 조사 방법

CLAUDE.md 3.2(핸드셰이크+인증)를 기준으로 연결 수명주기 전 단계(소켓 연결 → nonce 교환 →
HMAC 검증 → 버전 협상)의 코드를 두 저장소에서 라인 단위로 대조했다.

- `ARClink/src/main/java/com/arclink/protocol/AuthCodec.java`
  ↔ `ARClinkPlugin/src/main/kotlin/.../internal/ArcAuthCodec.kt`
- `ARClink/.../client/ArcLinkConfig.java` ↔ `ARClinkPlugin/.../internal/ArcLinkConfig.kt`
- `ARClink/.../protocol/ArcLinkConnectionManager.java` ↔ `ARClinkPlugin/.../internal/ArcLinkSocketServer.kt`
- `ARClink/.../protocol/ArcLinkMessage.java` ↔ `ARClinkPlugin/.../internal/WireMessage.kt`
- `ARClink/.../protocol/ProtocolVersion.java` ↔ `ARClinkPlugin/.../internal/ProtocolVersion.kt`

## 원인 (결론: 코드 수준 불일치 없음)

대조 결과 **HMAC/핸드셰이크 구현은 두 저장소에서 byte-for-byte 일치**한다. 구체적으로 확인한 것:

1. **키 디코딩**: 양쪽 다 `HexFormat.of().parseHex(presharedKeyHex)`로 동일 디코딩
   (`ArcLinkConfig.toEndpoint()` / `ArcLinkConfig.presharedKeyBytes()`). `HexFormat.parseHex`는
   대소문자를 구분하지 않으므로 대소문자 차이는 원인이 아니다.
2. **서명 대상 바이트**: 클라이언트는 `auth_challenge.nonce`를 Base64 디코딩한 **원문 바이트**에
   서명한다(`AuthCodec.sign`). 서버는 자신이 생성해 보낸 nonce의 원본 `ByteArray`를 재인코딩 없이
   `session.pendingClientNonce`에 그대로 보관해 검증에 쓴다(`ArcAuthCodec.verify`). 양쪽 다 "디코딩된
   바이트"에 서명/검증하며, 한쪽이 hex/Base64 문자열 자체에 서명하는 흔한 실수는 없다. `clientNonce`
   (서버→클라이언트 역방향 증명)도 동일한 패턴.
3. **인코딩**: 서명·nonce·clientNonce 모두 양쪽에서 `Base64.getEncoder()/getDecoder()`(표준, URL-safe
   아님)로 통일. 혼용 없음.
4. **알고리즘**: 양쪽 다 `HmacSHA256` + `SecretKeySpec`, 동일.
5. **비교 로직**: 양쪽 다 `MessageDigest.isEqual`로 상수 시간 비교.
6. **와이어 필드명**: `type/version/id/sessionToken/payload`, `auth_challenge.nonce`,
   `auth_response.{signature,clientNonce,playerName}`,
   `auth_result.{ok,sessionToken,serverProof,expiresAtEpochMs}` — 두 저장소에서 정확히 일치.
7. **버전**: 양쪽 다 `ProtocolVersion(1,0,0)`, `negotiate()` 로직(메이저 다르면 거절/마이너는 min) 동일.

→ 재현 가능한 코드 버그를 찾지 못했다. 대신 실제 저장소에서 "통신이 안 된다"로 보일 수 있는 두 가지
**코드 밖 요인**을 발견:

- **(A) `host` 미설정 시 연결 시도 자체를 안 함**: `ARClink/run/config/arclink.json`
  (로컬 개발용 실행 파일)에 `"host": ""`가 남아 있었다. `ArcLinkClientMod.onInitializeClient()`는
  `config.isConfigured()`(host와 presharedKeyHex 둘 다 비어있지 않음)가 거짓이면 소켓 연결을 아예
  시도하지 않고 `"ARCLink: 'host' not set in config/arclink.json — skipping connection."` 경고만
  남기고 리턴한다. 이 경우 핸드셰이크/HMAC 단계에는 도달조차 안 하므로, 로그에 auth 관련 줄이
  전혀 없다면 이 단계일 가능성이 높다. (단, 이 파일은 이번 세션의 로컬 개발용 스텁일 수 있어
  실제 재현 사례인지는 불명 — 아래 "확인할 것" 참고.)
- **(B) `tls` 옵션이 클라이언트에만 있고 서버(플러그인)에는 미구현**: 클라이언트
  `ArcLinkConfig.tls=true`면 `Endpoint.toUri()`가 `wss://`로 접속을 시도한다. 그런데
  `ArcLinkSocketServer`는 `org.java_websocket.server.WebSocketServer(InetSocketAddress(config.port))`를
  SSL 컨텍스트 없이 그대로 쓰는 평문 WebSocket 서버라 TLS를 전혀 받지 않는다. `tls: true`인
  클라이언트가 이 서버에 접속하면 TCP/TLS 레벨에서 실패하고, 증상은 "통신 안 됨"으로 키 불일치와
  똑같이 보인다. → **플러그인 쪽 조치가 필요한 항목** (아래 참고, 이번 작업 범위 밖).

## 수정

코드 수준 불일치가 없었으므로 `ARCLink`(클라이언트) 쪽에 적용한 동작 변경은 없음. 재현되지 않는
버그를 상정한 코드 변경은 하지 않는다(CLAUDE.md 2 "모호하면 가장 단순한 해석" 원칙).

## 이번 세션의 제약 (중요)

이 작업을 수행한 클라우드 세션의 로컬 셸(`device_bash`)은 아웃바운드 네트워크가 전면 차단되어
있었다 — `services.gradle.org`, `repo1.maven.org` 모두 프록시에서 403으로 거부됨. 그 결과:

- **`./gradlew build`/`test`를 이 세션에서 한 번도 실행하지 못했다** (Gradle 배포판 자체를
  다운로드할 수 없음, 로컬 wrapper 캐시도 이 VM에는 없음).
- 따라서 CLAUDE.md 요구사항인 "동일 키 → 핸드셰이크 성공 / 키·인코딩 불일치 → 실패"를 검증하는
  목 소켓 서버 기반 회귀 테스트를 **작성하지 않았다**. "빌드 통과를 확인할 수 없는 코드는 커밋하지
  않는다"(CLAUDE.md 6)는 규칙을 지키기 위함이다.
- 네트워크가 열린 환경(직접 IDE, 또는 egress가 허용된 세션)에서 아래를 추가할 것:
  `ARClink`에 `org.java-websocket`을 **testImplementation 전용**으로 추가해(서버 측이 이미 쓰는
  라이브러리와 동일) `ArcLinkConnectionManager` 상대의 가짜 서버를 띄우고, (1) 올바른 키로
  핸드셰이크 성공, (2) 다른 키/다른 인코딩으로 서명 시 `signature verification failed`로 실패하는
  것을 검증하는 통합 테스트를 추가. (외부 라이브러리 추가이므로 CLAUDE.md 2에 따라 이 파일에
  선택지/근거/결정을 남겨야 함 — 근거: 서버가 이미 검증된 동일 라이브러리를 쓰므로 목 서버의
  현실성이 가장 높고, 직접 RFC6455를 구현하는 것보다 단순함.)

## 사용자가 확인할 것 (우선순위 순)

1. **서버 콘솔에서 `/arclink status` 실행** — 소켓이 실제로 listening 상태인지, 클라이언트 접속 시
   authenticated count가 올라가는지 확인.
2. **클라이언트 로그(`.minecraft/logs/latest.log`)에서 `ARCLink:`로 시작하는 줄 확인.** 아래 중
   어느 메시지가 찍히는지에 따라 실패 지점이 바로 특정된다:
   - `'host' not set ... skipping connection` → 설정 단계, 소켓 연결 시도조차 안 함 (A)
   - `socket open, awaiting server auth challenge` 이후 진전 없음 → 서버가 `auth_challenge`를
     안 보냄 (플러그인 미기동 / 방화벽 / 포트 불일치 / TLS 불일치(B))
   - `authentication rejected — signature verification failed` → 진짜 키 불일치. 양쪽
     `presharedKeyHex` 문자열을 byte 단위로 재대조(공백·개행·따옴표 혼입 여부, 길이 64자인지)
   - `authentication rejected — incompatible protocol version` / `unparsable protocol version`
     → 버전 협상 실패 (양쪽 모두 `1.0.0`이어야 함)
   - `server failed to prove it holds the shared secret` → 서버→클라이언트 방향 증명 실패
     (키 불일치의 반대 방향 증상)
3. `host`/`port`가 서버가 실제로 바인드한 포트(마크 포트와 별개, 플러그인 설정 `config.port`,
   기본 25566)와 정확히 일치하는지, 클라이언트 `tls`가 `false`인지(서버가 TLS 미지원이므로) 확인.

---

## 추가 확인 결과 (사용자 재현 후 확정)

실제 접속 재현 결과:

- `/arclink status` → `listening on 25566 — 0 client(s) authenticated` (서버는 정상 기동)
- 클라이언트 로그 → `ARCLink: 'host' not set in config/arclink.json — skipping connection.`
- 양쪽 `presharedKeyHex` 값을 직접 대조 → `b32fd53d2f6715d98bc4a43d7446a4f69de8e139d9504da3ee754e100c5dd8e0`,
  64자리, byte 단위로 완전히 동일. **키 불일치가 원인이 아님을 확정.**

**확정된 실패 지점**: 소켓 연결 시도 자체가 발생하지 않음 (위 "원인 (A)"). 핸드셰이크/HMAC
단계에는 도달하지도 못했다.

**확정된 근본 원인**: 클라이언트 `config/arclink.json`의 `"host"` 필드가 빈 문자열로 남아있어
`ArcLinkConfig.isConfigured()`가 거짓을 반환, `ArcLinkClientMod.onInitializeClient()`가
`ArcLinkConnectionManager`를 아예 시작하지 않고 조용히 리턴함.

**해결**: `host`에 서버 실제 주소를 채우고 클라이언트를 완전히 재시작(설정은
`onInitializeClient()`에서 1회만 로드되며 핫리로드되지 않음). 코드 변경 불필요 — 설정값 문제였음.
