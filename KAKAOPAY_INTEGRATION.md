# 카카오페이 단건 Ready / Approve

최신 규격 대조표와 외부 호출을 하지 않는 개발용 격리 도구는 [KAKAOPAY_COMPATIBILITY.md](KAKAOPAY_COMPATIBILITY.md)를 따른다.

## 공식 규격과 구현 경계

2026-11-09 구현 직전에 다음 공식 문서를 확인했다. 이 문서의 내부 상태/보안 정책은 StayOn의 선택이며 PG 보장이 아니다.
- [공통](https://developers.kakaopay.com/docs/payment/online/common)
- [단건 결제](https://developers.kakaopay.com/docs/payment/online/single-payment)
- [주문 조회](https://developers.kakaopay.com/docs/payment/online/payment-detail)
- [참고](https://developers.kakaopay.com/docs/payment/online/reference)

신규 REST API의 Ready/Approve/Order 경로, JSON 전송, SECRET_KEY 인증 헤더, CID·TID·파트너 참조와 금액 필드를 사용한다.
TLS 1.2를 설정하며 응답 전체의 로컬 deadline은 기본 8초(설정 상한 10초)다. 공식 플랫폼 timeout과 별개의 로컬 제한이다.
PC/Mobile 웹 URL만 고객에게 반환한다. app 전용 URI, 정기 결제, cid_secret 옵션은 구현하지 않았다.
카카오페이 전체 취소 POST는 [취소 보상 처리 문서](KAKAOPAY_CANCEL.md)를 따른다. 실제 KakaoPay Webhook 인증 규격은 구현하지 않는다.

## 고객 API

1. 기존 POST /customer/orders/{orderId}/payment-attempts에 Idempotency-Key 및 {"provider":"KAKAOPAY"}를 보낸다.
   기존 JWT/Redis/예약/주문/owner/Provider/가맹점 검증 후 결제 시도 ID를 얻는다. 아직 PG Ready나 승인은 하지 않는다.
2. POST /customer/payment-attempts/{id}/ready: 본문 생략 또는 {}. 추가 필드는 400이다. orders:create가 필요하다.
   서버가 고정 가맹점, 현재 owner·활성 상태, 주문 총액·KRW와 Secret을 검증한다. CID/TID/금액/URL/세금은 고객에게 받지 않는다.
3. Ready 응답: id, preparationStatus, pcUrl, mobileUrl, expiresAt. PREPARED는 200, REQUESTING/UNKNOWN은 202이며 URL이 없다.
   TID, CID, Secret 참조, nonce hash, 내부 선점 값은 공개하지 않는다. Cache-Control: no-store를 적용한다.
4. 고객이 반환된 결제 URL을 연다. PG 인증 리다이렉트는 React의 설정된 HTTPS approval_url로 돌아온다.
5. React가 pg_token과 서버가 URL에 붙인 attempt/state를 읽고 기존 POST /customer/payment-attempts/{id}/approve로 전달한다.

승인 본문:
```json
{"authentication":{"version":"KAKAOPAY_V1","payload":{"state":"<callback state>","pg_token":"<ephemeral token>"}}}
```

승인 결과는 기존 DTO와 상태 코드를 따른다. APPROVED=200, APPROVING/UNKNOWN/CANCEL_PENDING=202, 금지 상태=409다.
현재 JWT·세션·연락처·scope·주문 소유권을 먼저 검증하며, 새 승인은 현재 예약 업무 정책과 owner/가맹점 활성 상태도 재검증한다.
승인 선점 이후에는 고객 로그아웃/예약 변경 때문에 실제 금융 결과 반영이나 내부 조회 복구를 버리지 않는다.
GET 고객 결제 상태/내역은 로컬 상태 조회다. 임의로 PG를 polling하는 고객 endpoint는 추가하지 않았다.

## 요청 데이터와 TID 고정

가맹점은 PgMerchant에 고정된 CID와 환경/Secret 참조를 사용한다. API 환경과 배포의 가맹점 환경이 같아야 한다.
실제 SANDBOX 전송은 문서의 TC0ONETIME만 허용한다. LIVE는 live-enabled=true가 추가로 필요하고 테스트 CID는 거부한다.
Secret은 기존 Resolver의 환경/owner/Provider grant로 분리한다. 문서에 없는 Secret 문자열 prefix를 추측하지 않는다.
Mock 전송만 합성 CID를 허용하며 test 단독 프로필의 127.0.0.1 HTTP 서버로 제한한다.
기존의 가맹점 식별자 전역 UNIQUE 정책은 유지한다. 공용 테스트 CID를 여러 owner가 공유하는 정책은 별도 계약이 필요하다.

partner_order_id는 결제 시도 UUID, partner_user_id는 별도로 생성해 저장한 익명 UUID다. 고객 이름·전화번호·회원 ID를 전송하지 않는다.
현재 PG 상품은 서버가 정한 주문 묶음 1개이며 item_name은 일반 추가서비스 주문 명칭이다. 실제 총액은 저장된 주문 스냅샷이다.
기존 상품 모델에 세금 구분이 없어 tax-mode를 UNCONFIGURED로 두고 Ready를 차단한다.
가맹점에서 ALL_TAXABLE 또는 ALL_TAX_FREE를 명시해야 한다. 혼합 과세는 후속 상품 세금 모델이 필요하다.
tax_free_amount를 Ready 시 고정하고 VAT는 선택 필드를 생략하여 PG 계산을 사용한다. 이는 가맹점의 세무 적합성을 보장하지 않는다.

TID는 Ready 성공 시 저장하고 (merchant_id,prepared_tid_hash) UNIQUE로 중복 연결을 막는다. 같은 시도에서 다른 TID로 바꿀 수 없다.
Approve와 조회는 저장된 TID·파트너 참조·세금·고정 CID를 사용한다. 승인/조회 결과의 CID, TID, 파트너 참조와 금액을 검증한다.
승인 후 pg_transaction_id에는 TID를 사용한다. AID는 승인/취소별 요청 식별자이므로 거래 키로 사용하지 않는다.
고객에게 TID를 받거나 다른 CID를 선택하게 하는 경로는 없다.

## 인증 컨텍스트와 Ready 상태

Ready 선점과 함께 256bit 난수의 hash, 고객 세션 ID hash, 만료 시각과 익명 PG 사용자 참조를 저장한다.
원본 state는 approval/cancel/fail URL의 query에만 전달하며 별도 평문 nonce 필드나 pg_token 필드를 저장하지 않는다.
인증 lifetime은 로컬 정책으로 기본 10분, 최대 15분이다. PG 자체 유효성 판정과 동일하다고 가정하지 않는다.
Ready 재응답은 원래 세션과 유효기간에 결합한다. 재로그인으로 새 세션이 생기면 예전 인증 컨텍스트를 임의로 이관하지 않는다.
승인 선점 트랜잭션 안에서 state/세션/만료를 확인하고 컨텍스트 소비 시각·금융 선점·주문 상태를 함께 저장한다.
중복 승인/늦은 리다이렉트는 현재 인증·소유권 확인 후 기존 결과를 반환하며 PG 승인을 다시 전송하지 않는다.
리다이렉트나 cancel_url/fail_url 방문 자체로 PAID/FAILED/CANCELLED를 만들지 않는다.

| 준비 상태 | 의미 / 반복 요청 |
| --- | --- |
| NONE | 아직 요청하지 않음. 사전 설정·Secret·DB 선점 실패 후 재시도 가능 |
| REQUESTING | 선점 커밋 후 Ready 진행. 동시 요청은 202, 추가 PG 요청 없음 |
| PREPARED | TID/URL 저장 완료. 유효한 원래 세션에 같은 URL 반환 |
| UNKNOWN | 타임아웃·HTTP 오류·잘못된 응답 등으로 결과 불명. 202, Ready 자동 재전송 없음 |

준비 상태 UNKNOWN과 금융 상태 UNKNOWN은 다르다. Ready만 진행한 주문은 PENDING_PAYMENT이며 아직 과금되지 않았다.
미결제 취소는 기존대로 가능하다. 준비 중 취소와 Ready 응답이 경합해도 승인할 수 없고, 수신한 TID는 감사용으로 보존한다.
인증 컨텍스트가 만료되면 409이며 새 컨텍스트/새 Ready를 같은 시도에서 자동 생성하지 않는다.

## 장애와 거래 조회

Ready와 모든 PG 호출은 DB 트랜잭션 밖에서 수행한다. 선점·TID 저장·승인 결과는 짧은 REQUIRES_NEW / READ_COMMITTED 단계다.
기존 예약 → 상품(필요 시) → 주문 → 라우팅 잠금(필요 시) → 결제 시도 → Inbox의 상대 순서를 유지한다.
동일 Ready 시도는 DB에서 하나만 선점한다. TID 저장 실패 시 같은 수신 결과를 DB에만 한 번 재적용한다.
두 DB 적용이 모두 실패하면 UNKNOWN을 가능한 범위에서 기록하고, DB가 계속 중단돼 있으면 REQUESTING 선점이 남는다.
서버가 TID를 저장하기 전 중단/응답 유실을 겪으면 공식 Order API에 필요한 TID가 없다.
문서에 없는 주문번호 기반 조회나 Ready 멱등성을 가정하지 않고, 자동 재전송·자동 승인 없이 운영자 확인 대상으로 남긴다.

승인 타임아웃/4xx/5xx/응답 검증 실패는 UNKNOWN이다. pg_token을 저장해 자동 재승인하지 않는다.
기존 PaymentRecoveryWorker/reconcileApproval이 저장된 TID로 Order API를 조회한다.
이 구현은 검증된 SUCCESS_PAYMENT만 APPROVED로 반영한다. 진행/인증 실패/승인 실패/부분 취소/알 수 없는 상태는 UNKNOWN이다.
이 상태 목록만으로 미래에도 승인되지 않는다는 최종 무과금 보장을 가정하지 않는다. 실패 확정·해제는 추가 PG 확인/운영 정책이 필요하다.
승인 중 취소 의도가 있으면 승인 성공 뒤 CANCEL_PENDING을 유지한다. 취소 Worker가 원래 승인 TID의 전체 금액 취소를 한 번 선점하여 전송한다.
취소 응답 또는 Order 조회의 CID/TID/파트너 참조/원금/세금과 전체 취소 증거가 모두 일치해야 로컬 주문·결제 상태를 함께 완료한다.
타임아웃·오류·결과 저장 실패 이후에는 취소 POST를 재전송하지 않고 거래 조회로 복구한다. 미확정 거래는 CANCEL_PENDING을 유지하며 재시도 소진 후 운영자 확인이 필요하다.

## React 연동 예시 (코드 변경 없음)

Ready 응답의 pcUrl/mobileUrl로 이동한다. 서버 설정 callback 주소와 카카오페이 앱의 등록 도메인이 일치해야 한다.
callback query 유지, 실제 모바일 전환, pg_token 수명은 실 PG 환경에서 추가 확인해야 한다.
콜백 페이지는 분석/접근 로그를 남기기 전에 query를 제거해야 한다. 로그/스토리지/분석 도구에 토큰을 전달하지 않는다.
프런트와 ingress는 callback query logging 차단, Referrer-Policy: no-referrer와 캐시 차단을 설정해야 한다.

```js
// 문서 예시만 제공한다. React 프로젝트 파일은 변경하지 않았다.
const callback = new URL(window.location.href);
const attempt = callback.searchParams.get('attempt');
const state = callback.searchParams.get('state');
const pgToken = callback.searchParams.get('pg_token');
history.replaceState(null, '', callback.pathname);
if (!attempt || !state || !pgToken) throw new Error('Invalid payment callback');
const response = await fetch(`/customer/payment-attempts/${encodeURIComponent(attempt)}/approve`, {
  method: 'POST', cache: 'no-store',
  headers: { Authorization: `Bearer ${customerAccessToken}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ authentication: {
    version: 'KAKAOPAY_V1', payload: { state, pg_token: pgToken }
  }})
});
// 202이면 로컬 상태를 조회하고 대기 UX를 표시한다. 무조건 approve를 다시 호출하지 않는다.
```

## 실제 격리 테스트에 필요한 설정

- 명시적 실제 PG 호출 승인과 별도 테스트 가맹점/예약/주문/owner 데이터.
- payment.kakaopay.enabled=true, mock-transport=false, 공식 API origin. LIVE는 이번 작업에서 실행하지 않았다.
- payment.merchant.environment=SANDBOX 및 KAKAOPAY Provider 활성 규칙과 owner별 PgMerchant(CID=TC0ONETIME).
- SANDBOX Secret key(dev)를 해당 owner/Provider/환경에만 해결하는 신뢰된 PgSecretResolver 구현. 실제 키 값은 소스/DB/문서에 넣지 않는다.
- 허용된 HTTPS approval/cancel/fail URL, callback-origins와 PG redirect-hosts. PG 개발자센터 Web 도메인 등록.
- 가맹점이 확인한 tax-mode. 실제 운영 과세/정산 계약과 일치하는지 별도 검증.
- 복구 Worker를 사용할 경우 payment.recovery.enabled를 테스트 환경에서 명시적으로 활성화. 기존 기본값 false는 유지했다.

공식 문서로 요청/응답 규격을 확인했고, 실제 HTTP 동작·오류·동시성은 loopback Mock HTTP 서버와 H2/독립 MySQL에서 검증한다.
카카오페이 테스트 서버 자체도 호출하지 않았다. 실제 인증, 키/CID 발급·권한, TLS/방화벽, 모바일 전환, PG 정산 계약을 검증한 것은 아니다.
정산 가능 여부를 코드로 보장하지 않는다. Cancel 전송 코드는 Mock으로 검증하며 실제 PG 취소는 실행하지 않았다. settled 거래 대사, 카카오페이 전용 Webhook 규격/Verifier, 외부 Secret Manager는 미구현이다.
KAKAOPAY_READY_MIGRATION.sql은 검토용 증분 DDL일 뿐이며 기존 SQL/운영 DB/배포 설정은 변경하지 않았다.

## 변경 파일

추가: config/KakaoPayProperties.java; service/KakaoPayUrls.java, KakaoPayHttpClient.java, KakaoPayAdapter.java, KakaoPayReadyService.java;
dto/customer/CustomerPaymentReadyRequest.java, CustomerPaymentReadyResponse.java.
수정: entity/PaymentAttempt.java; service/PgAdapter.java, PaymentTransactions.java, PaymentOrchestrator.java; controller/CustomerPaymentController.java.
위 경로는 src/main/java/com/stayon/stayon_backend 기준이다.
테스트 추가: service/MockKakaoPayServer.java, KakaoPayFlowTests.java, MySqlKakaoPayFlowTests.java, KakaoPayConfigurationTests.java (src/test/java/com/stayon/stayon_backend 기준).
문서 추가: KAKAOPAY_INTEGRATION.md, KAKAOPAY_READY_MIGRATION.sql. 기존 결제·API·가맹점·보안 문서는 새 계약 링크를 추가했다.
