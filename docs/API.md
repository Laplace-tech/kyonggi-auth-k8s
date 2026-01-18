# Kyonggi Board Backend (Auth) — API Contract (v1)

> 범위: **Auth 서브시스템(OTP Signup + JWT Access + Refresh Rotation + Me)**  
> 목표: 클라이언트/운영/테스트가 **동일한 계약(Contract)** 을 공유하도록 고정한다.  
> 상태: 현재 코드 기준 문서. 아래 **TODO(고정 필요)** 는 실제 응답/정책을 확인해 문서와 코드를 일치시켜야 한다.

- 문서 버전: 1.0
- 마지막 갱신: 2026-01-15

---

## 목차
1. 공통 규약  
2. 인증 모델  
3. 에러 응답 규약(전 레이어 통일)  
4. ErrorCode 목록(상태코드 포함)  
5. 429(Rate Limit) 계약  
6. Refresh Cookie 정책  
7. 엔드포인트 요약(한 페이지)  
8. 엔드포인트 상세(요청/응답/대표 에러)  
9. Health Endpoint(운영 함정)  
10. 운영 메모(From, 관측)  
11. 검증(Evidence): 테스트로 증명되는 계약  
12. 변경 관리(호환성 규칙)  
13. TODO(문서/코드 동기화 포인트)

---

## 1) 공통 규약

### 1.1 Base URL
- Local(예): `http://localhost:8080`
- K8s port-forward(예): `http://localhost:8080`

### 1.2 Content-Type / Encoding
- 요청 바디가 있는 엔드포인트: `Content-Type: application/json`
- 에러 응답: `application/json;charset=UTF-8` (전 레이어 통일)

### 1.3 캐시 방지(보안 권장)
인증/토큰 관련 응답은 캐시되면 위험하므로 아래 정책을 유지한다(또는 동등 수준 보장).
- `Cache-Control: no-store`
- `Pragma: no-cache`

> 보안 레이어 차단 시 `SecurityErrorWriter`가 위 정책을 보장한다.

### 1.4 시간/타임존(OTP 정책에 영향)
OTP 만료/쿨다운/일일 제한은 서버 시간 기준으로 계산된다.  
운영에서는 “하루 경계” 혼선을 막기 위해 타임존을 고정하는 것을 권장한다.
- 권장: `Asia/Seoul` (KST)
- (운영 정책) 일일 제한은 **KST 00:00~23:59** 기준으로 동작한다고 가정

---

## 2) 인증 모델 개요

### 2.1 Access Token (JWT)
- 보호 리소스 호출 시:
  - `Authorization: Bearer <accessToken>`
- 서버는 매 요청마다 JWT 서명/만료/issuer 검증으로 인증(Stateless).
- 유효하면 `SecurityContext`에 `AuthPrincipal(userId, role)`을 주입한다.

**JWT 최소 검증 요소(계약):**
- `exp`(만료) 검증
- `iss`(issuer) 검증: `app.auth.jwt.issuer` 설정값과 일치
- 서명 검증

### 2.2 Refresh Token (HttpOnly Cookie)
- refresh token 원문(raw)은 **DB에 저장하지 않음**(sha256 해시만 저장).
- refresh는 응답 바디로 내려가지 않고 **HttpOnly 쿠키(Set-Cookie)** 로만 내려감.
- `POST /auth/refresh`는 refresh 쿠키를 읽어 **rotation** 수행:
  - old 토큰은 ROTATED로 폐기
  - 새 refresh 발급 + 새 access 발급
  - ROTATED 토큰 재제출은 **REFRESH_REUSED**로 차단

**보안 불변조건(Contract Invariants)**
- (I1) DB에는 refresh raw 미저장(해시만 저장)
- (I2) ROTATED 토큰 재제출은 `REFRESH_REUSED`로 차단(성공이 나오면 안 됨)
- (I3) logout/revoke된 토큰은 재사용 불가(`REFRESH_REVOKED`)

---

## 3) 에러 응답 규약 (전 레이어 통일)

### 3.1 에러 스키마: `ApiError`
모든 에러는 다음 JSON 포맷을 사용한다(ControllerAdvice / Filter / EntryPoint 공통).

```json
{
  "code": "ERROR_CODE_ENUM_NAME",
  "message": "사용자 메시지",
  "retryAfterSeconds": 20,
  "details": {}
}
```

- `code`: `ErrorCode.name()` (클라이언트 분기용 **안정 식별자**)
- `message`: 사용자 메시지(정책에 따라 바뀔 수 있음)
- `retryAfterSeconds`: 주로 429에서 사용(없으면 JSON 미포함)
- `details`: 필요 시만 사용(없으면 JSON 미포함)

### 3.2 Validation 에러 details 계약 (TODO: 실제 응답 확인 후 고정)
`VALIDATION_ERROR`의 `details`는 클라이언트가 기계적으로 파싱 가능해야 한다.  
권장(예시):

```json
{
  "code": "VALIDATION_ERROR",
  "message": "요청 값이 올바르지 않습니다.",
  "details": {
    "fieldErrors": [
      { "field": "email", "reason": "must not be blank" },
      { "field": "password", "reason": "size must be between 8 and 64" }
    ]
  }
}
```

> TODO: 현재 구현이 위와 다르면, **실제 내려오는 형태로 문서를 고정**하거나(권장) 구현을 위 형태로 맞춘다.

### 3.3 보안 레이어 에러 응답
`GlobalExceptionHandler`는 Controller 이후만 처리한다.  
Security Filter Chain에서 차단되는 경우에도 동일 포맷을 유지하기 위해 `SecurityErrorWriter`를 사용한다.

`SecurityErrorWriter`가 보장하는 것:
- `Cache-Control: no-store`, `Pragma: no-cache`
- `Content-Type: application/json;charset=UTF-8`
- HTTP status는 `ErrorCode.status()`로 결정

### 3.4 “인증 없음” vs “토큰은 있는데 invalid” 구분
- 토큰이 아예 없음 → `RestAuthEntryPoint`가 `401 AUTH_REQUIRED`
- Authorization 헤더가 있고 Bearer token이 있으나 검증 실패 → `JwtAuthenticationFilter`가 `401 ACCESS_INVALID`

> 중요: `JwtAuthenticationFilter`는 Bearer 토큰이 있으면 검증한다.  
> 즉 **permitAll 엔드포인트라도** invalid token을 들고 오면 `ACCESS_INVALID`로 401이 떨어질 수 있다.

---

## 4) ErrorCode 목록(상태코드 포함)

| code | HTTP | 의미(요약) |
|---|---:|---|
| EMAIL_DOMAIN_NOT_ALLOWED | 400 | @kyonggi.ac.kr 도메인만 허용 
| EMAIL_ALREADY_EXISTS     | 409 | 이메일 중복                  
| NICKNAME_ALREADY_EXISTS  | 409 | 닉네임 중복                  

| OTP_ALREADY_VERIFIED     | 409 | 이미 verified (미만료)       
| OTP_COOLDOWN             | 429 | 쿨다운                      
| OTP_DAILY_LIMIT          | 429 | 일일 발송 제한               
| OTP_NOT_FOUND            | 400 | OTP 요청 이력 없음           
| OTP_EXPIRED              | 400 | OTP 만료                   
| OTP_TOO_MANY_FAILURES    | 429 | 실패횟수 초과               
| OTP_INVALID              | 400 | OTP 불일치                 
| OTP_NOT_VERIFIED         | 400 | verified 필요               

| PASSWORD_MISMATCH        | 400 | 비밀번호 불일치              
| WEAK_PASSWORD            | 400 | 비밀번호 정책 불만족          
| INVALID_NICKNAME         | 400 | 닉네임 정책 불만족            

| INVALID_CREDENTIALS      | 401 | 로그인 실패(유저 없음/비번 불일치 통합) 
| ACCOUNT_DISABLED         | 403 | 비활성 계정 

| AUTH_REQUIRED            | 401 | 인증 필요(토큰 없음) 
| ACCESS_INVALID           | 401 | access JWT invalid 

| REFRESH_INVALID          | 401 | refresh invalid(없음/미발급/유저 없음 등) 
| REFRESH_EXPIRED          | 401 | refresh 만료 
| REFRESH_REUSED           | 401 | refresh 재사용 차단 
| REFRESH_REVOKED          | 401 | refresh revoke됨 

| USER_NOT_FOUND           | 401 | 토큰은 유효하나 사용자 없음(비정상 상태) 

| VALIDATION_ERROR         | 400 | 요청 검증 실패(@Valid/@Validated) 
| INTERNAL_ERROR           | 500 | 처리되지 않은 서버 오류 
---

## 5) 429(Rate Limit) 계약
OTP 관련 429는 아래를 **동시에 제공**하는 것을 권장한다.
- Header: `Retry-After: <seconds>`
- Body: `retryAfterSeconds: <seconds>`

예시:

```http
HTTP/1.1 429
Retry-After: 15
Content-Type: application/json;charset=UTF-8
```

```json
{ "code":"OTP_COOLDOWN", "message":"잠시 후 다시 시도해주세요.", "retryAfterSeconds":15 }
```

---

## 6) Refresh Cookie 정책

쿠키 생성/삭제는 `AuthCookieUtils`가 통일한다.

### 6.1 쿠키 속성
- 이름: `KG_REFRESH` (설정값 `app.auth.refresh.cookie-name`)
- Path: `/auth` (설정값 `cookie-path`)
- HttpOnly: `true`
- Secure: 설정값(`cookie-secure`) — 운영에서는 보통 `true`
- SameSite: 설정값(`cookie-same-site`) — 예: `Lax`
- **Max-Age: 항상 포함(persistent cookie)**
  - rememberMe=true → `remember-me-seconds`
  - rememberMe=false → `session-ttl-seconds`

### 6.2 rememberMe 의미(현재 정책)
- rememberMe는 “세션 쿠키 vs 지속 쿠키” 스위치가 아니다.
- **항상 persistent(Max-Age 포함)**로 내려가며, rememberMe는 **TTL(길이)**만 결정한다.

### 6.3 로그아웃 쿠키 삭제 계약(멱등)
- `POST /auth/logout`는 항상 **삭제 Set-Cookie**를 내려 멱등을 보장한다.
- 삭제 쿠키는 기존 쿠키와 **동일한 name/path**로 내려야 브라우저가 확실히 제거한다.

---

## 7) 엔드포인트 요약(한 페이지)

| Endpoint | Method | Auth | Success | Notes |
|---|---|---|---:|---|
| /auth/signup/otp/request | POST | -                 | 204 | best-effort mail 
| /auth/signup/otp/verify  | POST | -                 | 204 | -                
| /auth/signup/complete    | POST | -                 | 201 | TODO: status/location 고정 
| /auth/login              | POST | -                 | 200 | body=access, cookie=refresh 
| /auth/refresh            | POST | cookie            | 200 | rotation, old reuse blocked 
| /auth/logout             | POST | (cookie optional) | 204 | idempotent 
| /auth/me                 | GET  | Bearer            | 200 | - 
| /actuator/health/**      | GET  | -                 | 200 | permitAll (Authorization 넣지 말 것) 

---

## 8) 엔드포인트 상세

### 8.1 Signup — OTP 요청
**POST** `/auth/signup/otp/request`  
OTP 발급 요청. 메일 발송은 트랜잭션 커밋 후(best-effort) 처리될 수 있다.

- Request Body:
```json
{ "email": "user@kyonggi.ac.kr" }
```

- Response:
  - `204 No Content`

- 대표 에러:
  - `400 EMAIL_DOMAIN_NOT_ALLOWED`
  - `409 OTP_ALREADY_VERIFIED`
  - `429 OTP_COOLDOWN` / `429 OTP_DAILY_LIMIT`

- curl:
```bash
curl -i -X POST http://localhost:8080/auth/signup/otp/request   -H 'Content-Type: application/json'   -d '{"email":"user@kyonggi.ac.kr"}'
```

---

### 8.2 Signup — OTP 검증
**POST** `/auth/signup/otp/verify`

- Request Body:
```json
{ "email": "user@kyonggi.ac.kr", "code": "123456" }
```

- Response:
  - `204 No Content`

- 대표 에러:
  - `400 OTP_NOT_FOUND`
  - `400 OTP_EXPIRED`
  - `400 OTP_INVALID`
  - `429 OTP_TOO_MANY_FAILURES`

---

### 8.3 Signup — 회원가입 완료
**POST** `/auth/signup/complete`

- Request Body:
```json
{
  "email": "user@kyonggi.ac.kr",
  "password": "Abcdef1!2",
  "passwordConfirm": "Abcdef1!2",
  "nickname": "anna_01"
}
```

- Response:
  - `201 Created` (바디 없음)

> TODO(정리): Location이 없으면 “Created” 의미가 약해질 수 있음.  
> v1에서 **204로 바꾸거나** Location 제공을 고려(문서/코드 동기화).

- 대표 에러:
  - `400 OTP_NOT_FOUND` / `400 OTP_NOT_VERIFIED` / `400 OTP_EXPIRED`
  - `400 PASSWORD_MISMATCH` / `400 WEAK_PASSWORD` / `400 INVALID_NICKNAME`
  - `409 EMAIL_ALREADY_EXISTS` / `409 NICKNAME_ALREADY_EXISTS`

---

### 8.4 Login
**POST** `/auth/login`  
성공 시:
- `accessToken`은 바디(JSON)
- `refresh`는 HttpOnly 쿠키(Set-Cookie)

- Request Body:
```json
{
  "email": "user@kyonggi.ac.kr",
  "password": "Abcdef1!2",
  "rememberMe": true
}
```

- Response `200 OK`:
```json
{ "accessToken": "<JWT>" }
```

- Response Headers(예):
```
Set-Cookie: KG_REFRESH=<refreshRaw>; Path=/auth; HttpOnly; SameSite=Lax; Max-Age=604800
```

- 대표 에러:
  - `400 EMAIL_DOMAIN_NOT_ALLOWED`
  - `401 INVALID_CREDENTIALS`
  - `403 ACCOUNT_DISABLED`
  - `400 VALIDATION_ERROR`

---

### 8.5 Refresh (Rotation)
**POST** `/auth/refresh`  
refresh 쿠키를 읽어 rotation을 수행하고, 새 refresh 쿠키 + 새 accessToken을 반환한다.

- Request:
  - Cookie: `KG_REFRESH=<oldRefreshRaw>`

- Response `200 OK`:
  - Header: `Set-Cookie: KG_REFRESH=<newRefreshRaw>; ...`
  - Body:
```json
{ "accessToken": "<JWT>" }
```

- 대표 에러:
  - `401 REFRESH_INVALID`
  - `401 REFRESH_EXPIRED`
  - `401 REFRESH_REUSED`
  - `401 REFRESH_REVOKED`

- 보안 메모(CSRF/쿠키 기반):
  - refresh는 쿠키 기반이므로 브라우저 환경에서는 SameSite 정책의 영향을 받는다.
  - cross-site 환경(CORS/프론트 분리 등)이 들어오면 CSRF 전략을 별도 검토해야 한다.

---

### 8.6 Logout (Idempotent)
**POST** `/auth/logout`  
항상 쿠키 삭제 Set-Cookie를 내려주는 “멱등 로그아웃”.

- Request:
  - Cookie가 없어도 호출 가능

- Response:
  - `204 No Content`
  - Header: refresh 쿠키 삭제(`Max-Age=0`)

- 서버 동작:
  - refresh 쿠키가 있으면 DB에서 해당 세션 revoke(LOGOUT) 시도(best-effort)
  - 쿠키가 없거나 이미 revoke된 경우에도 성공(204)

---

### 8.7 Me (내 정보)
**GET** `/auth/me`  
Access JWT 필요.

- Request:
  - `Authorization: Bearer <accessToken>`

- Response `200 OK`:
```json
{
  "userId": 1,
  "email": "user@kyonggi.ac.kr",
  "nickname": "anna_01",
  "role": "USER",
  "status": "ACTIVE"
}
```

- 대표 에러(중요):
  - 토큰 없음 → `401 AUTH_REQUIRED` (EntryPoint)
  - 토큰 있음 + invalid → `401 ACCESS_INVALID` (Filter)
  - 유저 없음 → `401 USER_NOT_FOUND`
  - 비활성 계정 → `403 ACCOUNT_DISABLED`

---

## 9) Health Endpoint (운영 함정)
- `GET /actuator/health/**` 는 permitAll (k8s probe 목적)
- 단, **Authorization 헤더에 invalid Bearer를 보내면 401이 될 수 있으므로** health 체크 요청에는 Authorization을 포함하지 않는다.

---

## 10) 운영 메모(From 주소 / 관측)
SMTP(Brevo/SendGrid 등)는 “이메일 형식”과 별개로 **검증된 Sender만 From으로 허용**하는 경우가 많다.
- **“From(app.mail.from)은 SMTP에서 검증된 sender로 맞춰야 한다.”**

또한 OTP 발급 성공(204)과 SMTP 수신 성공은 분리될 수 있다.
- 운영에서는 앱 로그(성공/실패 1줄) 또는 provider 로그로 발송 여부를 관측한다.

---

## 11) 검증(Evidence): 테스트로 증명되는 계약
테스트 코드를 문서에 복붙하지 않고, “무엇을 증명하는지(Claim)→어떤 테스트가 보장하는지(Evidence)”만 고정한다.

| 보장(Claim) | Evidence(Test) |
|---|---|
| OTP 정책(쿨다운/만료/실패횟수) | `AuthSignupOtpServiceIT` |
| 회원가입 흐름(OTP→complete) | `AuthSignupServiceIT` |
| 로그인/내 정보 | `AuthLoginIT`, `AuthMeIT` |
| Refresh rotation 정상 동작 | `AuthRefreshRotationIT` |
| Refresh 재사용 공격 차단 | `AuthRefreshSecurityIT` |
| Health endpoints 200 | `ActuatorHealthIT` |

---

## 12) 변경 관리(호환성 규칙)
- `ErrorCode.name()`은 클라이언트 분기 기준이므로 **변경 금지**(breaking change).
- 응답 스키마(ApiError, 주요 DTO)는 v1에서 가능한 한 **추가만 허용**(삭제/rename 금지).
- breaking change가 필요하면 `/v2/...` 등 명시적 버전 전략을 사용한다.

---

## 13) TODO (문서/코드 동기화 포인트)
1) `VALIDATION_ERROR.details` 실제 응답 형태로 고정
2) `/auth/signup/complete` 성공 status + Location 정책 고정
