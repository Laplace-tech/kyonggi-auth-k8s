# DEMO — 5~8 min Script

> 목적: 발표/면접에서 “보여줄 순서”를 고정해서 말이 새지 않게 한다.  
> 원칙: 자세한 명령어는 RUNBOOK을 참조하고, 여기선 흐름/포인트/기대 결과만 적는다.

---

## 0) 데모 목표(한 줄)
OTP 기반 회원가입 + JWT 인증 + Refresh Rotation(재사용 공격 차단) + 운영 재현(health/probes, SMTP)

---

## 1) 시연 순서(8 steps)

1) **Health 확인**
- 기대: `/actuator/health` 200, liveness/readiness 200

2) **OTP 발급 요청**
- 기대: `POST /auth/signup/otp/request` → 204

3) **메일 수신(로컬 MailHog 또는 Prod SMTP)**
- 기대: OTP 코드 수신(헤더/시간 확인)

4) **OTP verify**
- 기대: `POST /auth/signup/otp/verify` → 204

5) **signup complete**
- 기대: `POST /auth/signup/complete` → 201(or 204; 문서와 코드 일치 필요)

6) **login**
- 기대: `POST /auth/login` → 200 + accessToken(JSON) + refresh 쿠키(Set-Cookie)

7) **me**
- 기대: `GET /auth/me` → 200(userId/email/nickname/role/status)

8) **refresh rotation + 보안 포인트**
- 기대: `POST /auth/refresh` → 새 refresh 쿠키 + 새 accessToken
- 포인트: **old refresh 재제출은 REFRESH_REUSED로 차단**(보안 데모)

---

## 2) 데모에서 강조할 설계 포인트(짧게)
- 에러 응답 스키마 통일(ApiError)로 클라이언트 분기 안정화
- Refresh는 raw 미저장(해시 저장) + rotation + 재사용 공격 차단
- 운영에서 Secret 주입/프로필 분리로 환경 혼선 방지
