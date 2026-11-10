# KakaoPay 전체 취소 및 보상 처리

## 범위와 확인 근거

일반 PAID 주문의 고객 환불 API와 부분 취소는 제공하지 않는다. 기존 승인 중 고객 취소 의도에 대한 전체 취소 보상만 연결한다.
Worker 기본 비활성, 고객 인증·인가, 고정 가맹점, 기존 취소 200/202/409 계약을 유지한다. 신규 Entity/DDL/마이그레이션은 없다.

2026-11-09 다음 공식 출처를 확인했다.

- [결제 취소](https://developers.kakaopay.com/docs/payment/online/cancellation): 공식 메뉴가 가리키는 취소 문서. 이번 도구에서는 페이지 본문 대신 탐색 메뉴만 로드되어 최신 요청 필수/선택 표 전체를 재확인하지 못했다.
- [주문 조회](https://developers.kakaopay.com/docs/payment/online/payment-detail): CID/TID 조회, 금액·누적 취소·취소 가능 금액, 상태 및 거래 상세. CANCEL_PAYMENT는 여러 부분 취소의 합계로 전액 취소된 경우도 포함한다.
- [공통](https://developers.kakaopay.com/docs/payment/online/common), [응답 규격](https://developers.kakaopay.com/docs/payment/online/reference): JSON, SECRET_KEY 인증, 오류 응답 및 타임아웃.
- 공식 개발자센터 AI 연동 문서가 연결한 [공식 Toolkit 취소 구현](https://github.com/kakaopay-develop/agent-toolkit/blob/main/typescript/src/shared/payment/cancelPayment.ts)과 [HTTP Client](https://github.com/kakaopay-develop/agent-toolkit/blob/main/typescript/src/shared/client.ts): POST 경로, JSON/SECRET_KEY 및 아래 네 가지 전송 필드를 교차 확인했다. 라이브러리를 프로젝트에 도입하거나 실행하지 않았다.
- [카카오페이 담당자의 승인 완료 전 취소 불가 확인](https://developers.kakaopay.com/forum/t/api/566), [담당자의 전체 취소 요청·응답 및 중복 취소 거절 확인](https://developers.kakaopay.com/forum/t/api/376): 아래 최소 요청/응답 필드의 1차 보완 근거. 포럼의 사용자 인증 정보는 복사하거나 사용하지 않았다.

취소 문서 본문의 전체 선택 필드·오류 분류 확인 및 실제 SANDBOX 호환성 검증은 배포 전 필수다. 본 구현을 실제 PG 계약 검증 완료로 해석하지 않는다. 문서에 없는 요청 멱등성·에러 코드별 확정 무과금 보장은 가정하지 않는다.

## 외부 API 계약

POST https://open-api.kakaopay.com/online/v1/payment/cancel

기존 HttpClient의 JSON, SECRET_KEY 헤더, TLS 1.2, 리다이렉트 금지, 응답 크기 제한, 전체 응답 deadline을 그대로 적용한다.
전송 본문은 서버의 고정 데이터만 사용한다.

| 필드 | 값 |
| --- | --- |
| cid | PaymentAttempt에 고정된 PgMerchant.externalId |
| tid | 저장된 준비 TID와 승인 pg_transaction_id가 일치하는 값 |
| cancel_amount | 원래 승인 금액 전체 |
| cancel_tax_free_amount | Ready에 고정한 비과세 금액 전체 |

VAT는 기존 Ready와 동일하게 별도 금액을 지정하지 않는다. 현재 과세 모델은 ALL_TAXABLE / ALL_TAX_FREE뿐이다. 혼합 과세, 수동 VAT, 컵 보증금, cid_secret 등의 추가 계약은 지원하지 않는다.
부분 취소 금액과 cancel_available_amount 옵션의 동작을 추측하여 사용하지 않는다. 브라우저 cancel_url은 이 API와 별개이며 금융 취소 증거가 아니다.

취소 응답은 원래 cid/tid/partner_order_id/partner_user_id/amount.total/amount.tax_free를 검증한다.
아래 조건을 모두 만족해야 CANCELED 결과를 만든다.

- status = CANCEL_PAYMENT, approved_at/canceled_at 및 요청 AID 존재
- approved_cancel_amount.total = 원금, tax_free = 원래 비과세 금액
- canceled_amount.total = 원금, tax_free = 원래 비과세 금액
- cancel_available_amount.total = 0, tax_free = 0

AID는 작업 식별자이며 원래 거래 TID를 대체하지 않는다. 별도 AID 컬럼은 추가하지 않았고, 고정 가맹점·TID와 로컬 취소 claim을 연결하여 조회 복구한다.
Order 조회에는 이번 요청 금액/AID 조건을 요구하지 않고 동일 거래의 누적 전액 취소 증거를 검증한다. 부분 취소 상태, 금액/참조 불일치, 증거 누락은 최종 취소가 아니다.

## 상태·트랜잭션·잠금

| 상황 | 주문 | 결제 시도 | 동작 |
| --- | --- | --- | --- |
| 승인 진행/UNKNOWN 중 고객 취소 | CANCEL_PENDING | APPROVING/UNKNOWN + 취소 의도 | PG 취소 전송 금지, 승인 조회 |
| 이후 승인 확정 | CANCEL_PENDING | CANCEL_PENDING | 취소 전송 대상 |
| 취소 선점 | CANCEL_PENDING | CANCEL_PENDING + cancelClaim | 선점 DB 커밋 후 PG 호출 |
| 전액 취소 확인 | CANCELLED | CANCELED | 하나의 DB 트랜잭션으로 완료 |
| 취소 오류/타임아웃/응답 검증 실패 | CANCEL_PENDING | CANCEL_PENDING + cancelUnknown | 취소 의도·claim 유지, 조회만 재시도 |
| 취소 결과 DB 저장 실패 | CANCEL_PENDING | CANCEL_PENDING + 기존 claim | 재시작 후 동일 TID 조회로 복구 |
| PAID 주문 고객 취소 | PAID | APPROVED | 기존 409 유지 |
| 이미 취소 완료 | CANCELLED | CANCELED | 스냅샷 200, 금융 요청 없음 |

기존 PaymentTransactions의 REQUIRES_NEW / READ_COMMITTED / 10초 경계를 변경하지 않는다.
예약 → 필요한 상품(필요 시) → 주문 → 결제 시도 → Inbox 순서를 유지한다. 취소에는 상품 잠금이 필요 없다.
선점/결과 적용은 독립된 짧은 트랜잭션이고 PG/Secret 호출 중 DB 트랜잭션과 행 잠금을 유지하지 않는다.
결과 저장 실패가 외부 취소를 되돌리지 못하므로 선점 claim을 지우지 않는다. 주문과 결제 상태의 부분 커밋은 허용하지 않는다.

## 멱등성·lease·복구

영속 cancelClaim은 금융 전송 선점이며 한 번만 발급된다. 여러 CancellationWorker와 RecoveryWorker가 경쟁해도 재발급하지 않는다.
복구 lease는 조회 작업과 결과 적용을 보호하는 별도 토큰이다. 만료된 결과는 DB 적용과 lease 완료 모두 거부된다.
lease 만료/프로세스 중단이 금융 claim을 해제하지 않는다. 재시작 후 CANCEL_LOOKUP을 선택한다.
전송 전 프로세스 중단으로 실제 취소가 전송되지 않은 경우에도 안전을 위해 자동 재전송하지 않는다.

HTTP 4xx/5xx, 연결 오류, timeout, 잘못된 JSON 및 참조 불일치는 승인되지 않았거나 취소되지 않았다는 확정 증거가 아니다.
원래 TID로 Order API를 조회하고 전액 취소를 확인할 때만 완료한다. SUCCESS_PAYMENT가 계속 보여도 자동 재취소하지 않는다.
조회 재시도는 기존 지수 백오프(기본 5초~15분), 기본 최대 8회 및 lease 정책을 사용한다.
예산 소진 시 recovery_failure=EXHAUSTED_*, recovery_failed_at을 남기고 취소 의도/금융 claim/거래 참조를 보존한다.
운영자는 PG 거래 상세와 내부 기록을 대조해야 하며, 자동 claim 초기화나 무조건 재전송 기능은 제공하지 않는다.
Secret 실패는 금융 claim 이전에 실패할 수 있으므로 자격 증명 복원 후 선점 재시도가 가능하다.

## 가맹점과 보안

현재 활성 라우트를 다시 선택하지 않고 과거 PaymentAttempt의 owner/Provider/환경/CID/Secret 참조를 사용한다.
비활성화된 가맹점도 과거 거래 취소/조회가 가능하다. SANDBOX/LIVE 환경 불일치, 승인 TID와 준비 TID 불일치는 HTTP 호출 전에 차단한다.
실제 전송의 SANDBOX 테스트 CID와 LIVE CID 분리, Secret Resolver의 owner/Provider/환경 grant 검증을 유지한다.
클라이언트는 취소 금액·CID·TID를 지정할 수 없으며 토큰/Secret/원문 PG 오류를 로그나 고객 응답에 추가하지 않는다.

## 검증과 실제 연동 전 남은 작업

로컬 Mock HTTP 서버가 금융 ledger를 모사한다. 실제 카카오페이 서버, CID 자격 증명, 네트워크 또는 정산 결과를 검증한 것은 아니다.
독립 MySQL 테스트는 기존 OwnedMySql의 신규 datadir/랜덤 loopback 포트/--no-defaults 인스턴스를 사용하며 기존 서버나 운영 데이터에 연결하지 않는다.
회귀 및 신규 테스트 실행 수는 최종 실행 보고서에 기록한다.

실제 테스트는 별도의 명시적 승인 후 격리 SANDBOX에서 수행해야 한다. 테스트 CID/Secret Resolver 권한, 공식 취소 규격 본문, 취소 가능 기간·한도·세무 처리, 응답 필드 및 거래 조회 지연을 확인한다.
payment.kakaopay.enabled 및 Provider/가맹점 설정 외에 Worker는 payment.cancellation-worker.enabled / payment.recovery.enabled를 명시적으로 켜야 한다. 기본값은 변경하지 않는다.
수동 대사 절차·알림, 장기 미해결 거래 처리, AID 감사 기록 확장, 부분 취소, 일반 PAID 주문 환불 정책은 후속 작업이다.

## 실행 결과 (2026-11-09)

JAVA_HOME=C:\Users\Kevin\.jdks\corretto-21.0.6, GRADLE_USER_HOME=C:\dev\stayon-backend\.gradle-codex-home을 각 Gradle 실행 프로세스에 설정했다.

```powershell
.\gradlew.bat compileJava test --tests '*KakaoPayFlowTests' --offline --no-daemon --rerun-tasks --no-build-cache
.\gradlew.bat compileJava test --offline --no-daemon --rerun-tasks --no-build-cache
```

| 실행 범위 | 테스트 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| KakaoPayFlowTests (H2 + 로컬 Mock HTTP) | 164 | 0 | 0 | 0 |
| MySqlKakaoPayFlowTests (실제 MySQL + 로컬 Mock HTTP) | 169 | 0 | 0 | 0 |
| 전체 MySQL 테스트 7개 클래스 합계 | 540 | 0 | 0 | 0 |
| 전체 JUnit | 1427 | 0 | 0 | 0 |

신규 실행 케이스는 75개(공통 36개를 H2/MySQL 각각 실행 + MySQL 전용 3개)다. 기존 1352개는 유지하며, 기존 KakaoPay 취소 미구현 기대 테스트 한 건의 기대값을 구현된 취소 완료로 갱신했다.
전체 명령은 BUILD SUCCESSFUL(3분 53초)이며 기존 deprecated/unchecked 컴파일 경고가 남아 있다.
실행 로그는 build/kakao-cancel-full.log, JUnit XML은 build/test-results/test, HTML은 build/reports/tests/test/index.html이다.

MySQL 8.0.37/InnoDB에서 다음을 실제로 검증했다.

- 8개 병렬 취소 Worker와 병렬 복구 Worker/기존 취소 Worker 경쟁에서 취소 HTTP 전송 한 번
- 취소 HTTP 대기 중 예약·주문·가맹점·결제 시도 행에 다른 연결의 SELECT FOR UPDATE 성공
- 실제 InnoDB Lock Wait Timeout 발생 시 503 및 취소 전송/부분 변경 없음
- PG 취소 성공 이후 실제 데드락 발생, INNODB_METRICS의 lock_deadlocks 증가 확인, 로컬 주문·결제 롤백 및 Order 조회만으로 완료
- 취소 결과 DB 롤백, 만료 lease 결과 쓰기 차단, 새로운 Orchestrator 인스턴스를 통한 영속 거래 조회 복구

이번 전체 실행의 독립 MySQL 인스턴스 7개 모두 Shutdown complete를 확인했다. 외부 PG 금융 호출, 운영 데이터/스키마 변경 및 배포는 수행하지 않았다.
