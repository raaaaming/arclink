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

---

## 최종 확정

접속 재현 로그:

```
ARCLink auth rejected: no online player named 'Raaaaming'
ARCLink session closed (player=null, code=4001, reason=no online player named 'Raaaaming')
```

이 메시지는 `ArcLinkSocketServer.handleAuthResponse`에서 `ArcAuthCodec.verify(...)`가
**통과한 이후** `findPlayer(playerName)`이 null일 때 나는 거절 사유다 — 즉 HMAC 서명 검증은
이미 성공했다는 뜻. 원인은: 클라이언트 모드가 `ClientLifecycleEvents.CLIENT_STARTED`(타이틀
화면 진입 시점)에 곧바로 ARCLink 소켓에 접속을 시도하는데, 이 시점엔 아직 대상 마인크래프트
서버에 입장하지 않아 Bukkit 쪽에서 해당 플레이어가 온라인으로 안 잡힌다. `ReconnectPolicy`의
지수 백오프로 재시도하다가, 실제로 서버에 입장한 뒤의 재시도에서 `findPlayer`가 성공해
최종적으로 인증이 완료된다(사용자가 직접 재현해 확인).

**최종 결론**: 코드 버그 없음. 키 불일치도 아니었음. 원인은 클라이언트
`config/arclink.json`의 `host`가 비어 있어 소켓 연결 자체를 시도하지 않았던 것뿐. `host`를
채운 뒤에는 핸드셰이크 → HMAC 검증 → (서버 입장 전이라 일시적으로) player-lookup 거절 →
재연결 백오프 → 서버 입장 후 재시도 성공, 순서로 설계대로 정상 동작함을 확인.


# DECISIONS.md — `/shop` MCEF 화면 무반응 진단 (진행 중)

## 지금까지 확정된 것 (서버 쪽)

arcshop/ARCCore/ARClinkPlugin 세 저장소를 진단하며 다음을 서버 로그로 직접 확인함:
- `/shop` 커맨드는 Bukkit `CommandMap`에 정상 등록되고 실제로 `execute()`까지 도달함.
- `Bukkit.getPlayerExact()`가 플레이어를 정상적으로 찾음.
- ARCLink 연결이 감지됨(`arcLink.isConnected(playerId) == true`).
- `ShopWebController.tryOpen()`이 `true`를 반환 — 즉 `arcLink.send(playerId, "open_browser", ...)`
  까지 예외 없이 호출됨.

즉 **서버→소켓 전송까지는 전부 정상**임이 로그로 확정됨(arcshop/ARClinkPlugin DECISIONS.md 참고).

## 클라이언트 로그로 확인한 것

`/shop`을 친 시점(23:19:37 전후) 전후의 `.minecraft/logs/latest.log`를 받아봤는데, 그 구간에
`ARCLink:`로 시작하는 줄이 **단 하나도 없음** — `open_browser` 수신도, MCEF 관련 경고도, 예외도
전혀 없이 그냥 조용함. 5분 뒤 게임을 끌 때 "JCEF is still running, killing to avoid lingering
processes."만 찍힘(이건 MCEF 모드가 애초에 JCEF를 띄워놨다는 뜻이라 MCEF 자체는 로드돼 있었다는
근거는 됨).

**문제**: 이 무로그 상태만으로는 원인을 못 좁힌다 — `OpenBrowserCommand.execute()`와
`ArcLinkConnectionManager.onApplicationMessage()`의 **성공 경로에는 애초에 로그가 하나도 없었다**
(실패 경로에만 `LOGGER.warn`이 있었음). 즉 "로그가 없다"가 "메시지가 도달 안 함"과
"메시지는 도달해서 처리까지 됐는데 화면만 안 뜸" 두 경우를 구분해주지 못하는 상태였다.

## 결정

코드 동작은 바꾸지 않고(관찰 우선 원칙, ARCCore DECISIONS.md #3과 동일한 접근), 성공 경로에도
로그를 추가함:
- `ArcLinkConnectionManager.onApplicationMessage()` — 메시지 수신 시, 그리고
  `registry.dispatch()` 성공/실패 여부를 각각 로그. 또한 `mainThreadExecutor.execute(...)` 람다
  내부를 `try/catch(Throwable)`로 감싸 예외가 조용히 삼켜질 가능성을 차단(발생 시
  `LOGGER.error`로 스택트레이스까지 남김) — 지금까지 이 경로는 예외 안전망이 전혀 없었다.
- `OpenBrowserCommand.execute()` — 메시지 수신 시, URL 조립 후, `client.setScreen(...)` 성공/실패
  각각 로그. `client.setScreen(new ArcLinkBrowserScreen(url))`도 `try/catch(Throwable)`로 감싸서
  `MCEF.createBrowser()`가 던지는 예외가 있다면 클라이언트 채팅 메시지로도 사용자에게 보이게 함
  (이전에는 이 두 실패-분기 케이스에만 채팅 메시지가 있었고, `httpPort<=0` 분기에는 채팅
  메시지조차 없어서 그것도 같이 추가함).

## 다음 단계

[MANUAL] 클라이언트 모드 재빌드 후 `/shop` 재현, `.minecraft/logs/latest.log`의 `ARCLink:` 줄을
다시 확인:
- `'open_browser' command received` 자체가 없으면 → 메시지가 클라이언트에 아예 도달 못 함
  (웹소켓 딜리버리/세션 문제 — 특히 이번 재현 직전에 idle timeout → 재연결이 있었던 것과 관련
  있을 수 있음, 아래 참고)
- 그 줄은 있는데 `setScreen(...) returned without throwing`이 없으면 → 예외 발생, 이번에 추가한
  catch가 스택트레이스를 잡아줄 것
- 둘 다 있는데 화면이 안 뜨면 → MCEF 렌더링 자체 문제(GL 텍스처/타이밍)로 완전히 좁혀짐

참고로 이번 재현 직전 클라이언트 로그에 `ARCLink: disconnected (closed(1000): idle timeout),
scheduling reconnect` → 재연결 → `authenticated` 순서가 있었다. `ArcLinkSocketServer.onClose`는
`sessionsByPlayer.remove(it, session)`(값 비교 포함 remove)를 써서 재연결 후 새 세션이 옛 세션에
의해 잘못 지워지는 레이스는 없어 보이지만(코드 확인함), 재연결 타이밍이 이번 재현과 맞물려 있다는
점은 기록해둔다.


# DECISIONS.md — 실행 자체가 안 됨: `Incompatible mods found!` (mcef 버전 판정 오류)

## 증상

```
Mod 'ARCLink' (arclink) 0.1.0 requires version 2.1.6 or later of mod 'MCEF...' (mcef),
but only the wrong version is present: 2.1.6-1.21.1!
```

Fabric Loader가 모드 해석 단계(게임 코드 실행 전)에서 아예 실행을 거부함 — 지금까지의 `/shop`
런타임 조사와는 무관한, 그보다 더 앞선 단계의 문제.

## 원인

`fabric.mod.json`의 `depends.mcef`가 `">=2.1.6"`였는데, 실제 설치된 MCEF 아티팩트 버전 문자열은
`2.1.6-1.21.1`(마인크래프트 버전을 하이픈 뒤에 붙이는 MCEF 자체 관례)이다. SemVer 명세상 하이픈
뒤는 prerelease 식별자라서, **prerelease 버전은 같은 core 버전의 정식 릴리스보다 우선순위가 낮다**
— 즉 `2.1.6-1.21.1`은 SemVer 순서로 `2.1.6`보다 "작은" 값으로 취급된다. Fabric Loader는 이
규칙을 그대로 따르는 SemVer 구현을 쓰므로 `>=2.1.6`이 `2.1.6-1.21.1`을 거부한 것 — 실제로는
호환되는(오히려 더 구체적인) 버전인데도 문자열 형식 때문에 "더 낮다"고 판정됨. MCEF 쪽의 버전
네이밍 관례와 SemVer 비교 규칙이 충돌하는, 흔히 알려진 종류의 문제.

## 결정

`">=2.1.6"` → `">=2.1.6-0"`로 변경. SemVer에서 `X.Y.Z-0`은 `X.Y.Z`에 대해 가능한 가장 낮은
prerelease로 정의되므로, `>=X.Y.Z-0`은 `X.Y.Z`의 모든 prerelease/빌드 태그 변형(`2.1.6-1.21.1`
포함)과 `X.Y.Z` 정식 릴리스, 그리고 그 이후의 모든 상위 버전을 전부 포함하는 표준적인 트릭이다.
하한선 자체를 없애는 것(`"*"`)보다 안전 — `2.1.6` 미만(예: 아직 필요한 API가 없는 구버전)은 여전히
거부된다.

## 검증

[MANUAL] 클라이언트 재실행해 "Incompatible mods" 크래시 없이 정상 기동하는지 확인. 이 크래시는
게임 코드 실행 전 단계라 이번 세션에서 빌드 없이 정적으로 확인 가능한 부분(JSON 유효성)만
검증했고, 실제 Fabric Loader의 버전 판정 통과 여부는 직접 실행해서 확인해야 한다.


# DECISIONS.md — 상점 화면이 흐릿하게 보임

## 증상 및 배제한 원인

`/shop` MCEF 브라우저 화면 자체는 정상적으로 뜨지만 텍스트/UI가 전체적으로 흐릿함. 사용자가 직접
확인한 결과:
- 마인크래프트 GUI 배율을 "자동"/"수동" 어느 쪽으로 바꿔도 흐림 정도가 동일함
- Windows 디스플레이 배율은 100%(스케일링 없음)
- 스크린샷이 아니라 실제 게임 화면에서도 흐리게 보임(육안 확인)

→ 물리 픽셀 대 GUI 스케일 좌표 불일치(HiDPI 문제)는 배제됨 — 그런 문제였다면 GUI 배율을 바꿀 때
흐림 정도가 달라졌어야 한다.

## 원인

`ArcLinkBrowserScreen.init()`은 브라우저를 프레임버퍼 물리 픽셀 크기(`getFramebufferWidth/Height`)
그대로 생성해왔다 — 이 자체는 맞는 크기다. 문제는 MCEF가 감싸는 CEF의 **오프스크린 렌더링(OSR)**
경로 자체의 특성: CEF가 오프스크린 비트맵으로 렌더링할 때는 서브픽셀(ClearType류) 폰트 안티에일리
어싱을 쓰지 않고 그레이스케일 안티에일리어싱만 적용한다(실제 모니터의 RGB 서브픽셀 배열을 전제로
하는 서브픽셀 AA는 "이 비트맵이 나중에 화면의 정확히 어느 물리적 위치에 그려질지" 모르는 OSR
맥락에서는 애초에 적용할 수 없음). 그 결과 물리 해상도가 완전히 1:1로 맞아도 텍스트가 일반 브라우저
창보다 부드럽게(=흐릿하게) 보인다 — HiDPI 미스매치가 아니라 CEF OSR 자체의 구조적 특성.

## 결정

브라우저를 실제 목적지 크기보다 **2배(SUPERSAMPLE=2) 더 큰 해상도**로 생성하고, 화면에 그릴 때는
기존과 동일한 크기의 사각형에 그대로 블릿(축소 샘플링)한다 — 다운샘플링 자체가 그레이스케일 AA로
그려진 텍스트에 사실상의 안티에일리어싱/선명화 효과를 준다. CEF의 렌더링 파이프라인 자체(서브픽셀
AA 활성화 여부)를 건드릴 방법이 없어 보여서(OSR 모드의 구조적 제약), 이 방식이 코드 변경만으로
가능한 표준적인 우회책이라고 판단했다. 배율 값(2)은 임의로 정함 — 너무 크면 CEF 오프스크린 렌더링
비용(매 프레임 그리는 비트맵 크기)이 커지므로, 가장 흔히 쓰이는 배수부터 시작.

**부수적으로 발견해 같이 고친 것**: 기존 코드는 마우스 좌표(`mouseX`/`mouseY`, 마인크래프트의
GUI-스케일 좌표계)를 그대로 정수 캐스팅해 브라우저에 넘기고 있었는데, 브라우저의 좌표계는
프레임버퍼 물리 픽셀 크기였다 — 즉 GUI 배율이 1이 아닌 사용자는 이미 클릭 위치가 어긋나고 있었을
것이다(이번에 SUPERSAMPLE을 곱하면서 이 어긋남이 더 커지므로 반드시 같이 고쳐야 했다).
`toBrowserX/Y()`로 GUI-스케일 좌표 → 브라우저 픽셀 좌표 변환을 명시적으로 추가했다.

## 검증

[MANUAL] 재빌드 후 `/shop` 재현 — (1) 텍스트가 이전보다 선명해졌는지, (2) 카테고리/구매/판매
버튼 클릭이 정확한 위치에서 인식되는지(특히 GUI 배율이 "자동"이 아닌 고정값일 때) 확인할 것.
여전히 흐리면 SUPERSAMPLE을 3으로 올려보고, 반대로 프레임 드랍 등 성능 문제가 생기면 다시 1로
낮추고 다른 원인(예: 상점 페이지 CSS의 폰트 크기 자체가 원래 작아서 부드러워 보이는 것)을 봐야 함.


## 추가 조사 (2026-09-26) — 슈퍼샘플링만으로는 흐림이 전혀 개선되지 않음

사용자 재확인: 클릭 위치는 정확히 작동하지만(→ `toBrowserX/Y()` 좌표 변환이 맞고, 브라우저가
실제로 SUPERSAMPLE=2배 해상도 좌표계에서 동작하고 있다는 뜻), 텍스트/UI 흐림은 이전과 완전히
동일함 — 위 "결정"에서 예상한 다운샘플링에 의한 선명화 효과가 전혀 나타나지 않았다.

### 원인 (재분석)

슈퍼샘플링이 안티에일리어싱 효과를 내려면, 2배 해상도로 렌더링된 텍스처를 1배 크기 쿼드에
블릿할 때 GPU가 인접 텍셀을 **평균/보간**해야 한다(선형 필터링). 클릭 좌표 매핑이 정확히
작동한다는 것은 CEF가 실제로 2배 해상도로 렌더링하고 있다는 증거이므로, 문제는 렌더링 해상도가
아니라 **텍스처를 화면에 그릴 때 쓰는 필터링 모드**일 가능성이 높다고 판단했다: MCEF가 매 프레임
`glTexSubImage2D`로 텍스처를 갱신하는 방식(밉맵을 매번 재생성하지 않는 것이 일반적)에서는
`GL_TEXTURE_MIN_FILTER`를 `GL_NEAREST`(포인트 샘플링)로 설정해두는 것이 흔한 기본값이다 — 이
경우 다운샘플링 시 GPU가 인접 텍셀을 평균 내지 않고 그냥 하나만 골라 버리므로, 여분의 해상도
데이터가 그려지는 순간 그대로 버려져 시각적으로 "슈퍼샘플링을 켜기 전과 완전히 동일"해진다.
이는 관측된 증상(클릭은 2배 좌표계에서 정확 vs. 화면은 전혀 안 달라짐)과 정확히 들어맞는다.

**검증 한계**: MCEF(`com.cinemamod.mcef`)는 이 저장소가 아닌 컴파일된 외부 의존성이라 실제
텍스처 설정 코드를 직접 읽을 수 없었다. 이번 세션 내내 클라우드 컨테이너와 사용자 PC의
`device_bash` 양쪽 모두 네트워크 아웃바운드가 allowlist 프록시로 막혀 있어(Maven Central,
GitHub raw 콘텐츠 모두 403/404) Maven에서 MCEF jar를 받아 `javap`로 실제 텍스처 파라미터를
확인하는 것도 불가능했다. 아래 결정은 일반적인 OpenGL/CEF OSR 구현 관례에 근거한 추론이며,
MCEF의 실제 소스로 직접 검증된 것은 아니다 — 재현 결과로 맞았는지 틀렸는지 확인해야 한다.

### 결정

`ArcLinkBrowserScreen.render()`에서 `RenderSystem.setShaderTexture(0, browser.getRenderer()
.getTextureID())` 직후, 같은 텍스처에 대해 `GL_TEXTURE_MIN_FILTER`/`GL_TEXTURE_MAG_FILTER`를
`GL_LINEAR`로 명시적으로 강제 설정한다(`org.lwjgl.opengl.GL11.glTexParameteri` 직접 호출).
`setShaderTexture`가 렌더 스레드에서 동기적으로 해당 텍스처를 현재 텍스처 유닛/`GL_TEXTURE_2D`에
바인딩하므로, 바로 다음 줄에서 우리 쪽 코드가 필터 모드를 덮어써도 안전하며 MCEF 내부 코드를
건드릴 필요가 없다. 밉맵 생성(`glGenerateMipmap`)까지는 하지 않았다 — 단순 선형 필터링만으로
충분한지 먼저 확인하고, 그래도 부족하면 다음 단계로 검토.

### 검증

[MANUAL] 재빌드 후 `/shop` 재현 — 이번에는 텍스트 선명도가 조금이라도 달라지는지 확인할 것.
- 그래도 전혀 변화가 없다면: (a) MCEF가 `GL_NEAREST` 강제라는 가설 자체가 틀렸을 가능성(다른
  원인 — 예: CEF 자체의 폰트 렌더링 설정, 또는 상점 웹페이지 CSS 폰트 크기가 원래 작아서 흐려
  보이는 것)을 재검토해야 함.
  - 이 경우 `SUPERSAMPLE` 값을 3~4로 올려 재현해보면 "그레이스케일 AA + 다운샘플링" 가설과
    "필터링 모드" 가설 중 어느 쪽이 맞는지 구분하는 데 도움이 될 수 있음(필터링이 문제였다면
    배율을 올려도 여전히 무변화일 것이고, 필터링을 고친 상태에서 배율을 올리면 갈수록
    선명해져야 함).
- 조금이라도 나아졌다면: 밉맵 생성(`glGenerateMipmap(GL11.GL_TEXTURE_2D)`)까지 추가하는 것을
  다음 단계로 검토.


## 사용자 피드백(2026-09-27) — "봉켓몬 카피" 프로젝트에서도 MCEF UI를 썼는데 이런 문제 없었음

사용자가 이전에 만든 다른 마인크래프트 프로젝트("봉켓몬 카피")에서도 MCEF 기반 UI를 썼는데, 거기서는
별다른 조치 없이도 깨끗하게 렌더링됐다는 근거로 "그레이스케일 AA/필터링" 가설 자체에 의문 제기.

### 재검토 — 이 지적이 실제로 짚어낸 논리적 허점

돌이켜보면 중요한 허점이 있다: **슈퍼샘플링을 적용하기 전, 원래의 1:1(브라우저 해상도 =
목적지 쿼드 크기) 코드에서도 이미 흐렸다**는 게 최초 보고 시점의 상황이었다. 그런데 텍스처
축소 필터링(GL_NEAREST vs GL_LINEAR)은 원본 텍스처 해상도가 목적지보다 **클 때**만 의미가
있다 — 정확히 1:1로 그리는 경우에는 축소 샘플링 자체가 일어나지 않으므로 필터 모드가 화면에
아무 영향을 못 준다. 즉 "그레이스케일 AA로 인한 흐림을 슈퍼샘플링+선형 필터링으로 우회한다"는
가설은애초에 **원래(1:1) 상태에서 이미 흐렸다는 관측과 앞뒤가 안 맞는다** — 1:1일 때 흐렸던
원인을 슈퍼샘플링/필터링이 해결해 줄 이유가 없다.
(단, GPU가 축 정렬이 완벽하지 않은 쿼드나 부동소수점 오차로 인해 1:1이라도 미세하게 0이 아닌
LOD를 계산해 밉맵 샘플링에 들어갈 가능성은 남아있고 — Minecraft 자체가 블록/아이템 텍스처에
전역적으로 밉맵 필터를 쓰는 설정이 있어, 만약 이 텍스처가 마지막으로 바인딩됐던 텍스처 유닛의
샘플러 상태를 그대로 물려받는다면 — 이 경우 지금 추가한 `GL_LINEAR`(비-밉맵) 강제 설정이
여전히 도움이 될 여지는 있다. 하지만 이건 방금 세운 가설이 원래 맞았다기보다 완전히 별개의,
더 지엽적인 시나리오다.)

### 결정 — 다음 단계

1. 이미 적용한 `GL_LINEAR` 강제 설정은 그대로 두고 재현 테스트는 계속 진행 — 비용이 낮고
   (코드 몇 줄), 위에서 언급한 지엽적 밉맵 시나리오라면 여전히 도움이 될 수 있음.
2. 그와 별개로, "봉켓몬 카피"의 MCEF 브라우저 생성/렌더링 코드를 확보해 이 프로젝트의
   `ArcLinkBrowserScreen`과 직접 대조하는 것이 훨씬 더 확실한 다음 수임 — 이번 세션에서는
   해당 저장소가 연결돼 있지 않아 직접 비교 불가. 사용자에게 해당 코드(브라우저 생성 시 해상도
   계산 방식, `MCEF.createBrowser` 호출부, 텍스처 블릿 코드)를 요청함.
3. 텍스트 흐림의 다른 가능한 원인 후보(아직 미검증, 앞으로 비교 자료로 좁혀야 함):
   - 브라우저 생성 시점에 `getFramebufferWidth/Height`가 실제 창 크기보다 작은/잘못된 값을
     반환하고 있어(예: 초기화 타이밍 문제) 저해상도로 렌더링된 걸 확대해서 보여주고 있을 가능성
   - 상점 웹페이지 자체의 CSS(폰트 크기, `-webkit-font-smoothing`, viewport 메타 태그 유무)
   - Minecraft 자체의 비디오 설정(밉맵 레벨, 셰이더팩/리소스팩 유무)이 이 텍스처에도 영향을
     주고 있을 가능성


## 실제 대조 완료(2026-09-27) — "봉켓몬 카피"의 McefScreen 코드 확보, `ArcLinkBrowserScreen` 재작성

사용자가 "봉켓몬 카피" 프로젝트 폴더를 연결해줘서 `bongketmon-ui` 모듈의 실제 MCEF 화면 클래스
(`kr.bongketmon.ui.screen.McefScreen`)와 상점 등 실제 웹 UI(zip으로 받은 `shop.html` 등)를
직접 읽고 `ArcLinkBrowserScreen`과 한 줄씩 대조했다.

### 대조 결과

같은 점(원인이 아님):
- MCEF 버전 동일(`2.1.6-1.21.1`) — 다만 ARClink는 `com.cinemamod` 메이븐, 봉켓몬은
  `maven.modrinth` 메이븐에서 받음(같은 릴리스의 미러일 가능성이 높지만 100% 동일 아티팩트인지는
  확인 못 함 — 낮은 우선순위 후보로 남겨둠)
- 둘 다 브라우저를 `getFramebufferWidth/Height()`(물리 픽셀) 그대로 생성 — GUI 스케일 단위로
  만들지 않음. 봉켓몬 쪽 주석도 "GUI 스케일 단위로 만들면 글자가 뭉개진다"고 명시적으로 경고하고
  있어 이 부분은 ARClink도 이미 맞게 하고 있었음.
- 폰트: 봉켓몬의 `tokens.css`는 `'Pretendard', ...,'Segoe UI', 'Malgun Gothic', ..., sans-serif`
  순서로 폴백하는데, 실제로 Pretendard 폰트 파일은 모드에 번들돼 있지 않음(주석은 "번들하면 그걸
  쓴다"고 돼 있을 뿐) — 즉 봉켓몬도 결국 OS 시스템 폰트(Segoe UI/Malgun Gothic)에 의존하고
  있다는 점은 ARClink(`ShopWebAssets.kt`: `"Segoe UI", "Malgun Gothic", sans-serif"`)와 동일.
  폰트 번들 여부는 원인이 아님.

다른 점(`ArcLinkBrowserScreen`과 `McefScreen`의 `createBrowser`/렌더 코드 사이 유일한 실제 차이):
- `MCEF.createBrowser(url, transparent, w, h)`의 두 번째 인자 — 봉켓몬은 `true`(투명), ARClink는
  `false`(불투명)였음.
- 셰이더/버텍스 포맷 — 봉켓몬은 `getPositionTexProgram`(POSITION_TEXTURE, 색은
  `setShaderColor` 유니폼으로) 사용, ARClink는 `getPositionTexColorProgram`
  (POSITION_TEXTURE_COLOR, 정점마다 색 하드코딩) 사용. 수학적으로는 동일해야 하는 차이지만
  격리 실험이므로 남겨두지 않음.

### 결정

이전 두 차례(슈퍼샘플링, GL_LINEAR 강제)는 모두 근거가 약한 상태에서 진행한 추측이었고, 이번엔
**실제로 검증된(사용자가 흐리지 않다고 확인한) 참고 구현과 코드 자체를 대조**할 수 있었으므로
훨씬 신뢰할 수 있는 실험이 가능해졌다. `ArcLinkBrowserScreen`을 슈퍼샘플링/GL_LINEAR 강제를
모두 제거하고 `McefScreen`과 **동일하게**(1배 해상도, `transparent=true`, `getPositionTexProgram`
+`setShaderColor`) 재작성했다 — 마우스 좌표 변환(`toBrowserX/Y`)만 GUI 스케일 대응을 위해
유지(1배 해상도에서는 항등 변환이라 무해함).

이건 "한 가지를 더 추측해서 고친다"가 아니라 **격리 실험**이다: 이번에도 여전히 흐리면 Java
렌더링 코드(불투명/셰이더 차이)는 원인이 아니라는 뜻이 되어 콘텐츠 쪽(웹페이지 CSS/폰트/크기)을
다음으로 봐야 하고, 이번에 선명해지면 `transparent` 플래그 또는 셰이더/버텍스 포맷 차이 중
하나가 실제 원인이었다는 뜻이 되어 그 둘을 하나씩 되돌려 정확히 어느 쪽인지 좁힐 수 있다.

### 검증

[MANUAL] 재빌드 후 `/shop` 재현 — 이번에는 정말 선명해지는지 확인. 결과에 따라:
- 선명해짐 → `transparent=true`와 셰이더 변경 중 어느 게 실제 원인인지 하나씩 되돌려 좁히기
- 여전히 흐림 → Java 렌더링 경로는 원인이 아니라는 강한 증거. 다음은 arcshop의 실제
  `shop.html`/CSS(폰트 크기 11~14px대로 작은지, 실제 브라우저에서 봤을 때와 비교) 자체를
  봉켓몬 쪽 웹뷰(`bongketmon-ui`의 `McefScreen`)로 대신 열어보는 교차 테스트를 제안 —
  콘텐츠가 문제인지 완전히 격리 가능.


## 사용자 확인(2026-09-27) — McefScreen과 완전히 동일하게 맞춘 뒤에도 여전히 흐림

`ArcLinkBrowserScreen`을 검증된 참고 구현(`McefScreen`)과 1배 해상도·transparent=true·셰이더까지
완전히 동일하게 재작성했는데도 흐림이 그대로였다. 이건 **Java 렌더링 경로(브라우저 생성 방식,
셰이더/버텍스 포맷, 텍스처 블릿 코드)는 원인이 아니라는 걸 사실상 확정**한다 — 남은 후보는
(a) arcshop이 실제로 서빙하는 상점 페이지 콘텐츠(`ShopWebAssets.kt`의 HTML/CSS/JS) 자체이거나
(b) 콘텐츠와 무관한 다른 환경 요인(예: `com.cinemamod` 메이븐 vs `maven.modrinth` 메이븐에서 받은
MCEF 아티팩트가 미묘하게 다름, ARClink 모드 자체의 다른 초기화 코드가 뭔가 영향을 줌 등)이다.
ARClink 클라이언트 쪽 소스를 전부 훑어봤을 때 `ArcLinkBrowserScreen` 외에 GL 상태를 건드리는
믹스인/렌더링 코드가 전혀 없다는 것도 확인함(다른 코드가 텍스처 필터/셰이더 상태를 오염시키고
있을 가능성은 낮음).

### 결정 — 콘텐츠 vs 그 외 요인 격리 테스트

arcshop이 만든 서명 URL 대신, `OpenBrowserCommand.execute()`에서 **일회성으로** URL을
`https://example.com/`(완전히 외부의, 콘텐츠상 arcshop과 아무 관련 없는 사이트)로 강제
오버라이드하도록 임시 코드를 추가했다(`TEMP DEBUG` 주석으로 명확히 표시, 확인 후 반드시 제거).

- `/shop`을 쳤을 때 이 외부 사이트가 **선명하게** 뜨면 → arcshop이 서빙하는 그 페이지의 실제
  HTML/CSS(폰트 크기, 스타일 등)가 원인이라는 뜻이 되어, 다음은 `ShopWebAssets.kt`의 실제 콘텐츠를
  자세히 다시 보거나 봉켓몬의 실제 웹 UI를 그대로 서빙해서 대조하는 방향으로 좁힐 수 있다.
- 이 외부 사이트마저 **흐리게** 뜨면 → 콘텐츠는 원인이 아니고, MCEF 아티팩트 출처(메이븐 저장소
  차이)나 이 PC의 CEF 런타임 자체의 문제(예: GPU 가속 비활성화, 소프트웨어 렌더링 폴백)처럼 훨씬
  더 근본적인 걸 봐야 한다 — 이 경우 봉켓몬 모드를 이 클라이언트에 실제로 같이 설치해서 그
  McefScreen이 실제로 이 PC에서 선명하게 뜨는지까지 재확인하는 게 다음 단계.

### 검증

[MANUAL] 재빌드 후 `/shop` 실행 — 이번엔 상점이 아니라 example.com이 뜬다(의도된 것). 그 페이지
텍스트가 선명한지 흐린지만 확인해서 알려줄 것. 확인 끝나면 이 임시 오버라이드를 반드시 제거하고
원래 서명 URL 로직으로 되돌릴 것 — 절대 이 상태로 커밋/배포하지 않는다.

## 사용자 확인(2026-09-27) — example.com마저 흐림: 콘텐츠도, Java 렌더링 경로도 원인이 아님

`https://example.com/`(arcshop과 완전히 무관한 외부 사이트, 아주 단순한 정적 HTML)을 강제로
열었는데도 흐렸다. 이걸로 두 가지가 함께 확정된다:
- Java 렌더링 경로(브라우저 생성, 셰이더, 텍스처 블릿) — 이전 실험에서 이미 배제
- arcshop이 서빙하는 페이지의 콘텐츠(HTML/CSS/폰트) — 이번 실험으로 배제

즉 **MCEF가 렌더링하는 건 뭐가 됐든 이 클라이언트에서 흐리게 나온다**는 뜻이다. 임시 오버라이드는
확인 후 즉시 원복함(`OpenBrowserCommand`는 다시 정상적으로 서명 URL을 연다).

### 재검토 — 지금까지 간과한 후보: 화면 전체에 걸리는 후처리

지금까지는 계속 "MCEF/CEF가 텍스처를 만드는 방식" 쪽만 팠는데, 원인이 콘텐츠에도 Java
렌더링 코드에도 없다면 남는 건 **이 텍스처를 그린 뒤에(또는 화면 전체에) 추가로 걸리는 무언가**
일 가능성이 높다. 이런 건 콘텐츠나 우리 렌더링 코드와 무관하게 화면에 나타나는 모든 것을
똑같이 흐리게 만들 수 있고, GUI 배율/윈도우 배율과도 무관하다(사용자가 이미 배제한 두 변수와도
모순되지 않음):

- **샤더팩(Iris/Oculus) 또는 OptiFine의 안티에일리어싱/블룸/DOF 등 후처리 효과** — 켜져 있으면
  게임 화면 전체(브라우저 오버레이 포함)에 블러 계열 효과가 걸릴 수 있음. 인게임에서 키 하나로
  켜고 끌 수 있어 **재빌드 없이** 바로 테스트 가능한, 지금까지 중 가장 저렴한 실험.
- **GPU 드라이버 레벨 이미지 샤프닝/스케일링 필터** — 예: NVIDIA "이미지 스케일링"/앙비엔트
  필터, GeForce Experience의 "Ansel"/필터, AMD의 RSR/이미지 샤프닝. 게임 창 전체에 적용되며
  마인크래프트의 GUI 배율 설정과는 완전히 무관함.
- **Windows의 앱별 배율 재정의** — `.exe` 우클릭 → 속성 → 호환성 → "고해상도 디스플레이 배율
  재정의" 옵션이 켜져 있으면 Windows가 앱 전체를 흐릿하게 확대해서 그림(이건 마인크래프트 자체의
  GUI Scale 설정과는 별개의, OS 레벨 설정이라 사용자가 지금까지 확인한 것과 모순되지 않음).

### 결정

코드를 더 건드리기 전에, 사용자에게 위 세 가지(특히 샤더팩)를 직접 확인해달라고 요청 — 셋 다
재빌드 불필요, 게임 설정/OS 설정만 확인하면 되는 가장 저렴한 다음 스텝. 코드 레벨에서 더 팔
곳이 사실상 안 보이는 상태라(콘텐츠·Java 렌더링 다 배제됨), 이 결과를 기다린 뒤에 다음 코드
변경 방향을 정하는 게 낫다고 판단.

### 검증

[MANUAL] 사용자가 직접 확인:
1. 마인크래프트에 Iris/Oculus/OptiFine 샤더팩이 켜져 있는지 — 켜져 있다면 끄고 `/shop`(또는
   방금 했던 example.com 임시 테스트)을 다시 확인
2. NVIDIA/AMD 그래픽 드라이버 제어판에 이미지 샤프닝/스케일링/필터 종류가 켜져 있는지
3. 마인크래프트 실행 파일(.exe 또는 launcher가 실행하는 java.exe)에 Windows
   "고해상도 디스플레이 배율 재정의" 호환성 옵션이 켜져 있는지

## 근본 원인 확정(2026-09-27, 바이트코드 검증) — `super.render()`가 바닐라 메뉴 배경 블러를 덮어씌움

코드 수정 없이 리서치만 진행(사용자 요청). 결론: **흐림의 원인은 MCEF/CEF가 아니라
`ArcLinkBrowserScreen.render()` 마지막 줄의 `super.render(context, mouseX, mouseY, delta)`**.

MC 1.21.1 바이트코드(yarn 매핑 jar, javap)로 확인한 호출 체인:
- `Screen.render()` → 가장 먼저 `renderBackground()` 호출
- `renderBackground()` → (월드 안이면) `applyBlur(delta)` → `renderDarkening()`
- `applyBlur()` → `GameRenderer.renderBlur()` → 반경 = 옵션 `menuBackgroundBlurriness`
  (기본값 **5**, 접근성 설정의 "메뉴 배경 흐림")인 블러 포스트이펙트를 **메인 프레임버퍼 전체**에 적용

우리 화면은 브라우저 텍스처를 먼저 그린 뒤 `super.render()`를 부르므로, 이미 그려진 브라우저
이미지 위에 가우시안 블러 + 어둡게 하기 오버레이가 그대로 덮인다. 봉켓몬 `McefScreen.render()`는
`super.render()`를 호출하지 않아 선명했던 것 — 앞서 "완전히 동일하게 맞췄다"던 재작성에서 이 한 줄을
남겨둔 게 대조 실수였다. 이것으로 지금까지의 모든 관측(GUI 배율·윈도우 배율 무관, 슈퍼샘플링/GL_LINEAR
무효과, example.com도 흐림, 봉켓몬은 선명)이 전부 설명된다.

함께 배제·확인된 것:
- MCEF 텍스처 필터는 이미 `GL_LINEAR`(MCEFRenderer.initialize 바이트코드 확인) → 이전 "GL_NEAREST" 가설은 틀림
- CEF 스케일 팩터는 1.0 고정, view rect = 요청 크기 그대로(CefBrowserOsr 확인) → CEF 내부 축소 없음
- cinemamod 메이븐 vs modrinth 메이븐 MCEF: 클래스 186개 바이트 단위 완전 동일

부차 요인(주원인 제거 후에도 남을 수 있는 미세한 무름):
- MCEF가 전역 `CefSettings.background_color`를 알파 0으로 설정 → 오프스크린에서 투명 페인팅 →
  Chromium은 불투명 레이어에서만 LCD(ClearType) 텍스트를 쓰므로 그레이스케일 AA가 됨
- `Screen.width/height`는 `ceil(fb/scale)`인데 GUI 직교투영은 `fb/scale`(실수) → 창모드에서 프레임버퍼가
  배율로 나눠떨어지지 않으면 쿼드가 최대 (scale-1)px 늘어나 GL_LINEAR 리샘플링으로 약간 무름

[MANUAL] 코드 수정 없이 확인: 옵션 → 접근성 설정 → "메뉴 배경 흐림"을 0으로 → `/shop`이 선명해지면 확정.
수정 방향(미적용): `super.render()` 호출 제거 또는 `renderBackground()`를 빈 메서드로 오버라이드하고,
효과 없던 슈퍼샘플링/GL_LINEAR 관련 변경은 되돌린다.

## 수정 적용(2026-09-27) — `renderBackground()` 빈 오버라이드

선택지: (A) `render()` 끝의 `super.render()` 호출 제거(봉켓몬 McefScreen 방식) / (B) `renderBackground()`를
빈 메서드로 오버라이드.
근거: 둘 다 블러(`applyBlur`)와 어둡게 하기(`renderDarkening`, `inworld_menu_background.png`)를 함께
없앤다. 사용자 스크린샷 측정상 블러 0에서도 배경이 원래 밝기의 약 75%(RGB 178)였던 게 후자 때문.
(B)는 나중에 이 화면에 바닐라 위젯을 붙여 `super.render()`가 다시 필요해져도 배경이 되살아나지 않는다.
결정: (B) 적용(사용자 선택). 1.21.1에서 `renderBackground()`는 `Screen.render()`에서만 호출됨을
바이트코드로 확인. 실제 MC 1.21.1(yarn) + MCEF jar로 javac 컴파일 통과(brigadier `Message`만 스텁).
