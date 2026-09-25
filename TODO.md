# TODO — ARCLink HMAC/핸드셰이크 진단 후속

- [MANUAL: 완료 — host가 비어 있어 연결 시도 자체가 스킵되고 있었음. 확인됨, DECISIONS.md 참고. host 채운 뒤 재접속 재확인 필요.]
  "사용자가 확인할 것"에 적힌 순서(`/arclink status` → 클라이언트 `ARCLink:` 로그 줄 판독)로
  정확히 어느 단계에서 끊기는지 특정할 것.]
- [BLOCKED: 이 세션의 device_bash 셸은 아웃바운드 네트워크가 전면 차단되어(services.gradle.org,
  repo1.maven.org 모두 프록시 403) `./gradlew build`/`test`를 한 번도 실행하지 못했다. 목 소켓
  서버 기반 핸드셰이크 회귀 테스트(동일 키 성공 / 키·인코딩 불일치 실패)는 네트워크가 열린
  환경에서 추가해야 한다. 상세는 `DECISIONS.md` "이번 세션의 제약" 참고.]
