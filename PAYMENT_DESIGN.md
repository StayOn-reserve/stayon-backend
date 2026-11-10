# 결제 승인·취소 기반

이 문서는 PaymentAttempt / PaymentTransactions / PaymentOrchestrator의 현재 계약이다.
다중 사업자 가맹점/Secret/고정 라우팅의 현재 계약은 PG_MERCHANT_ROUTING.md를 함께 따른다.
카카오페이 Ready/인증/승인/조회와 준비 상태·복구 제한은 KAKAOPAY_INTEGRATION.md를 따른다. 실제 PG 호출은 수행하지 않았다.
카카오페이 전체 취소 보상의 전송·응답 검증·조회 복구와 공식 문서 확인 한계는 [KAKAOPAY_CANCEL.md](KAKAOPAY_CANCEL.md)를 따른다. 일반 PAID 주문의 고객 환불 정책은 추가하지 않는다.
실제 PG Adapter는 구현하지 않는다. Webhook Inbox/인증 인터페이스는 PG_WEBHOOK_INBOX.md를 따른다. 고객 결제 HTTP API와 미완료 거래의 자동 조회 복구는
후속 단계에서 구현했으며 현재 계약은 CUSTOMER_PAYMENT_API.md를 함께 따른다.
기존 Payment 엔티티는 사용처가 없고 별도 테이블이다. 변경·이관·이중 기록하지 않았으며 기존 데이터가
있다면 실제 연동 전에 별도의 이관/대사 결정을 해야 한다.

## 상태 책임과 전이

주문은 고객에게 보이는 업무 진행 상태이며 결제 시도는 특정 PG 요청의 금융 결과다.
주문에 PAYMENT_PROCESSING / CANCEL_PENDING만 추가하고 기존 상태의 의미와 미결제 취소 정책을 유지한다.
REFUND_PENDING은 일반 PAID 주문의 환불을 구현하지 않으므로 추가하지 않는다.

| 현재 주문 | 사건 | 다음 주문 |
| --- | --- | --- |
| PENDING_PAYMENT | 승인 선점 커밋 | PAYMENT_PROCESSING |
| PENDING_PAYMENT | 기존 예약 정책을 통과한 미결제 취소 | CANCELLED |
| PAYMENT_PROCESSING | 취소 의도 접수 | CANCEL_PENDING |
| PAYMENT_PROCESSING | 승인 성공 / 확정 미승인 | PAID / PENDING_PAYMENT |
| PAYMENT_PROCESSING | 결과 불명 | 유지 |
| CANCEL_PENDING | 승인 성공 | 유지, PG 취소 작업 대기 |
| CANCEL_PENDING | 확정 미승인 / PG 취소 성공 | CANCELLED |
| CANCEL_PENDING | 승인 또는 취소 결과 불명 | 유지 |
| CANCELLED | 같은 취소 / 같은 금융 결과 재전달 | 유지 |
| PAID, EXPIRED, REFUNDED | 고객 취소 | 409, 변경 없음 |

표 밖의 전이는 거부한다. 기존 EXPIRED / REFUNDED 값은 보존하지만 새로운 만료/환불 경로는 만들지 않는다.
주문 CANCEL_PENDING은 도메인 자체에도 취소 의도를 보존한다. 승인 반영자가 false 플래그를 전달해도
이를 PAID로 덮어쓸 수 없다. 주문 금액·상품 스냅샷·생성 멱등키는 결제로 변경하지 않는다.

| 현재 시도 | 사건 | 다음 시도 |
| --- | --- | --- |
| READY | 승인 선점 / PG 호출 전 미결제 취소 | APPROVING / CANCELED |
| APPROVING | 승인 성공 | APPROVED, 취소 의도가 있으면 CANCEL_PENDING |
| APPROVING | 확정 미승인 / 응답 불명·타임아웃 | FAILED / UNKNOWN |
| UNKNOWN | 조회/검증된 결과 | APPROVED, CANCEL_PENDING 또는 FAILED |
| APPROVING, UNKNOWN | 고객 취소 요청 | 현재 상태 유지, cancel_requested_at 저장 |
| CANCEL_PENDING | PG 전체 승인 취소 성공 | CANCELED |
| CANCEL_PENDING | 취소 결과 불명 | 상태 유지, cancel_unknown=true |
| APPROVED, FAILED, CANCELED | 동일 결과 / 지연된 UNKNOWN | 역전이 없이 유지 |

APPROVED 이후 새로운 고객 취소는 PAID 정책에 따라 거부한다. 승인 전에 영속화한 취소 의도가 있는
경우에만 성공 결과를 CANCEL_PENDING으로 저장한다. 확정 실패 뒤 모순되는 성공, 다른 거래 ID,
다른 선점 토큰, 금액/통화/provider/참조 불일치는 409이며 결과를 덮어쓰지 않는다.
전송 예외와 타임아웃은 FAILED가 아니다. Adapter의 DECLINED는 앞으로도 승인되지 않을
**권위 있고 최종적인 미승인/무과금**일 때만 사용해야 한다. 단순 NOT_FOUND도 보통 UNKNOWN이다.

## PaymentAttempt 데이터와 DB 불변 조건

- UUID 시도 ID는 PG merchant reference 및 복구 키다. 주문 FK는 생성 후 불변이다.
- provider는 대문자 ASCII 식별자다. NAVER_PAY/KAKAO_PAY 등 Adapter로 확장하며 실구현은 없다.
- amount는 주문 스냅샷에서 읽은 long 정수이고 currency는 현재 KRW다. 부동소수점/클라이언트 가격을 쓰지 않는다.
  0원도 모델상 허용하며 실제 PG의 최소 금액·무상 주문 처리는 연동 전 별도 확정이 필요하다.
- 주문+멱등키 SHA-256 UNIQUE로 같은 키는 기존 시도를 반환한다. provider가 다르면 409다.
- active_order_id UNIQUE는 READY부터 금융 결과 불명·승인·취소 처리 동안 주문 슬롯을 점유한다.
  FAILED 및 승인 전 CANCELED만 슬롯을 해제한다. 승인 이후 PG 취소된 시도는 슬롯을 계속 보존하여
  동일 주문의 재청구를 DB에서도 막는다. 여러 시도는 확정 실패 후 새로운 키로 만들 수 있다.
- approved_order_id UNIQUE는 주문당 승인 성공 이력을 1개로 제한하고 PG 취소 후에도 보존한다.
- (provider,pg_transaction_hash) UNIQUE로 PG 거래 ID를 중복 매핑하지 않는다. 거래 ID 원문과 SHA-256을
  함께 저장하며 해시는 MySQL의 대소문자 비구분 collation에 의존하지 않는다. 승인 전에는 둘 다 NULL이다.
- CHECK는 슬롯과 실제 order_id 일치, 상태별 슬롯 NULL 여부, 승인 이력과 PG ID의 결합 및 금액>=0을 강제한다.
- @Version은 잘못된 무잠금 작성자의 오래된 UPDATE도 차단한다. 기본 동시성 제어는 행 잠금이다.
- 승인·취소 선점 UUID, 시작·완료·요청·생성·수정 시각을 저장한다. 선점 UUID는 PG 인증 토큰이 아니다.
  카드 정보·PG 인증 코드·고객 인증 토큰을 저장하거나 로그에 출력하지 않는다.

View/Claim은 내부 조율 계약이며 고객 응답 DTO가 아니다. 향후 HTTP API에서는 선점 값 등을 그대로
직렬화하지 말아야 한다. 기존 취소 HTTP API는 원래 주문 스냅샷 DTO만 사용한다.

## 승인·취소 경쟁의 선형화

승인 선점과 고객 취소는 같은 예약/주문 잠금을 사용한다. 고객 취소가 먼저 READY를 취소하면 PG 호출은 없다.
승인이 먼저 선점하면 취소 의도와 CANCEL_PENDING 주문을 원자 저장하고 202를 반환한다.
승인 성공 DB 반영이 먼저 PAID로 커밋되면 뒤따르는 고객 취소는 기존대로 409다.
취소 의도가 먼저 커밋되면 승인 성공이 도착해도 PAID가 되지 않고 PG 취소 대상으로 남는다.
UNKNOWN이면 취소를 완료하지 않는다. 확정 미승인이 확인되면 PG 취소 없이 CANCELLED로 완료한다.

권한은 멱등 응답보다 먼저 검증한다. 고객 createAttempt/approve는 현재 세션·연락처·orders:create와
주문 소유 범위를 검증한다. 실제 신규 선점에는 기존 예약 CREATE_ORDER 업무 조건도 적용한다.
기존 취소는 orders:cancel을 요구하고 PENDING_PAYMENT의 예약 취소/체크아웃 제한(403)을 유지한다.
금융 진행 중 취소 및 취소 멱등 재응답은 예약이 읽기 전용이 되어도 허용하지만 현재 세션·연락처·scope는 요구한다.
서버의 검증된 PG 결과 반영/조회 복구는 고객 JWT 만료나 로그아웃 때문에 실제 금융 결과를 버리지 않는다.
이 내부 메서드를 외부 Webhook에 직접 노출해서는 안 된다.

## 트랜잭션과 PG 호출

전역 잠금 계층은 예약 → 필요한 상품 ID 오름차순 → 주문 → PaymentAttempt다.
결제는 상품을 바꾸지 않으므로 예약 → 주문 → 시도만 잠근다. 참조로 경로를 찾는 최초 조회는 비잠금이고,
관련 행을 순서대로 잠근 후 상태·금액·소유 범위를 검사한다.

- PaymentTransactions의 독립 DB 단계는 TransactionTemplate REQUIRES_NEW / READ_COMMITTED / 10초다.
- 기존 CustomerOrderService.cancel 안의 requestCancellationLocked만 MANDATORY로 기존 트랜잭션에 참여한다.
  중첩 REQUIRES_NEW를 열어 이미 잡은 주문 잠금과 자기 교착을 만들지 않는다.
- 승인 선점/취소 작업 선점 → 커밋 → PG 호출 → 새 DB 트랜잭션에서 결과 반영 순서다.
- PaymentOrchestrator의 PG 메서드는 이미 열린 외부 DB 트랜잭션도 거부한다. 자신의 짧은 단계만
  닫아 놓고 호출자의 트랜잭션/잠금을 계속 유지하는 실수를 막는다.
- self-invocation에 트랜잭션을 기대하지 않는다. 별도 Bean의 명시적 TransactionTemplate과 MANDATORY 경계를 사용한다.
- 주문·시도 변경은 같은 DB 단계에서 원자 커밋한다. PG 승인/취소와 MySQL 커밋은 단일 원자 작업이 아니다.
- DB 잠금·제약·커밋 실패는 503으로 처리하며 금융 RPC를 자동 재전송하지 않는다. 업무 충돌은 409다.

실제 MySQL 테스트는 기존 OwnedMySql의 새 독립 MySQL 8.0.37 인스턴스를 사용한다. H2와 구분하고
기존 서버·운영 설정·스키마에 접속/마이그레이션하지 않는다. 새 운영용 SQL은 PAYMENT_STATE_MIGRATION.sql의
검토용 파일일 뿐 실행하지 않는다. JPA의 현재 create-drop 설정을 운영 데이터 이행 방식으로 사용해서는 안 된다.

## 비동기 취소와 장애 복구

PaymentAttempt의 CANCEL_PENDING 행이 영속 작업이다. PaymentCancellationWorker.drain은
미선점 행을 읽고 개별 DB 선점 후 PG를 호출한다. 여러 worker가 읽어도 선점자는 1개다.
기본 스케줄러는 비활성이다. Adapter를 구성하고 payment.cancellation-worker.enabled=true로 설정하면
Spring 스케줄러가 기본 5초 간격, 20개 배치로 처리한다. 고객 취소 API 안에서는 drain/PG를 호출하지 않는다.
이는 모놀리스의 MySQL 폴링이며 Kafka/RabbitMQ를 도입하지 않는다.

| 장애 지점 | 영속 상태 | 복구 계약 |
| --- | --- | --- |
| 선점 커밋 전 실패 | READY | PG 호출 없음, 같은 시도로 재시도 가능 |
| 선점 커밋 후 서버 중단 / 승인 응답 유실 | APPROVING 또는 UNKNOWN, 슬롯 유지 | 동일 merchant reference로 PG 조회 |
| 승인 성공 후 결과 DB 반영 실패 | APPROVING/UNKNOWN, 기존 선점 유지 | 새 Orchestrator에서 reconcileApproval |
| 승인 취소 응답 유실 / 결과 DB 실패 | CANCEL_PENDING와 cancelClaim 유지 | reconcileCancellation으로 PG 조회 |
| DB 커밋 성공 후 고객 응답 유실 | 확정 상태 | 같은 참조/주문의 재요청은 기존 결과 |

approve 중복 호출은 APPROVING/UNKNOWN에서 PG를 다시 호출하지 않는다. cancelClaim이 있으면
후속 worker도 PG 취소를 다시 전송하지 않는다. 선점 UUID·작업 키는 저장되어 재시작 후에도 동일하다.
PG operationKey는 시도 UUID + :approve / :cancel로 고정된다. 실제 Adapter도 이 키에 대한 멱등성을 제공해야 한다.
DB의 APPROVING/UNKNOWN/CANCEL_PENDING 목록은 상태·updated_at 인덱스로 조회할 수 있다.
reconcileApproval/reconcileCancellation은 수동/내부 worker용 조회 복구 진입점이다.
신규 PaymentRecoveryWorker가 DB lease·백오프를 사용하여 이를 자동 호출한다. 기본 실행은 비활성이다.
조회로 확정되지 않으면 UNKNOWN/대기 상태를 유지하고 새 시도나 최종 취소를 허용하지 않는다.
선점만 남고 PG에 요청이 안 갔는지 알 수 없는 작업도 임의로 재전송하지 않는다. 실제 PG의 종결 보장,
멱등 재전송 또는 운영자 확인 정책을 정의해야 한다. 자동 lease 만료 후 재승인 기능은 없다.

서버 재시작 테스트는 DB 상태를 유지한 채 Orchestrator 객체를 새로 만드는 시뮬레이션이다.
실제 JVM 강제 종료/COMMIT TCP 단절/실제 PG 네트워크 장애 시험을 수행했다고 주장하지 않는다.

## 다음 PG 단계의 미구현 계약

- 실제 NAVER_PAY/KAKAO_PAY 승인·조회·전체 취소 Adapter와 안전한 SDK 예외 매핑.
- 실제 공급자의 서명 검증 구현과 인증/이벤트 식별 규격 확인. Inbox와 검증 인터페이스는 구현했다.
- Webhook 전용 결과 적용은 부모/시도 잠금 뒤 Inbox lease를 검증하고 DONE/RETRY와 금융 상태를 원자 커밋한다.
  기존 단독 승인/취소/복구 결과 적용의 독립 트랜잭션 경계도 유지한다.
- 외부 정산 원장 대사·운영자 경보·안전한 금융 요청 재전송 정책. 현재 미완료 거래 조회 복구와 구분한다.
- 실제 PG 인증 UX와 인증 정보의 공급자별 검증, 무료 주문/금액·통화 정책. 고객 HTTP DTO는 별도 문서 참조.
- 일반 PAID 주문의 취소/환불·부분 취소·정산 원장, 기존 Payment 데이터의 이행. 이번 단계에서 실제 환불은 없다.
