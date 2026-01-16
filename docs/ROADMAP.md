# ROADMAP — Phase 0~7 Progress Board

> 목적: “완료 기준이 뭔지”를 못 박고, 진행을 체크한다.  
> 원칙: 길게 쓰지 말고 상태/완료조건/다음 액션만 둔다.

---

## Phase 0 — 에러 응답 통일/인코딩+Content-Type/Actuator health+probes
- 상태: ✅ 완료
- 완료 기준(예):
  - health 계열 200
  - 보안/컨트롤러 레이어 에러가 ApiError 스키마로 통일

## Phase 1 — 프로필·설정 분리(4 yml+compose)·테스트 격리
- 상태: ✅ 거의 완료
- 남은 작업(예):
  - prod에서 필요한 env 미주입 시 fail-fast 여부 재확인
  - 문서/템플릿(.env.example) 정리

## Phase 2 — Compose runbook 정리
- 상태: 🟨 진행 중
- 완료 기준:
  - RUNBOOK의 Compose 섹션만 보고 30분 내 재현 가능

## Phase 3 — k3d(K8s) 배포 MVP(kustomize overlay local) + E2E 검증
- 상태: ✅ 완료
- 완료 기준:
  - pods Running
  - port-forward
  - health 200
  - otp request 204
  - MailHog 수신(로컬 오버레이)

## Phase 4 — Brevo/SendGrid SMTP 실발송 증명(Secret/ConfigMap 주입)
- 상태: ✅ 완료
- 완료 기준:
  - prod 프로필로 기동
  - OTP 메일 실제 수신(헤더/스크린샷 증거)

## Phase 5 — 관측/로그(운영 지표/로그 정리)
- 상태: ⬜ 미착수
- 완료 기준(최소):
  - 운영 로그 정책(레벨/포맷) 고정
  - 메일 발송 성공/실패 관측 지점 확보(로그/대시보드)

## Phase 6 — 위협모델(Threat model)
- 상태: ⬜ 미착수
- 완료 기준:
  - 1~2p 문서(자산/공격자/공격면/대응) + 테스트/코드 근거 연결

## Phase 7 — README/API/데모/CI로 v1 봉인
- 상태: 🟨 부분 진행
- 완료 기준:
  - README는 길 안내만(문서 링크)
  - CI에서 `./backend/gradlew test` green
  - v1.0.0 tag/release (freeze)

---

## Next 3 Actions (추천)
1) API.md의 TODO(VALIDATION details, signup complete status) 문서/코드 일치
2) RUNBOOK Compose/k3d(local/prod) 3트랙 완결 + `docs/assets` 증거 링크
3) Threat Model 1~2p 작성 + “Claim → Evidence(Test)” 연결
