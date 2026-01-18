# RUNBOOK — Reproducible Operations (Compose / k3d)

> 목적: “명령어만 따라치면” 동일 결과가 나오는 **재현/운영 절차** 문서  
> 원칙: 절차(명령어)는 이 문서에만 둔다. 다른 문서는 이 문서를 링크로 참조한다.

---

## 0) Prerequisites
- Docker / Docker Compose
- kubectl
- k3d (로컬 Kubernetes)
- (선택) curl, jq, lsof

---

## 1) Quickstart — Docker Compose (Local)

### 1.1 준비
- `.env`는 **로컬에서만** 생성한다(커밋 금지). 템플릿은 `.env.example` 참고.
- MailHog UI: `http://localhost:8025/`
  - Spring은 SMTP로 `mailhog:1025`에 메일을 보내고, MailHog가 UI(8025)에서 “메일이 온 것처럼” 쌓아서 보여준다.

### 1.2 도커 리셋/기동(완전 초기화 + 재빌드)
```bash
cd ~/kyonggi-board/ops/compose

# 컨테이너/네트워크 제거 + 볼륨(mysql_data)까지 제거(= DB 초기화)
docker compose down -v

# 백그라운드 실행 + backend 이미지 재빌드(코드 변경 반영)
docker compose up -d --build

# 상태 확인(mysql healthcheck 포함)
docker compose ps
```

> backend만 다시 빌드/재기동하고 싶으면:
```bash
cd ~/kyonggi-board/ops/compose
docker compose up -d --build backend
docker compose ps
```

### 1.3 로그 확인(선택)
```bash
cd ~/kyonggi-board/ops/compose
docker compose logs -f backend
docker compose logs -f mailhog
docker compose logs -f mysql
```

### 1.4 검증(필수: 최소 증명 세트)
```bash
# health group 200
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness

# OTP 요청 204
curl -i -X POST "http://localhost:8080/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d '{"email":"<YOUR_KYONGGI_EMAIL>@kyonggi.ac.kr"}'
```

- 브라우저로 MailHog UI 확인: `http://localhost:8025/`  
  (OTP 메일이 실제 발송된 것처럼 쌓여야 정상)

> “회원가입~로그아웃/refresh rotation”까지 집요하게 검증하려면 Appendix A를 실행.

### 1.5 종료/정리
```bash
cd ~/kyonggi-board/ops/compose
docker compose down -v
```

---

## 2) Quickstart — k3d (Local overlay: MailHog)

### 2.1 클러스터 생성/삭제
```bash
cd ~/kyonggi-board

# 이전 port-forward(있으면) 정리
pkill -f "kubectl.*port-forward" || true

k3d cluster delete kyonggi || true
k3d cluster create kyonggi --servers 1 --agents 1

# 컨텍스트 확정
kubectl config use-context k3d-kyonggi
kubectl get nodes -o wide

# 네임스페이스 확정
kubectl create namespace kyonggi --dry-run=client -o yaml | kubectl apply -f -
kubectl get ns kyonggi
```

### 2.2 이미지 빌드 + k3d import (태그 고정: :local)
```bash
cd ~/kyonggi-board

docker build -t kyonggi-backend:local -f backend/Dockerfile backend
k3d image import kyonggi-backend:local -c kyonggi

# (옵션) import 확인
docker exec k3d-kyonggi-agent-0 crictl images | grep kyonggi-backend || true
```

### 2.3 배포(local overlay) + 상태/로그 확인
```bash
cd ~/kyonggi-board
kubectl apply -k ops/k8s/overlays/local

kubectl -n kyonggi get all
kubectl -n kyonggi get pods -o wide
kubectl -n kyonggi rollout status deploy/backend --timeout=180s

kubectl -n kyonggi logs -f deploy/backend

# 문제 생기면: logs보다 events/describe가 먼저다
kubectl -n kyonggi get events --sort-by=.lastTimestamp | tail -n 30
kubectl -n kyonggi describe pod -l app=backend | sed -n '1,220p'
```

### 2.4 port-forward + 검증
> port-forward는 보통 “터미널 2개”로 나눠서 실행한다. (backend 8080, mailhog 8025)

터미널 1:
```bash
kubectl -n kyonggi port-forward svc/backend 8080:8080
```

터미널 2:
```bash
kubectl -n kyonggi port-forward svc/mailhog 8025:8025
```

검증:
```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness

curl -i -X POST "http://localhost:8080/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d '{"email":"<YOUR_KYONGGI_EMAIL>@kyonggi.ac.kr"}'
```

- MailHog UI: `http://localhost:8025/`

---

## 3) Quickstart — k3d (Prod overlay: Real SMTP)

> prod overlay는 “실제 SMTP로 릴레이”한다. **시크릿은 절대 커밋 금지**.  
> 검증 기준: `health 200` + `OTP request 204` + **실메일 수신 증거(스크린샷/헤더)**를 `docs/assets/`에 저장.

### 3.1 클러스터 완전 초기화 → 새로 생성
```bash
cd ~/kyonggi-board

k3d cluster delete kyonggi || true
k3d cluster create kyonggi --servers 1 --agents 1

kubectl config use-context k3d-kyonggi
kubectl get nodes -o wide

kubectl create namespace kyonggi --dry-run=client -o yaml | kubectl apply -f -
kubectl get ns kyonggi
```

### 3.2 시크릿 파일 준비(필수) — 경로 고정
```bash
ls -la ops/k8s/overlays/prod/secrets/backend.env
ls -la ops/k8s/overlays/prod/secrets/mysql.env
```

### 3.3 이미지 빌드 + k3d import (tag 고정 예: v1.0.0)
```bash
cd ~/kyonggi-board

docker build -t kyonggi-backend:v1.0.0 -f backend/Dockerfile backend
k3d image import kyonggi-backend:v1.0.0 -c kyonggi
```

### 3.4 prod overlay 배포 + 참조 이미지/정책 확인(태그 mismatch 디버깅 핵심)
```bash
cd ~/kyonggi-board
kubectl apply -k ops/k8s/overlays/prod

# ✅ Deployment가 참조하는 이미지(태그) 확인 (tag mismatch 디버깅 핵심)
kubectl -n kyonggi get deploy backend -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'

# (옵션) imagePullPolicy 확인 (Always면 k3d에서 ErrImagePull 나기 쉬움)
kubectl -n kyonggi get deploy backend -o jsonpath='{.spec.template.spec.containers[0].imagePullPolicy}{"\n"}'

kubectl -n kyonggi get pods -o wide
kubectl -n kyonggi rollout status deploy/backend --timeout=180s
```
 
### 3.5 로그 확인 + 포트포워딩 및 검증
```bash
# 롤아웃 실패 시: logs보다 events/describe가 먼저다
kubectl -n kyonggi get events --sort-by=.lastTimestamp | tail -n 30
kubectl -n kyonggi describe pod -l app=backend | sed -n '1,220p'

kubectl -n kyonggi logs -f deploy/backend
kubectl -n kyonggi port-forward svc/backend 8080:8080

curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness

curl -i -X POST "http://localhost:8080/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d '{"email":"add28482848@kyonggi.ac.kr"}'
```

- **실 메일 수신 증거 캡처(필수)**  
  - 메일 본문(OTP) + 헤더(발송 도메인/relay) 캡처  
  - `docs/assets/`에 저장하고 README/DEMO에서 링크

---

## 4) 운영 명령어 모음(자주 쓰는 것)

### 4.1 로그
```bash
kubectl -n kyonggi logs -f deploy/backend --tail=200
kubectl -n kyonggi get events --sort-by=.lastTimestamp -w
```

### 4.2 상태/문제 조사
```bash
kubectl -n kyonggi get pods -o wide
kubectl -n kyonggi describe pod -l app=backend | sed -n '/Events/,$p'
```

### 4.3 포트/프로세스 점유 확인(호스트)
```bash
# 컨테이너 포트 매핑 확인
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# 8080/8025 점유 프로세스 확인(특히 port-forward/compose 충돌)
sudo lsof -i :8080 || true
sudo lsof -i :8025 || true
```

---

## 5) Troubleshooting (빠른 원인 분리)

> 형식: **현상 → 확인 명령 → 원인 → 조치** 순서로 쓴다.

- 8080 포트 충돌 (port-forward 실패 / compose backend가 못 뜸)
  - 확인: `sudo lsof -i :8080`, `pkill -f "kubectl.*port-forward"`
- mysql connection refused (초기 기동 지연)
  - 확인: `kubectl get events`, `kubectl describe pod`, compose면 `docker compose logs -f mysql`
- readiness/liveness fail
  - 확인: `/actuator/health/*`, `describe pod Events`, 환경변수/프로필/actuator 노출
- 535 SMTP auth failed
  - 확인: backend 로그 스택, Secret 값, From 정책
- Secret/ConfigMap 키 누락 (kustomize generator/파일 경로)
  - 확인: `kubectl describe pod`에 env 주입 실패 메시지

---

## 6) Verification: Tests vs Smoke
- **정책/불변조건(로직/보안 계약)**: `./backend/gradlew test` (CI에서도 동일)
- **운영 wiring(배포/네트워크/SMTP/쿠키/포트)**: Compose/k3d에서 curl 스모크
- **증거물**: `docs/assets/`에 캡처 저장 후 본 문서/DEMO/README에서 링크

---

# Appendix A — Auth Smoke Test (curl, full flow)

> 목적: 회원가입 → 로그인 → me → refresh rotation → logout까지,  
> API/쿠키/토큰/DB 상태를 “집요하게” 수동 검증한다.

## A.0 변수(권장)
```bash
export BASE="http://localhost:8080"
export EMAIL="anna@kyonggi.ac.kr"
export PASS="StrongPassw0rd!"
export NICK="Anna"

# 쿠키 파일 경로
export CK="/tmp/kyonggi_cookie.txt"
export CK2="/tmp/kyonggi_cookie_new.txt"
export CK3="/tmp/kyonggi_cookie_after_logout.txt"
```

## A.1 OTP 요청 (204) → MailHog에서 코드 확인
```bash
curl -i -X POST "$BASE/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\"}"
```

- Compose: MailHog UI `http://localhost:8025/`
- k3d(local): `kubectl -n kyonggi port-forward svc/mailhog 8025:8025` 후 `http://localhost:8025/`

MailHog에서 OTP 코드를 확인하고 아래에 넣는다.

## A.2 OTP 검증 (204)
```bash
export OTP="837064"  # <-- MailHog에서 본 값으로 교체

curl -i -X POST "$BASE/auth/signup/otp/verify" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"code\":\"$OTP\"}"
```

실패하면 보통:
- 만료 / 횟수 초과 / 코드 불일치 / 쿨다운 위반 / 일일 제한 등의 정책 에러

## A.3 가입 완료 (201)
```bash
curl -i -X POST "$BASE/auth/signup/complete" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"passwordConfirm\":\"$PASS\",\"nickname\":\"$NICK\"}"
```

> 여기서 “OTP verified 상태가 아니면” 가입이 막히는 게 정상.

## A.4 로그인 (200) — refresh 쿠키 저장 + accessToken 추출
```bash
# -c: Set-Cookie(리프레시 토큰 쿠키)를 파일에 저장
curl -i -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -c "$CK" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"rememberMe\":false}"
```

응답 바디에 accessToken이 JSON으로 온다고 가정하면(프로젝트 설계 기준), jq로 뽑아서 저장:
```bash
# (옵션) accessToken 추출 (응답 바디가 JSON일 때만)
ACCESS="$(curl -s -X POST "$BASE/auth/login" \
  -H "Content-Type: application/json" \
  -c "$CK" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"rememberMe\":false}" | jq -r '.accessToken')"
echo "$ACCESS"
```

쿠키 파일 확인:
```bash
cat "$CK"
# Netscape cookie jar 포맷이라 한 줄이 길어도 정상
# KG_REFRESH 같은 이름의 쿠키 값이 있으면 정상
```

## A.5 /auth/me (Authorization: Bearer)
```bash
# 위에서 ACCESS를 뽑았다면 그대로 사용
curl -i -X GET "$BASE/auth/me" \
  -H "Authorization: Bearer $ACCESS"
```

- 성공: 내 정보 반환
- 실패: 만료/서명불일치면 401
- 이 엔드포인트는 Security/JWT 필터가 “진짜로 동작”하는지 확인하는 핵심

## A.6 refresh rotation (old 쿠키 → new 쿠키로 교체)
```bash
# -b: 저장된 쿠키를 요청에 실어 보냄
# -c: 서버가 새 Set-Cookie를 내려주면 새 파일에 저장
curl -i -X POST "$BASE/auth/refresh" \
  -b "$CK" \
  -c "$CK2"
```

새 쿠키 확인:
```bash
cat "$CK2"
```

## A.7 로그아웃 (쿠키 보내고, 서버가 만료 Set-Cookie 내려줌)
```bash
curl -i -X POST "$BASE/auth/logout" \
  -b "$CK2" \
  -c "$CK3"

cat "$CK3"
```

정상이면:
- 서버가 `Max-Age=0` 또는 만료 Set-Cookie를 내려서 쿠키가 무력화된다.

## A.8 정리
```bash
rm -f "$CK" "$CK2" "$CK3" || true
```

---

# Appendix B — DB 확인 (Compose 기준)

> 목적: users / email_otp / refresh_tokens 상태를 “눈으로” 확인한다.

프로젝트에 `dmysql` 유틸이 있으면:
```bash
dmysql -e "select * from users;"
dmysql -e "select * from email_otp;"
dmysql -e "select * from refresh_tokens;"
```

`dmysql`이 없다면(Compose mysql 컨테이너 이름이 예: kyonggi-mysql일 때):
```bash
docker exec -i kyonggi-mysql \
  mysql -ukyonggi -pkyonggi kyonggi_board \
  -e "select * from users;"

docker exec -i kyonggi-mysql \
  mysql -ukyonggi -pkyonggi kyonggi_board \
  -e "select * from email_otp;"

docker exec -i kyonggi-mysql \
  mysql -ukyonggi -pkyonggi kyonggi_board \
  -e "select * from refresh_tokens;"
```

---

# Appendix C — 테스트 코드 실행 목록(Gradle)

> 목적: “정책/불변조건”을 자동으로 검증한다.  
> 기준 위치: `~/kyonggi-board/backend`

## C.1 전체 테스트
```bash
cd ~/kyonggi-board/backend

# 기본
./gradlew test

# 빌드 산출물까지 리셋 후 다시
./gradlew clean test

# 자세한 로그(원인 추적에 유리)
./gradlew test --info

# 실패 원인 스택트레이스 상세
./gradlew test --stacktrace

# 데몬 영향 제거(환경 일관성)
./gradlew test --no-daemon

# 캐시/업투데이트 무시하고 강제로 다시 실행
./gradlew test --rerun-tasks
```

## C.2 특정 테스트 클래스/패턴만 실행
```bash
cd ~/kyonggi-board/backend

# FQCN(권장: 가장 확실)
./gradlew test --tests "com.kyonggi.backend.ActuatorHealthIT"

# 패턴 매칭(와일드카드 가능)
./gradlew test --tests "*AuthMeIntegrationTest"
./gradlew test --tests "*AuthRefreshRotationIntegrationTest"
./gradlew test --tests "*AuthLogoutIntegrationTest"

# 패키지/접두 패턴(Gradle 패턴 규칙에 따름)
./gradlew test --tests "com.kyonggi.backend.auth.*"
```

## C.3 테스트 결과/리포트 확인
```bash
# HTML 리포트(로컬 파일)
# backend/build/reports/tests/test/index.html

# XML 결과
# backend/build/test-results/test/
```

---

# Appendix D — 코드 검색(grep) 유틸
```bash
cd ~/kyonggi-board/backend

grep -Rni --include="*.java" "AuthProperties" .
grep -Rni --include="*.java" "OtpProperties" .
grep -Rnw --include="*.java" "EMAIL" .
```
