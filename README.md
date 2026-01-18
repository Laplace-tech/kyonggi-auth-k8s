# Docs Index

이 폴더의 문서들은 **서로 역할이 겹치지 않게** 나눠져 있다.  
원칙은 단 하나: **절차(명령어)는 RUNBOOK에만 둔다.** 나머지는 RUNBOOK을 링크로 참조한다.

---

## 프로젝트 주제

**Kyonggi Board Backend (Auth Subsystem) — OTP Signup + JWT Access + Refresh Rotation + Reproducible Ops**

이 프로젝트는 “게시판 CRUD”가 아니라, **인증(Auth) 서브시스템을 제품처럼 재현 가능하게** 만드는 것을 주제로 한다.

### 핵심 목표
- **회원가입 OTP(이메일)**: 만료/쿨다운/일일 제한/실패 카운터 등 **남용 방지 정책**을 포함한 OTP 플로우
- **JWT Access Token**: 짧은 TTL 기반 접근 토큰 발급 및 검증
- **Refresh Token Rotation**:
  - Refresh 원문은 저장하지 않고 **해시(SHA-256)만 DB 보관**
  - 토큰 재사용 공격을 **REUSED 차단**으로 방어
  - 동시성 상황에서도 “한 번만 성공”하는 **불변조건(invariant)**을 고정
- **운영/재현(Ops) 증명**:
  - Docker Compose / k3d(Kubernetes)로 같은 결과를 **명령어만 따라치면** 재현
  - `/actuator/health*` 200 + OTP 메일 수신(MailHog/실SMTP)까지 **E2E 증빙**을 남김

### 스코프(의도적으로 제외)
- 게시글/댓글 같은 일반 CRUD 기능 확장
- 사용자 프로필/권한 체계의 대규모 확장
- “기능 추가” 중심의 개발(현재는 v1 봉인 단계에서 문서·런북·증빙 중심)

### 산출물(문서 중심)
- RUNBOOK / API / DEMO / TESTS / ROADMAP 문서로 **단일 진실** 유지
- 캡처/증빙(스크린샷, 헤더)은 `docs/assets/`에 저장하고 문서에서는 링크만 사용

---

## 문서 역할(단일 진실)

- **RUNBOOK.md**: 재현/운영 절차(명령어 중심) + 스모크 검증(curl) + 테스트 실행(gradle) + 트러블슈팅
- **API.md**: HTTP 계약(스펙) 단일 진실(요청/응답/에러/쿠키 정책/함정/호환성 규칙)
- **DEMO.md**: 발표/시연용 5~8분 스크립트(순서 고정, 말할 포인트 포함)

## 빠른 링크
- [RUNBOOK](./docs/RUNBOOK.md)
- [API](./docs/API.md)
- [DEMO](./docs/DEMO.md)
- [TESTS](./docs/TESTS.md)

## Evidence / Assets
- 스크린샷/헤더 캡처/다이어그램은 `docs/assets/`에 저장하고 **문서에서는 링크로만 참조**한다.
- 파일명 규칙(권장): `YYYY-MM-DD__phaseX__what.png|txt|md`
  - 예: `2026-01-14__phase4__brevo_mail_header.png`
  - 예: `2026-01-14__phase3__k3d_health_200.png`

## 중복 금지 규칙(중요)
- “같은 내용 2번 쓰기”가 피곤함의 원인이다. 아래처럼 분리한다.
  - 실행 방법(명령어/절차/커맨드) → **RUNBOOK**
  - 스펙(요청/응답/에러/쿠키) → **API**
  - 결과 캡처/증빙(메일 헤더, curl 출력 스샷) → **assets + RUNBOOK 링크**
  - 발표 흐름(시연 순서/멘트) → **DEMO**
  - 진행 체크/완료 조건/다음 할 일 → **ROADMAP**

## 업데이트 룰(추천)
- 코드 변경 후: API.md(계약) → 테스트 통과(증거) → RUNBOOK(재현/검증) 순으로 맞춘다.
- 문서 버전/변경이 생기면 API.md 상단 “마지막 갱신일”을 업데이트한다.