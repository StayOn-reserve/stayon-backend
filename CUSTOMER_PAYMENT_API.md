# 고객 결제 HTTP 계약과 자동 복구

KAKAOPAY의 추가 Ready API와 KAKAOPAY_V1 브라우저 인증 계약은 KAKAOPAY_INTEGRATION.md를 따른다.
기존 API와 Fake Provider 계약을 유지하며 실제 카카오페이 리다이렉트는 Webhook으로 취급하지 않는다.

## API

모든 경로는 고객 JWT와 현재 Redis 세션, 예약 연락처 HMAC과 주문 소유 범위를 검증한다.
관리자 JWT는 사용할 수 없다. 응답에는 Cache-Control: no-store를 설정하며 Entity를 반환하지 않는다.
금액·통화·예약 ID·주문 상태·PG 거래 ID를 요청에서 받지 않는다. 알 수 없는 JSON 필드도 400이다.

| 경로 | scope | 요청 | 응답 |
| --- | --- | --- | --- |
| POST /customer/orders/{orderId}/payment-attempts | orders:create | Idempotency-Key, {"provider":"NAVER_PAY"} | 새 시도 201, 동일 키·provider 재요청 200, 다른 provider 409 |
| POST /customer/payment-attempts/{id}/approve | orders:create | 본문 생략/{} 또는 authentication | 승인 완료 200, 진행/UNKNOWN/취소 대기 202, FAILED/CANCELED 409 |
| GET /customer/payment-attempts/{id} | orders:read | 없음 | 현재 상태 200 |
| GET /customer/orders/{orderId}/payment-attempts?page=0&size=20 | orders:read | page>=0, size 1..100 | 생성 시각 내림차순, ID 내림차순 페이지 200 |

공개 시도 DTO는 id, orderId, provider, amount, currency, status, cancelRequested만 포함한다.
페이지 DTO는 content, page, size, totalElements, totalPages다. PG 거래 ID, 승인·취소 선점 값,
멱등키 해시, 인증 자료, 복구 오류/선점 정보는 공개하지 않는다.
신규 시도와 실제 승인 선점에는 기존 CREATE_ORDER 예약 업무 정책을 적용한다.
동일 시도의 결과 재조회/재승인 응답은 현재 인증·소유권 검증을 먼저 하고 새 금융 요청을 보내지 않는다.
읽기 API는 예약 취소/체크아웃/Site 미연결 이후에도 인증된 주문 범위에서 허용한다.
미인증/세션 불일치 401, scope 부족 403, 다른 예약 리소스 404, 업무 충돌 409,
Redis/일시적 DB 장애 및 승인 Adapter 미설정은 503이다.

신규 시도와 승인 선점은 지원·활성화 설정, 등록된 Adapter 및 주문별 Site/통화/금액 조건을 검증한다.
추가로 실제 Site → Item → Accommodation → owner 경로에서 PG 가맹점을 결정해 고정한다. 고객 가맹점 ID는 받지 않는다.
소유 경로/활성 가맹점/통화가 없으면 409이며, 과거 거래는 고정된 가맹점으로 조회·취소·복구한다. PG_MERCHANT_ROUTING.md 참조.
미지원 Provider는 400, 비활성/Adapter 미등록은 503, 주문 조건 불일치는 409이며 READY로 저장하지 않는다.
기존 시도 재응답/읽기와 이미 발생한 거래의 조회 복구는 보존한다. 현재 Provider 계약은 PG_WEBHOOK_INBOX.md를 따른다.

## 브라우저 인증 확장

승인 요청 예시는 {"authentication":{"version":"provider-v1","payload":{"opaqueCode":"..."}}}다.
이는 PG 승인 증명이 아니라 Adapter가 검증해야 하는 일시적 입력이다. 금액·provider·거래 ID는
이 payload를 통해 덮어쓸 수 없다. Adapter는 validateAuthentication과 approve(Command, BrowserAuthentication)을
함께 구현하고, 본인의 계약·위변조·주문/금액 결합·일회성을 검증해야 한다.
현재 기본 Adapter 계약은 빈 NONE 인증만 허용한다. 테스트 Fake의 TEST_V1만 확장 검증에 사용한다.
버전 32자, payload 16개, 키 64자, 값 2048자, 전체 값 8192자로 제한한다.
인증 값은 PaymentAttempt/작업/로그/응답에 저장하지 않는다. DTO와 내부 인증 객체의 toString도 마스킹한다.
승인 작업의 멱등 식별자는 생성된 시도 UUID다. 같은 UUID에 다른 인증 자료를 보내도 재승인하지 않는다.
재시작 후 인증 자료를 복구해 승인 요청을 재전송하지 않으며 merchant reference로 조회한다.

## 복구 Worker

PaymentRecoveryWorker는 APPROVING, UNKNOWN, CANCEL_PENDING을 페이지 단위로 탐색한다.
스캔은 후보일 뿐이며 예약 → 주문 → 결제 시도를 잠근 REQUIRES_NEW / READ_COMMITTED 단계에서
현재 상태·백오프·시도 한도·lease를 재검사하고 UUID lease와 시도 횟수를 커밋한다.
네트워크 호출 중에는 DB 트랜잭션/행 잠금이 없다. 결과 반영 시 같은 lease와 유효 기간을 다시 검증한다.
lease가 바뀌거나 만료된 이전 Worker는 결과를 반영하거나 새 Worker의 선점을 해제하지 못한다.

| 대상 | 작업 |
| --- | --- |
| APPROVING/UNKNOWN | reconcileApproval: PG 조회만 수행 |
| CANCEL_PENDING, cancelClaim 없음 | 영속 취소 의도에 대한 최초 PG 취소 선점·전송 |
| CANCEL_PENDING, cancelClaim 있음 | reconcileCancellation: PG 취소 결과 조회만 수행 |

승인 조회 성공 후 취소 의도가 있으면 취소 작업을 다음 배치에서 처리한다. 승인 복구 예산은 이때
취소 처리용으로 새로 시작한다. 불명확한 거래를 FAILED/CANCELLED로 확정하지 않는다.
PG 전송에 대한 승인/취소 claim은 복구 lease와 다르며 lease 만료로 금융 전송 claim을 해제하지 않는다.
따라서 서버가 최초 취소 선점 직후 중단되어 실제로 전송하지 못했더라도 이후에는 조회한다.
단순 NOT_FOUND는 UNKNOWN이며, 안전한 재전송 보장이 없는 PG에는 운영자 개입이 필요할 수 있다.

기본 설정은 payment.recovery.enabled=false다. 활성화한 테스트 스케줄러 배선과 직접 drain 호출을
각각 검증한다. enabled=true일 때 기본 5초 간격, 배치 20개로 실행한다.
payment.recovery 설정: batch-size=20, max-attempts=8, lease=60s, initial-grace=60s,
base-backoff=5s, max-backoff=15m, delay-ms=5000.
기존 payment.cancellation-worker.enabled도 기본 false를 유지한다. 기존 폴러는 최초 취소만 처리하며
신규 복구 Worker가 최초 취소와 조회를 모두 포함하므로 신규 구성에서는 복구 스케줄러 사용을 권장한다.

실패/UNKNOWN에는 5, 10, 20초 식의 지수 백오프를 적용하고 15분으로 제한한다.
recovery_attempts, recovery_next_at, recovery_failure, recovery_failed_at와 lease가 DB에 남는다.
한도에 도달한 행은 자동 선점을 중단하고 EXHAUSTED_* 코드와 금융 대기 상태를 유지한다.
마지막 선점 직후 프로세스가 종료된 경우에는 오류 코드 없이 시도 한도와 만료 lease만 남을 수도 있다.
DB가 쓰기 불가능하면 실패 코드 저장도 불가능하므로 기존 lease 만료 이후 재개한다.
재시작이 횟수나 백오프를 초기화하지 않으며, 한도 초과 거래는 명시적 조회 대사/운영자 확인 대상이다.
예외 원문·PG 응답·인증 값은 복구 실패 코드에 넣지 않는다.

정상 lease 동안 같은 시도의 Worker PG 작업은 1개다. 프로세스 정지/긴 GC로 lease가 만료되면
외부 조회가 겹칠 가능성까지 제거할 수는 없다. 이전 결과 쓰기는 fencing으로 차단하며 금융 요청은
별도 영구 claim으로 중복 전송하지 않는다. 실제 Adapter의 네트워크 deadline은 lease보다 짧게 설정해야 한다.
예약 단위 잠금이므로 같은 예약의 다른 주문 처리도 직렬화될 수 있다.

## 적용과 미구현

운영 DB/서버에는 접근하거나 스키마를 변경하지 않는다. PAYMENT_RECOVERY_MIGRATION.sql은
기존 PaymentAttempt 스키마 이후 필요한 증분 SQL의 검토용 파일이다. 기존 DB에 적용하지 않았다.
통합 테스트는 H2와 독립 OwnedMySql/MySQL 8 InnoDB에서 구분해 실행한다.
재시작 검증은 DB를 유지한 Worker/Orchestrator 재생성과 lease 만료를 이용한 시뮬레이션이며
실제 JVM 강제 종료나 COMMIT TCP 단절 시험은 아니다.

Webhook Inbox와 서명 검증 인터페이스는 PG_WEBHOOK_INBOX.md의 후속 단계에서 구현했다.
실제 PG Adapter/서명 구현, PAID 환불·부분 취소, 외부 정산 원장 대사,
운영자 경보/복구 관리 API는 구현하지 않았다. 현재 Worker 대사는 로컬 거래의 PG 결과 조회다.
공급자별 인증 UX·리다이렉트 검증·금액/통화 최소 한도·무료 주문·안전한 최종 미승인 판정은
실제 PG 단계에서 확정해야 한다. 기본 비활성 정책과 기존 주문/미결제 취소 정책은 유지한다.
