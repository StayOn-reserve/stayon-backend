# PG Webhook Inbox와 Provider 정책

다중 가맹점 도입 후 현재 수신 인증/고정 가맹점/이벤트 UNIQUE 계약은 PG_MERCHANT_ROUTING.md를 우선 따른다.
Provider 단독 가맹점 추론은 허용하지 않으며, Inbox 키는 (provider,merchant_id,event_key_hash)다.

## 수신 경계

POST /payments/webhooks/{provider}는 고객/관리자 JWT 대신 등록된 PgWebhookVerifier로 원본 바이트를 인증한다.
전용 SecurityFilterChain은 이 POST 경로만 허용하며 고객 SecurityFilterChain은 변경하지 않는다.
검증 인터페이스가 없으면 404, 잘못된 인증은 401, 잘못된 검증 결과는 400, 본문 64KiB 초과는 413이다.
Verifier는 서명/인증, 타임스탬프·재전송 규칙, 키 회전, 가맹점·수신 대상 결합과 정규 참조 추출을 책임진다.
실제 네이버페이/카카오페이의 헤더·알고리즘·인증 절차를 구현하거나 추정하지 않았다.
FakeWebhookVerifier의 HMAC 및 event|reference|claim 형식은 테스트 전용이며 실제 PG 규격이 아니다.

Verifier는 인증한 eventId와 정규 소문자 UUID attemptId만 반환한다. 이벤트의 성공/실패 주장은 금융 결과가 아니다.
원문/인증 헤더/서명/고객 정보는 저장하거나 로그에 출력하지 않는다. body_hash는 첫 수신 원문 SHA-256 감사용이다.
인증 이후 독립 REQUIRES_NEW / READ_COMMITTED 트랜잭션의 저장/커밋이 완료된 경우만 200을 반환한다.
DB 실패/커밋 불확실성은 503이며 PG가 같은 이벤트로 재전송할 수 있다. 처리 Worker 완료까지 기다리지 않는다.

## DB 제약과 멱등성

PgWebhookInbox는 UUID PK, provider, event_key_hash, attempt_id, body_hash, 상태, lease, 시도 횟수,
next_at, 실패 코드, 수신/수정/완료 시각과 @Version을 보유한다.
(provider,event_key_hash) UNIQUE이며 SHA-256은 원래 이벤트 ID의 대소문자를 보존한다.
같은 이벤트·참조는 200 중복 응답이며 새 작업을 만들지 않는다. 같은 이벤트가 다른 참조로 바뀌면 409다.
상태 주장 등 본문이 바뀌어도 인증된 이벤트·참조가 같으면 기존 작업을 반환하고 첫 body_hash를 보존한다.
금융 결과를 본문에서 읽지 않으므로 변경된 주장을 금융 상태에 반영하지 않는다.
병렬 INSERT 유일 제약 패자는 실패 트랜잭션 롤백 후 별도 트랜잭션으로 저장된 행을 확인한다.
그 조회가 실패하면 성공 응답을 보내지 않는다. @Version과 lease CHECK도 적용한다.

attempt_id는 FK가 아니다. 알림이 로컬 참조보다 먼저 도착해도 인증된 작업을 보존하기 위해서다.
존재하지 않는 참조는 재시도 후 FAILED/운영자 확인 대상으로 남으며, 금융 반영 때 반드시 실제 Provider와 참조를 재검증한다.
원문 재처리는 제공하지 않는다. 실제 PG가 안정적인 eventId/merchant reference를 제공하는지 확인해야 한다.
다른 식별 체계라면 Verifier에 검증된 참조 매핑을 추가해야 하며 추측한 ID를 사용해서는 안 된다.

## Worker와 원자적 금융 반영

상태는 RECEIVED → PROCESSING → DONE 또는 RETRY → PROCESSING이며 한도 초과 시 FAILED다.
최초 수신의 next_at은 NULL(즉시 처리)이며 재시도부터 지연 시각을 기록한다. Instant 나노초와
DB 마이크로초 반올림 차이로 새 작업이 일시적으로 조회에서 빠지는 것을 방지한다.
정렬은 coalesce(next_at,received_at), received_at, id로 하여 새 작업이 기존 재시도를 무조건 앞서지 않게 한다.
Inbox 단독 선점 트랜잭션이 lease UUID·기간·시도 횟수를 먼저 커밋한다. 이 트랜잭션은 결제 행을 잠그지 않는다.
그 뒤 PaymentOrchestrator.observeWebhook이 현재 로컬 시도를 조회하고 DB 트랜잭션 밖에서 PG 결과를 조회한다.
승인 claim이 있으면 승인 조회, 취소 claim이 있으면 취소 조회를 사용한다. Webhook Worker는 승인/취소 금융 요청을 보내지 않는다.
READY/승인 전 취소처럼 금융 claim이 없으면 이벤트만으로 승인 선점이나 PAID 전이를 만들지 않는다.
조회 UNKNOWN/타임아웃은 확정 실패가 아니며 재시도한다. 조회 금액·통화·거래 ID 불일치와 금지 전이는 충돌로 기록한다.

최종 적용의 잠금 순서는 예약 → 필요한 상품 → 주문 → 결제 시도 → Inbox다. 이 경로는 상품이 필요 없어 건너뛴다.
PaymentTransactions의 한 REQUIRES_NEW / READ_COMMITTED 트랜잭션 안에서 Inbox lease를 다시 검증하고,
기존 결제 상태 전이 로직과 DONE/RETRY를 함께 커밋한다. 한쪽 flush/커밋이 실패하면 둘 다 롤백된다.
Inbox 잠금을 보유한 채 예약/주문/시도 잠금을 추가로 얻는 경로는 없다.
승인 선점·주문 취소·복구 Worker와도 동일한 부모 잠금을 사용하므로 취소 의도나 최종 상태를 덮어쓰지 않는다.

정상 lease에서 같은 이벤트를 처리하는 Worker는 하나다. 만료 후 새 Worker가 회수하면 이전 token의 결과 쓰기/완료/재시도는 차단한다.
서버 정지/긴 GC 후 조회가 겹치거나 서로 다른 이벤트가 같은 시도를 조회할 수는 있다. 금융 반영은 부모 잠금과 멱등 전이로 직렬화한다.
기존 PaymentRecoveryWorker와 조회가 겹쳐도 금융 RPC를 재전송하지 않는다.
승인 조회 후 CANCEL_PENDING이면 저장된 취소 의도를 기존 복구 Worker가 후속 처리한다.

기본 payment.webhook.enabled=false이며 명시적 true에서만 스케줄러를 시작한다.
기본 설정: delay-ms=5000, batch-size=20, max-attempts=8, lease=60s, base-backoff=5s, max-backoff=15m.
UNKNOWN/일시 실패는 지수 백오프를 적용하며 예외 원문 대신 제한된 실패 코드만 저장한다.
PG 네트워크 deadline은 lease보다 짧아야 한다. 만료되거나 교체된 lease 결과는 반영하지 않는다.
마지막 선점 직후 중단되어 한도를 소진한 PROCESSING 행도 만료 후 FAILED로 정리한다.
DB가 쓰기 불가능하면 재시도 기록도 실패할 수 있으므로 기존 PROCESSING/lease가 복구 근거다.
재시작은 시도 횟수를 초기화하지 않는다. FAILED 이벤트의 자동 무제한 재전송/금융 요청은 없다.

## Provider 검증

신규 결제 시도 및 READY의 승인 선점은 주문 잠금 안에서 PaymentProviderPolicy를 검증한다.
payment.providers.rules.{PROVIDER} 설정이 없으면 미지원 400, enabled=false면 503,
등록된 Adapter가 없으면 503, 주문 Site·통화·금액 제한에 맞지 않으면 409다.
기본 규칙은 enabled=false, allow-all-sites=false, site-ids=[], currencies=[KRW], minimum-amount=1, maximum-amount=2147483647이다.
실제 운영 Provider는 기본 등록/활성화하지 않았다. 모든 Provider는 명시적인 설정과 Adapter가 필요하다.
기존 미지원 READY 데이터도 새 승인 선점은 거부한다. 기존 시도 멱등 재응답/읽기는 현재 고객 인증 후 허용한다.
이미 발생한 금융 거래는 Provider 신규 사용을 비활성화해도 Webhook/조회 복구를 계속 받아야 한다.
따라서 enabled는 신규 금융 시작을 제한하며 기존 거래 조회/서명 검증을 차단하지 않는다.
기존의 미등록 Provider READY 저장 허용 테스트는 새 거부 정책을 검증하도록 변경했고 나머지 시나리오는 유지했다.

FakePgAdapter/FakeWebhookVerifier는 src/test에만 있고 Bean도 @Profile(test)로 제한한다.
PgRuntimeGuard는 testOnly 구성 요소와 TEST_/FAKE_ 예약 이름을 test 단독 활성 프로필 밖에서 등록하면 시작을 실패시킨다.
test+prod처럼 혼합한 프로필도 거부한다. 실제 구현을 testOnly로 위장하지 않는 것은 신뢰된 서버 코드의 책임이다.

## 적용 범위와 다음 단계

PG_WEBHOOK_MIGRATION.sql은 새 테이블의 검토용 MySQL DDL이며 기존/운영 DB에는 실행하지 않았다.
테스트만 H2 및 OwnedMySql의 새 데이터 디렉터리·임의 loopback 포트 MySQL 8 인스턴스를 사용한다.
재시작 검증은 DB 유지 상태에서 Worker 재생성/lease 만료로 모사하며 실제 JVM 강제 종료/COMMIT TCP 단절 시험은 아니다.
실제 PG 서명 검증/네트워크 Adapter, 정산 원장 대사, 운영자 경보·FAILED 재처리 API, 보존/삭제 정책,
외부 취소·부분 환불 정책과 실서비스 ingress 제한은 다음 단계다. 실제 결제/환불/SMS는 호출하지 않았다.
