# 사업자 소유 PG 가맹점과 결제 라우팅

KAKAOPAY Adapter는 KAKAOPAY_INTEGRATION.md의 CID/환경/Ready/TID 고정 규칙을 추가로 적용한다.
실제 Secret Resolver/PG 연동은 별도 승인이 필요한 후속 단계이며 기존 가맹점 소유 경계는 유지한다.

## 확인한 도메인과 소유 경계

실제 관계는 ServiceOrder → Reservation → Site → AccommodationItem → Accommodation → User(owner)다.
Site는 AccommodationItem을 여러 개 공유할 수 있고, 한 Accommodation은 여러 Item을 가지며,
같은 User는 여러 Accommodation의 owner가 될 수 있으므로 한 owner의 여러 Site를 지원한다.
Site.accommodationItem은 NULL일 수 있으므로 연결 없는 예약을 가맹점에 임의 매핑하지 않는다.
별도의 법인/사업자 Entity는 없다. User.businessNumber는 선택 필드이고 여기서 법적 사업자 검증을 가정하지 않는다.
이번 모델은 기존 Accommodation.owner의 User ID를 기술적 사업자 경계로 사용한다.
법인 단위 소유·공동 정산·대리 가맹점이 필요하면 별도 계약과 도메인 확장이 필요하다.

## PgMerchant

UUID 내부 ID, 소유 User FK, provider, SANDBOX/LIVE 환경, PG 식별자와 SHA-256 해시,
활성 상태와 active_owner_id 슬롯, 허용 통화 Set, Secret 참조, 생성/수정 시각과 @Version을 저장한다.
금액/통화는 기존 주문/결제 시도 스냅샷을 따른다. Secret 값 자체는 DB에 저장하지 않는다.

- (provider,environment,external_id_hash) UNIQUE: 같은 PG 식별자를 다른 owner에게 중복 등록하지 않는다.
  실제 PG에서 공유 MID/하위 가맹점이 필요한지는 미확정이므로 현재는 보수적으로 거부한다.
- (provider,environment,active_owner_id) UNIQUE: owner별 Provider/환경에 활성 가맹점은 하나다.
- CHECK는 활성 슬롯과 owner 일치 및 비활성 슬롯 NULL을 강제한다.
- owner/provider/환경/PG 식별자/Secret 참조와 통화는 공개 수정 경로가 없다. 교체는 새 가맹점 행으로 한다.
  기존 가맹점은 비활성화하고 보존한다. 삭제는 과거 PaymentAttempt FK가 제한한다.
- 등록 중복은 DB 유일 제약 충돌이다. 라우팅 조회가 0개 또는 2개 이상이면 409로 거부하고 임의 선택하지 않는다.

관리자 등록/소유 확인 HTTP API는 추가하지 않았다. Entity/Repository와 활성 상태 변경은 신뢰된 서버 provisioning용이다.
고객은 가맹점 ID를 제출할 수 없다. 향후 관리 API는 인증된 관리자와 owner 소유권을 별도로 검증해야 한다.

## 선택과 고정

신규 PaymentAttempt는 기존 고객 인증·예약 정책·Provider 정책을 통과한 뒤 현재 소유 경로를 서버에서 잠그고 조회한다.
payment.merchant.environment의 기본값은 LIVE이며, 테스트는 명시적으로 SANDBOX를 사용한다.
owner/provider/환경의 활성 가맹점이 정확히 하나이고 해당 통화를 허용해야 한다. 없거나 비활성이면 INSERT하지 않는다.
PaymentAttempt의 merchant_id는 생성 시 고정되고 변경할 수 없다. 기존 멱등 재응답은 재라우팅하지 않는다.
(merchant_id,provider) 복합 FK가 다른 Provider 가맹점 연결을 막는다.
PG 거래 ID UNIQUE는 provider 전체가 아니라 (merchant_id,pg_transaction_hash)로 범위를 제한한다.
서로 다른 가맹점의 같은 거래 ID는 허용하되 같은 가맹점에서는 중복 결제 매핑을 막는다.

READY의 새 승인 시작은 현재 owner가 고정 owner와 같은지, 고정 가맹점이 활성인지/환경·통화가 맞는지 다시 검증한다.
Site 소유 이전이나 비활성화로 부적합해졌으면 409이고 다른 가맹점으로 조용히 바꾸지 않는다.
이미 선점한 승인 결과, 기존 거래 조회·취소·복구·Webhook은 현재 활성 설정/Site 소속으로 재라우팅하지 않는다.
따라서 기존 가맹점을 비활성화하고 새 MID를 등록해도 진행 중 거래는 과거 MID와 Secret 참조를 계속 사용한다.
주문 자체에는 기존 사업자 스냅샷이 없으므로 최초 결제 시도의 소유 경계는 당시 잠근 현재 Site 경로다.

기존 상대 잠금 순서는 유지한다: 예약 → 필요한 상품 → 주문 → [Site → Item → Accommodation → PgMerchant] → 결제 시도 → Inbox.
신규 라우팅/READY 선점만 대괄호 구간을 사용하며, 기존 거래 결과 반영은 고정된 불변 문맥으로 처리한다.
소유 경로와 가맹점은 PESSIMISTIC_READ 잠금과 refresh로 현재 값을 읽는다. 이전 요청 단계의 영속성 컨텍스트 값을 신뢰하지 않는다.
가맹점 활성 변경은 가맹점 행만 잠그고 나서 예약/주문을 얻어서는 안 된다. 교체는 이전 행 비활성 flush 후 새 행을 등록한다.
REQUIRES_NEW / READ_COMMITTED 및 Inbox lease/금융 결과 원자 커밋은 유지한다.

## Secret 경계와 Adapter

PgMerchantContext는 owner/provider/환경/내부 ID/MID/Secret 참조를 담는 내부 불변 문맥이며 고객 DTO가 아니다.
PgSecrets는 문맥이 DB의 불변 정보와 정확히 같은지, 배포 환경과 맞는지를 확인한 뒤 PgSecretResolver를 호출한다.
다른 owner/provider/참조를 조합한 문맥은 거부한다. Resolver 구현도 같은 범위의 allowlist/IAM을 적용해야 한다.
외부 Secret Manager 구현과 환경변수 Secret 값 fallback은 제공하지 않았다. Resolver가 없으면 503으로 실패한다.
Resolver와 모든 PG 네트워크 호출은 DB 트랜잭션 밖에서 수행한다. Secret 장애는 금융 승인/취소 claim 전에 발견하도록 한다.
PgSecret은 toString을 마스킹하고 close 시 소유 char 버퍼를 지운다. 실제 Adapter가 만든 추가 복사본도 직접 폐기해야 한다.
자격 증명·인증 자료를 Command, Entity, Inbox, 고객 응답, 실패 로그에 저장하지 않는다.
참조 alias의 외부 값 교체는 Resolver에서 구현할 수 있지만 동일 MID의 Secret 참조 자체를 변경하는 관리 기능은 이번에 구현하지 않았다.

Adapter의 승인·취소·조회 경계는 Command의 고정 merchant 문맥과 단기 Secret을 받는다.
merchant-aware 메서드를 구현하지 않은 Adapter는 성공 결과로 fallback하지 않는다.
확정 승인/취소 결과는 PG 가맹점 식별자를 반환해야 하며 고정 MID와 불일치/누락이면 상태 반영을 거부한다.
금액·통화·거래 ID 검증과 기존 상태 전이도 함께 적용한다. UNKNOWN은 금융 성공으로 간주하지 않는다.
테스트 Fake Adapter/Resolver/Verifier만 구현했고 test 단독 프로필 밖에서는 등록을 거부한다.

## 다중 가맹점 Webhook

기존 POST /payments/webhooks/{provider} 경로는 유지하지만 provider만으로 가맹점을 고르지 않는다.
Verifier.merchantHint가 원문/헤더에서 후보를 추출한다. 이 값은 자격 증명을 찾기 위한 비신뢰 힌트일 뿐이다.
후보의 고정 owner/provider/환경 문맥으로 Secret을 해결하고 merchant-aware verify를 호출한다.
Verifier는 실제 PG 규격에 따라 서명과 인증된 MID/수신 가맹점 결합을 검증하고 내부 merchant ID 및 선택적 PG 거래 ID를 반환한다.
실제 PG가 내부 ID를 전달하지 않는 경우 PG별 공개 수신 alias/MID 매핑이 필요하며 이번에는 규격을 추측하지 않았다.

인증된 merchant는 알려진 PaymentAttempt의 고정 merchant와 일치해야 하고, 이미 알려진 PG 거래 ID도 일치해야 한다.
다른 가맹점의 정상 서명으로 타 사업자의 참조를 제출해도 401로 거부한다.
참조가 아직 없으면 인증된 merchant와 거래 증거를 보존하고, Worker가 로컬 참조 등장 후 다시 검증한다.
Inbox UNIQUE는 (provider,merchant_id,event_key_hash)로 확장되어 서로 다른 가맹점의 같은 event ID를 허용한다.
적용 시 가맹점/PG 거래 ID/lease를 재검증하며 금융 상태와 Inbox 완료를 한 트랜잭션으로 반영한다.
서명/본문 주장만으로 PAID가 되는 경로는 없다. 기존 두 Worker와 승인·취소 경쟁 처리도 유지한다.

## 이행과 미구현

기존 SQL은 변경하지 않았고 PG_MERCHANT_MIGRATION.sql에 검토용 증분 DDL과 이행 중단 지점을 제공했다.
기존 PaymentAttempt/Inbox의 merchant는 현재 활성 설정으로 추측해 자동 보정하지 않는다.
결제/수신/Worker를 중지한 관리 절차에서 실제 과거 PG 자료로 매핑·검증 후 새 제약을 적용해야 한다.
매핑되지 않은 legacy 시도는 고객 조회·멱등 응답만 가능하고 금융 호출은 503으로 차단한다.
새 코드의 Inbox merchant는 필수이므로 NULL legacy Inbox가 남은 상태로 Worker를 활성화해서는 안 된다.

운영 DB/서버/기존 데이터/배포 설정은 변경하지 않았다. 실제 PG 호출·결제·환불·Secret Manager 연동은 없다.
독립 H2 및 OwnedMySql MySQL 8 InnoDB에서 유일 제약과 경쟁을 검증한다. 실제 운영 마이그레이션 검증은 수행하지 않는다.
실제 PG의 MID 정규화·하위 가맹점·정산/소유 이전·거래 번호 범위·Secret 회전/폐기·Webhook 수신 alias는 후속 계약이다.
가맹점 관리 API, 법적 사업자 인증, 환경별 물리 DB/Secret IAM 및 운영자 복구 도구는 다음 단계다.

## 이번 단계 변경 파일

추가한 운영 코드:
- entity/PgMerchant.java
- repository/PgMerchantRepository.java
- config/PgMerchantProperties.java
- service/PgMerchantContext.java
- service/PgMerchantRouting.java
- service/PgSecretResolver.java
- service/PgSecret.java
- service/PgSecrets.java

수정한 운영 코드 (위 경로는 src/main/java/com/stayon/stayon_backend 기준):
- entity/PaymentAttempt.java
- entity/PgWebhookInbox.java
- repository/PgWebhookInboxRepository.java
- service/PaymentTransactions.java
- service/PaymentOrchestrator.java
- service/PgAdapter.java
- service/PgWebhookVerifier.java
- service/PgWebhookService.java
- service/PgWebhookTransactions.java
- service/PgWebhookWorker.java

테스트 (src/test/java/com/stayon/stayon_backend/service 기준):
- 추가: FakePgSecretResolver.java, PgMerchantRoutingTests.java, MySqlPgMerchantRoutingTests.java, PgMerchantSecurityTests.java
- 수정: PaymentFlowTests.java, MySqlPaymentFlowTests.java, CustomerJwtTestProperties.java, FakePgAdapter.java, FakeWebhookVerifier.java, PgWebhookInboxTests.java

문서/검토용 SQL:
- 추가: PG_MERCHANT_ROUTING.md, PG_MERCHANT_MIGRATION.sql
- 수정: PAYMENT_DESIGN.md, CUSTOMER_PAYMENT_API.md, PG_WEBHOOK_INBOX.md, CUSTOMER_SECURITY.md
