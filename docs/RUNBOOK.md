# RUNBOOK — Reproducible Operations (Compose / k3d)

> 목적: “명령어만 따라치면” 동일 결과가 나오는 **재현/운영 절차** 문서  
> 원칙: 절차(명령어)는 이 문서에만 둔다. 다른 문서는 이 문서를 링크로 참조한다.

---

## 0) Prerequisites
- Docker / Docker Compose
- kubectl
- k3d (로컬 Kubernetes)
- (선택) curl, jq

---

## 1) Quickstart — Docker Compose (Local)
### 1.1 준비
- `.env`는 **로컬에서만** 생성한다(커밋 금지). 템플릿은 `.env.example` 참고.

### 1.2 기동
```bash
cd ~/kyonggi-board/ops/compose

docker compose down -v
docker compose up -d --build
docker compose ps

docker compose logs -f backend
docker compose logs -f mailhog
docker compose logs -f mysql
```

### 1.3 검증(필수)
```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness

curl -i -X POST "http://localhost:8080/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d '{"email":"add28482848@kyonggi.ac.kr"}'  
```

### 1.4 종료/정리
```bash
docker compose down -v
```

---

## 2) Quickstart — k3d (Local overlay: MailHog)
### 2.1 클러스터 생성/삭제
```bash
cd ~/kyonggi-board
pkill -f "kubectl.*port-forward" || true

k3d cluster delete kyonggi || true
k3d cluster create kyonggi --servers 1 --agents 1

# 컨텍스트/네임스페이스 확정
kubectl config use-context k3d-kyonggi
kubectl get nodes -o wide

kubectl create namespace kyonggi --dry-run=client -o yaml | kubectl apply -f -
kubectl get ns kyonggi
```

### 2.2 이미지 빌드 + k3d import (태그 고정: :local)
```bash
docker build -t kyonggi-backend:local -f backend/Dockerfile backend
k3d image import kyonggi-backend:local -c kyonggi

# (옵션) import 확인 (k3d 노드에 이미지가 들어갔는지)
docker exec k3d-kyonggi-agent-0 crictl images | grep kyonggi-backend || true
```

### 2.3 배포(local overlay) + 상태/로그/리소스 확인
```bash
kubectl apply -k ops/k8s/overlays/local

kubectl -n kyonggi get all
kubectl -n kyonggi get pods -o wide

kubectl -n kyonggi rollout status deploy/backend --timeout=180s

kubectl -n kyonggi get pods -o wide
kubectl -n kyonggi logs -f deploy/backend 

# 문제 생기면: events/describe가 먼저다 (원인 1분 컷)
kubectl -n kyonggi get events --sort-by=.lastTimestamp | tail -n 30
kubectl -n kyonggi describe pod -l app=backend | sed -n '1,220p'
```

### 2.4 port-forward + 검증
```bash
kubectl -n kyonggi port-forward svc/backend 8080:8080
kubectl -n kyonggi port-forward svc/mailhog 8025:8025

curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness

curl -i -X POST "http://localhost:8080/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d '{"email":"add28482848@kyonggi.ac.kr"}'

# MailHog UI (브라우저): http://localhost:8025
```


---

## 3) Quickstart — k3d (Prod overlay: Real SMTP)
> prod overlay는 “실제 SMTP로 릴레이”한다. **시크릿은 절대 커밋 금지**.

### 3.1 클러스터 완전 초기화 → 새로 생성
```bash
cd ~/kyonggi-board

k3d cluster delete kyonggi || true
k3d cluster create kyonggi --servers 1 --agents 1

kubectl config use-context k3d-kyonggi
kubectl get nodes -o wide
```

### 3.2 네임스페이스 생성
```bash
kubectl create namespace kyonggi --dry-run=client -o yaml | kubectl apply -f -
kubectl get ns kyonggi
```

### 3.3 시크릿 파일 준비(필수) — 경로 고정
```bash
ls -la ops/k8s/overlays/prod/secrets/backend.env
ls -la ops/k8s/overlays/prod/secrets/mysql.env
```

### 3.4 이미지 빌드 (tag = v1.0.0 고정)
```bash
docker build -t kyonggi-backend:v1.0.0 -f backend/Dockerfile backend
docker images | grep kyonggi-backend
```

### 3.5 k3d로 이미지 import + prod overlay 배포
```bash
k3d image import kyonggi-backend:v1.0.0 -c kyonggi
kubectl apply -k ops/k8s/overlays/prod

# ✅ Deployment가 참조하는 이미지(태그) 확인 (태그 mismatch 디버깅 핵심)
kubectl -n kyonggi get deploy backend -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'

# (옵션) imagePullPolicy까지 같이 보기 (Always면 k3d에서 ErrImagePull 나기 쉬움)
kubectl -n kyonggi get deploy backend -o jsonpath='{.spec.template.spec.containers[0].imagePullPolicy}{"\n"}'

kubectl -n kyonggi get pods -o wide
kubectl -n kyonggi rollout status deploy/backend --timeout=180s
```
 
### 3.6 로그 확인 + 포트포워딩 및 검증
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

---

## 5) Troubleshooting (빠른 원인 분리)
- 8080 포트 충돌( port-forward 실패 )  
- mysql connection refused(초기 기동 지연)  
- readiness/liveness fail(health group 구성/actuator 노출)  
- 535 SMTP auth failed(Secret 키/값/From 검증)  
- Secret/ConfigMap 키 누락(kustomize generator/파일 경로)

> 각 항목마다 “현상 → 확인 명령 → 원인 → 조치” 순서로 쓴다.

---

## 6) Verification: Tests vs Smoke
- **정책/불변조건**: `./backend/gradlew test` (CI에서도 동일)
- **운영 wiring**: Compose/k3d에서 curl 스모크
- **증거물**: `docs/assets/`에 캡처를 저장하고 본 문서에서 링크






# RUNBOOK — Reproducible Operations (Compose / k3d)

> 목적: “명령어만 따라치면” 동일 결과가 나오는 **재현/운영 절차** 문서  
> 원칙: 절차(명령어)는 이 문서에만 둔다. 다른 문서는 이 문서를 링크로 참조한다.

---

## 0) Prerequisites
- Docker / Docker Compose
- kubectl
- k3d (로컬 Kubernetes)
- (선택) curl, jq

---

## 1) Quickstart — Docker Compose (Local)
### 1.1 준비
- `.env`는 **로컬에서만** 생성한다(커밋 금지). 템플릿은 `.env.example` 참고.

### 1.2 기동
```bash
# (예) ops/compose로 옮겼다면 경로만 변경해서 사용
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
```

### 1.3 검증(필수)
```bash
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness

curl -i -X POST "http://localhost:8080/auth/signup/otp/request"   -H "Content-Type: application/json"   -d '{"email":"add28482848@kyonggi.ac.kr"}'
```

### 1.4 종료/정리
```bash
docker compose -f infra/docker-compose.yml down -v
```

---



## 2) Quickstart — k3d (Local overlay: MailHog)
### 2.1 클러스터 생성/삭제
```bash
k3d cluster delete kyonggi || true
k3d cluster create kyonggi --servers 1 --agents 1
kubectl config use-context k3d-kyonggi
kubectl get nodes -o wide
```

### 2.2 이미지 빌드 + import
```bash
kubectl create ns kyonggi --dry-run=client -o yaml | kubectl apply -f -
docker build -t kyonggi-backend:v1.0.0 ./backend
k3d image import kyonggi-backend:v1.0.0 -c kyonggi
```

### 2.3 배포(local overlay)
```bash
kubectl apply -k deploy/overlays/local
kubectl -n kyonggi get pods -o wide
kubectl -n kyonggi rollout status deploy/backend --timeout=180s
```

### 2.4 port-forward + 검증
```bash
kubectl -n kyonggi port-forward svc/backend 8080:8080
kubectl -n kyonggi port-forward svc/mailhog 8025:8025

curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/actuator/health/liveness
curl -i http://localhost:8080/actuator/health/readiness

curl -i -X POST "http://localhost:8080/auth/signup/otp/request" \
  -H "Content-Type: application/json" \
  -d '{"email":"add28482848@kyonggi.ac.kr"}'

```

---

## 3) Quickstart — k3d (Prod overlay: Real SMTP)
> prod overlay는 “실제 SMTP로 릴레이”한다. **시크릿은 절대 커밋 금지**.

### 3.1 배포(prod overlay)
```bash
kubectl apply -k deploy/overlays/prod
kubectl -n kyonggi rollout status deploy/backend --timeout=180s
```

### 3.2 검증(필수)
- Health 200
- OTP request 204
- **실 메일 수신 증거 캡처**(헤더/스크린샷) → `docs/assets/`에 저장

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

---

## 5) Troubleshooting (빠른 원인 분리)
- 8080 포트 충돌( port-forward 실패 )  
- mysql connection refused(초기 기동 지연)  
- readiness/liveness fail(health group 구성/actuator 노출)  
- 535 SMTP auth failed(Secret 키/값/From 검증)  
- Secret/ConfigMap 키 누락(kustomize generator/파일 경로)

> 각 항목마다 “현상 → 확인 명령 → 원인 → 조치” 순서로 쓴다.

---

## 6) Verification: Tests vs Smoke
- **정책/불변조건**: `./backend/gradlew test` (CI에서도 동일)
- **운영 wiring**: Compose/k3d에서 curl 스모크
- **증거물**: `docs/assets/`에 캡처를 저장하고 본 문서에서 링크
