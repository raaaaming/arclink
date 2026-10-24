# TODO — ARCLink HMAC/핸드셰이크 진단 후속

- [MANUAL: 해결 확인됨 — host 설정 후 정상 인증 성공 (DECISIONS.md "최종 확정" 참고). 남은 건 사용자가 서버 콘솔에서 `/arclink status`로 "1 client(s) authenticated" 확인하는 것뿐.]
  "사용자가 확인할 것"에 적힌 순서(`/arclink status` → 클라이언트 `ARCLink:` 로그 줄 판독)로
  정확히 어느 단계에서 끊기는지 특정할 것.]
- [BLOCKED: 이 세션의 device_bash 셸은 아웃바운드 네트워크가 전면 차단되어(services.gradle.org,
  repo1.maven.org 모두 프록시 403) `./gradlew build`/`test`를 한 번도 실행하지 못했다. 목 소켓
  서버 기반 핸드셰이크 회귀 테스트(동일 키 성공 / 키·인코딩 불일치 실패)는 네트워크가 열린
  환경에서 추가해야 한다. 상세는 `DECISIONS.md` "이번 세션의 제약" 참고.]

## MCEF 브라우저 UI (신규)

- [MANUAL: 이 세션은 네트워크가 없어 `./gradlew build`를 못 돌려봤다 — 먼저 IntelliJ에서
  빌드 통과를 확인해야 함 (빌드 전까지는 커밋 안 함, 아래 파일들은 디스크에만 작성됨).
  빌드 통과 후 실제 마크 클라이언트로 다음을 직접 확인할 것:
  1. 서버(플러그인)가 `open_browser` 타입 신호를 `{"payload":{"path":"<ArcLinkService.serveFile()
     반환값>"}}`로 보냈을 때 `ArcLinkBrowserScreen`이 뜨고 페이지가 실제로 로드되는지.
  2. 마우스 클릭/드래그/휠, 키보드 입력이 브라우저 안 요소(예: 텍스트 입력창)에 정상 전달되는지.
  3. 창 크기 조절 시 브라우저 해상도가 같이 갱신되는지, Esc로 닫힐 때 `MCEFBrowser.close()`가
     리소스 누수 없이 정리되는지.
  4. MCEF가 아직 초기화 전(CEF 런타임 다운로드 중 등)에 신호가 오면 크래시 없이
     "MCEF is not ready yet" 채팅 메시지만 뜨고 넘어가는지.
  변경 파일: `build.gradle`(MCEF 저장소/의존성), `fabric.mod.json`(mcef depends 추가),
  `ArcLinkConnectionManager.java`(auth_result의 optional `httpPort` 파싱),
  `ArcLinkBrowserScreen.java`(신규), `OpenBrowserCommand.java`(신규),
  `ArcLinkClientMod.java`(`open_browser` 핸들러 등록).]


## `/shop` MCEF 화면 무반응 진단 — 클라이언트 쪽 로그 계측 추가 (진행 중)

- [x] `ArcLinkConnectionManager.onApplicationMessage()`에 수신/dispatch 성공·실패 로그 추가,
      `mainThreadExecutor` 람다를 `try/catch(Throwable)`로 감싸 예외 안전망 추가
- [x] `OpenBrowserCommand.execute()`에 수신/URL조립/setScreen 성공·실패 로그 추가,
      `httpPort<=0` 분기에도 채팅 메시지 추가, `client.setScreen(...)`을 `try/catch(Throwable)`로
      감싸 MCEF 예외를 사용자 채팅에도 노출
- [MANUAL: 재빌드 후 서버 쪽 arcshop `/shop` 재현 + 클라이언트 `.minecraft/logs/latest.log`의
  `ARCLink:` 줄을 다시 확인 — DECISIONS.md "다음 단계" 참고. 로그를 붙여주면 다음 진단을 이어감.]


## 실행 자체가 안 됨 — `mcef` 버전 판정 오류로 "Incompatible mods found!" (수정)

- [x] `fabric.mod.json`의 `depends.mcef`를 `">=2.1.6"` → `">=2.1.6-0"`로 수정 — 설치된 MCEF의
      `2.1.6-1.21.1` 버전 문자열이 SemVer상 prerelease로 판정돼 `>=2.1.6`을 통과하지 못하던 문제
      (DECISIONS.md 참고)
- [MANUAL: 클라이언트 재실행해 "Incompatible mods" 없이 정상 기동하는지 확인. 기동되면 그 다음
  이전 항목("`/shop` MCEF 화면 무반응 진단")의 `/shop` 재현 테스트를 이어서 진행할 것.]


## 상점 화면이 흐릿하게 보임 — CEF OSR 그레이스케일 AA를 슈퍼샘플링으로 우회 (수정)

- [x] `ArcLinkBrowserScreen`이 브라우저를 목적지 크기의 2배(SUPERSAMPLE=2)로 생성하고 축소
      블릿하도록 수정 — HiDPI/GUI배율 문제는 사용자가 직접 배제 확인함, CEF OSR의 그레이스케일
      AA 특성이 원인 (DECISIONS.md 참고)
- [x] (부수 수정) 마우스 좌표를 GUI-스케일 좌표에서 브라우저 픽셀 좌표로 변환하는
      `toBrowserX/Y()` 추가 — 기존엔 GUI 배율이 1이 아니면 클릭 위치가 어긋났을 것, 이번 수정으로
      어긋남이 더 커지기 전에 같이 고침
- [MANUAL: 재빌드 후 `/shop` 재현 — 텍스트가 선명해졌는지, 버튼 클릭이 정확한 위치에서 되는지
  (특히 GUI 배율 고정값일 때) 확인할 것. 여전히 흐리면 SUPERSAMPLE을 올려볼 것.]


## 상점 화면 흐림 — 슈퍼샘플링만으론 무변화, 텍스처 필터링(GL_LINEAR) 강제 추가 (수정, 미검증)

- [x] 사용자 확인: 클릭 위치는 정확(→ 브라우저가 실제 2배 해상도로 동작 중이라는 증거), 하지만
      텍스트 흐림은 슈퍼샘플링 적용 전과 완전히 동일 — 다운샘플링이 실제로 안티에일리어싱
      효과를 내려면 텍스처 축소 필터링이 GL_LINEAR(보간)여야 하는데, MCEF가 매 프레임 텍스처를
      갱신하는 방식상 GL_NEAREST(포인트 샘플링)가 기본값일 가능성이 높다고 재분석 (DECISIONS.md
      "추가 조사" 참고)
- [x] `ArcLinkBrowserScreen.render()`에서 `setShaderTexture` 직후 `GL11.glTexParameteri`로
      `GL_TEXTURE_MIN_FILTER`/`GL_TEXTURE_MAG_FILTER`를 `GL_LINEAR`로 강제 설정
- [BLOCKED: MCEF 실제 소스/바이트코드를 확인할 방법이 이번 세션 내내 없었음(클라우드 컨테이너·
  사용자 PC 양쪽 모두 네트워크 아웃바운드가 allowlist 프록시로 차단, Maven Central/GitHub raw
  모두 403/404) — 이 수정은 일반적인 OpenGL/CEF 관례에 근거한 추론이며 MCEF 내부 코드로 직접
  검증되지 않음. 재현 테스트로만 맞는지 확인 가능.]
- [MANUAL: 재빌드 후 `/shop` 재현 — 텍스트 선명도가 조금이라도 달라지는지 확인할 것.
  - 그래도 무변화면: SUPERSAMPLE을 3~4로 올려서 재테스트 — 여전히 무변화면 "필터링" 가설 자체가
    틀렸을 가능성이 높으니 다른 원인(CEF 폰트 렌더링 설정, 상점 페이지 CSS 폰트 크기)을 검토.
  - 조금이라도 나아졌다면: `glGenerateMipmap`까지 추가하는 걸 다음 단계로 검토.]

- [BLOCKED: 사용자가 이전에 만든 "봉켓몬 카피" 프로젝트도 MCEF UI를 썼는데 이런 흐림 문제가
  없었다고 함 — 원래(슈퍼샘플링 적용 전) 1:1 렌더링에서도 이미 흐렸다는 관측과 맞물려 보면
  "그레이스케일 AA + 필터링" 가설 자체가 근본 원인이 아닐 가능성이 있음. "봉켓몬 카피"의 브라우저
  생성/렌더링 코드를 받아 직접 대조해야 확실해짐 — 이번 세션엔 해당 저장소가 연결 안 돼 있어
  직접 비교 불가 (DECISIONS.md 참고).]

- [x] 사용자가 "봉켓몬 카피" 프로젝트 폴더를 연결해줘서 실제 `McefScreen.java`와 웹 UI(zip)를
      확보, `ArcLinkBrowserScreen`과 직접 대조 완료. 실제 유일한 차이는 `MCEF.createBrowser`의
      `transparent` 인자(false vs true)와 셰이더/버텍스 포맷(`getPositionTexColorProgram` vs
      `getPositionTexProgram`) 뿐이었음 — 폰트/해상도 계산 방식은 이미 동일했음 (DECISIONS.md
      "실제 대조 완료" 참고)
- [x] `ArcLinkBrowserScreen`을 슈퍼샘플링/GL_LINEAR 강제를 모두 제거하고 `McefScreen`과 완전히
      동일하게(1배 해상도, transparent=true, getPositionTexProgram+setShaderColor) 재작성 —
      격리 실험: 이번에도 흐리면 Java 코드는 원인이 아니라는 뜻, 선명해지면 두 차이 중 하나가
      원인이라는 뜻
- [MANUAL: 재빌드 후 `/shop` 재현. 결과와 무관하게 다음 스텝이 명확해짐 —
  - 선명해짐: transparent 플래그와 셰이더 변경 중 어느 쪽이 실제 원인인지 하나씩 되돌려 좁히기
  - 여전히 흐림: Java 렌더링 경로는 원인이 아님 확정. arcshop의 `shop.html`/CSS 자체를 봉켓몬의
    McefScreen으로 대신 열어보는 교차 테스트로 콘텐츠 쪽 원인 격리 제안]

- [x] 사용자 확인: McefScreen과 완전히 동일하게 맞춘 뒤에도 여전히 흐림 — Java 렌더링 경로는
      원인에서 제외됨 확정. 남은 후보는 arcshop 콘텐츠(HTML/CSS) 또는 그 외 환경 요인
      (DECISIONS.md "사용자 확인(2026-09-27)" 참고)
- [x] (임시, TEMP DEBUG) `OpenBrowserCommand.execute()`에서 서명 URL 대신 `https://example.com/`을
      강제로 열도록 1회성 오버라이드 추가 — 콘텐츠 vs 그 외 요인 격리용. **확인 후 반드시 제거할 것.**
- [MANUAL: 재빌드 후 `/shop` 실행 → example.com이 뜸(의도됨) → 그 텍스트가 선명한지 흐린지만
  확인해서 알려줄 것.
  - 선명함: arcshop 콘텐츠(ShopWebAssets.kt의 HTML/CSS)가 원인 → 다음은 그 콘텐츠 자체를 재검토
  - 흐림: 콘텐츠도 원인 아님 → MCEF 아티팩트 출처(cinemamod vs modrinth 메이븐) 또는 이 PC의
    CEF 런타임 자체(GPU 가속/소프트웨어 렌더링) 문제일 가능성 → 봉켓몬 모드를 실제로 같이 설치해
    이 PC에서 McefScreen이 진짜 선명하게 뜨는지 재확인 필요
  확인 끝나면 이 오버라이드는 반드시 제거하고 원래 서명 URL 로직으로 되돌릴 것.]

## example.com마저 흐림 — 콘텐츠·Java 렌더링 경로 모두 배제, 화면 전체 후처리 후보로 전환

- [x] 임시 오버라이드 원복 완료(`OpenBrowserCommand`는 다시 정상 서명 URL을 엶)
- [x] 사용자 확인: example.com도 흐림 → arcshop 콘텐츠(HTML/CSS/폰트)도 원인에서 배제 확정.
      MCEF가 렌더링하는 건 뭐든 이 클라이언트에서 흐리게 나온다는 뜻 (DECISIONS.md 참고)
- [MANUAL: 재빌드 불필요, 게임/OS 설정만 확인하면 되는 저비용 테스트 3가지 —
  1. Iris/Oculus/OptiFine 샤더팩이 켜져 있으면 끄고 재확인 (가장 유력, 키 하나로 토글 가능)
  2. NVIDIA/AMD 드라이버의 이미지 샤프닝/스케일링 필터가 켜져 있는지
  3. 마인크래프트를 실행하는 exe/java.exe에 Windows "고해상도 디스플레이 배율 재정의" 호환성
     옵션이 켜져 있는지
  셋 중 하나라도 켜져 있어서 끄고 나서 선명해지면 원인 확정, 코드 수정 불필요할 수도 있음.]

## 흐림 근본 원인 확정 — super.render()의 바닐라 메뉴 배경 블러 (코드 미수정)

- [x] MC 1.21.1 바이트코드로 확인: `Screen.render()` → `renderBackground()` → `applyBlur()` →
      `GameRenderer.renderBlur()`(반경=메뉴 배경 흐림 옵션, 기본 5)가 이미 그려진 브라우저 위에 블러를 덮음.
      봉켓몬 McefScreen은 super.render()를 안 불러서 선명 (DECISIONS.md "근본 원인 확정")
- [MANUAL: 접근성 설정 → 메뉴 배경 흐림 0으로 바꿔 `/shop` 확인]
- [x] `ArcLinkBrowserScreen.renderBackground()`를 빈 메서드로 오버라이드(사용자 선택) — 블러와
      어둡게 하기 오버레이를 함께 제거. `super.render()`는 향후 위젯용으로 유지. 실제 MC 1.21.1 +
      MCEF jar를 클래스패스로 javac 컴파일 확인(brigadier `Message`만 스텁)
- [MANUAL: 재빌드 후 `/shop` — 메뉴 배경 흐림 옵션을 원래 값(5)으로 되돌린 상태에서도 선명하고
  원래 밝기(어둡지 않음)로 보이는지 확인]
