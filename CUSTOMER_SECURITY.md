# 예약 고객 Security 경계

## 실제 Redis 통합 테스트

`RedisCustomerAuthIntegrationTests`는 테스트 전용 Redis의 `127.0.0.1:6379`에만 직접 연결한다.
Redis 7 이상을 요구하며 현재 사용자가 지정한 서버는 Redis 8.0.5이다. Redis 7에서의 검증과 구분한다.
Spring 애플리케이션 컨텍스트, 운영 연결 환경변수, MySQL 및 SMS 발송을 사용하지 않는다.
Redis에 연결할 수 없거나 7 미만 버전이면 테스트가 실패하며 자동으로 건너뛰지 않는다.

각 테스트는 `test:customer:{UUID}:` 접두사를 생성한다. 운영 저장소 생성자의 접두사는 그대로 유지하고,
테스트는 package-private 생성자로 독립 접두사를 전달한다. 종료 시 해당 접두사만 SCAN하여 삭제한다.
FLUSHDB, FLUSHALL, SCRIPT FLUSH 및 서버 중지 명령은 사용하지 않는다.

OTP 180초, 세션 900초, 발급·실패 제한 3600/86400초의 실제 TTL을 확인한다.
만료 동작 테스트에서는 자체 키의 TTL만 150ms로 줄인 뒤 Redis의 실제 만료를 기다린다.
재발급 테스트에서는 자체 cooldown 키만 제거하고, 제한 경계 테스트에서는 자체 카운터만 설정한다.
전체 180/900초 대기, 시간당·일별 창의 전체 경과를 검증하는 테스트는 아니다.
16개 병렬 소비 요청과 Lua 스냅샷 관찰로 단일 성공 및 OTP 삭제·세션 생성의 일관성을 검증한다.
장애 테스트는 별도로 예약한 비수신 loopback TCP 포트를 사용하며 공유 Redis를 중단하지 않는다.


## 설정

고객 검증기는 `customer.jwt` ConfigurationProperties에 바인딩한다.
운영 비밀값은 파일에 저장하지 않고 다음 환경변수로 주입한다.

- `CUSTOMER_JWT_SECRET`: 관리자 키와 다른 충분한 난수 HMAC 키(UTF-8 최소 32바이트)
- `CUSTOMER_JWT_ISSUER`: 고객 토큰 issuer
- `CUSTOMER_JWT_AUDIENCE`: 고객 API audience

기존 관리자 JWT 환경 설정은 유지한다. 고객 설정 누락, 짧은 키, 관리자와 같은 키는
애플리케이션 시작 시 거부한다. 테스트 값은 테스트 전용 어노테이션으로 주입한다.

## 검증 계약

관리자 Access Token에는 `token_use=access`, Refresh Token에는 `token_use=refresh`를 넣는다.
일반 API 필터는 Access Token만 인증하고 AuthService.refresh는 Refresh Token만 검증한 뒤
기존 Redis 저장값과 비교한다. 용도 없는 기존 토큰은 거부하므로 적용 시 재로그인이 필요하다.
관리자 Principal은 기존 컨트롤러와 호환되도록 Long userId를 유지한다.

고객 검증기 `CustomerJwtTokenProvider.parseAccessToken`은 다음을 검사한다.

- 별도 키의 서명, issuer, audience, 만료(exp 필수)
- `token_use=reservation_access`
- `reservation_id`: 양수 정수 JSON 숫자
- `session_id`: 표준 소문자 UUID 문자열, subject와 동일
- `scope`: 공백으로 구분한 비어 있지 않은 문자열. 허용 값은
  `orders:read`, `orders:create`, `orders:cancel`

성공하면 불변 scope 집합을 포함한 CustomerPrincipal을 반환한다.
검증된 scope는 Principal과 SCOPE_ authority에 보관한다. CustomerSessionService.requireActive의
requiredScopes로 작업별 scope를 검사한다. 필터는 매 요청마다 Redis 세션 존재·만료,
세션 ID·예약 ID 일치, JWT scope가 세션 scope의 부분집합인지를 검사한다.
실제 예약 소유권·현재 연락처·상태·주문 FK 검증은 이후 ReservationAccessPolicy의 책임이다.

## SecurityFilterChain

1. `/customer`, `/customer/**`: 고객 필터 및 CUSTOMER authority 필수.
   POST `/customer/auth/otp/challenges`, `/customer/auth/otp/verifications` 두 경로만 공개한다.
2. `/management`, `/management/**`, 기존 `/sites/*/additional-services` 및 하위 경로:
   관리자 필터 및 MANAGER 역할 필수. 기존 상품 서비스의 DB 역할·소유권 검사 유지.
3. 나머지: 기존 공개 API 허용 정책 유지. Bearer를 제공하면 관리자 Access Token으로 검증.

모든 체인은 stateless다. 관리자 필터의 서블릿 자동 등록을 비활성화했고 고객 필터는
고객 체인에만 직접 설치해 두 필터가 같은 요청에서 Principal을 덮어쓰지 않는다.
잘못된 Bearer는 401, 유효한 관리자 Access Token의 역할 부족은 403이다.

## OTP API와 세션

POST `/customer/auth/otp/challenges` 입력:

```json
{"platform":"CAMFIT","reservationNumber":"외부 예약번호","phoneNumber":"010-1234-5678"}
```

플랫폼과 예약번호를 함께 조회한다. 전화번호는 한국 010 휴대전화로 제한하고
`+82`, `0082`, 공백·괄호·하이픈 표기를 정규화한다. 누락·마스킹·알 수 없는 형식은 인증하지 않는다.
서버 조회 연락처와 일치할 때만 서버 등록 번호를 정규화해 SmsSender에 전달한다.
존재 여부·전화 불일치·대상별 제한·SMS 실패 모두 동일한 202와 challengeId/expiresIn을 반환한다.
IP/전체 요청 제한은 예약 조회 전에 적용하며 429다. 형식 오류는 400이다.

POST `/customer/auth/otp/verifications` 입력은 challengeId와 8자리 문자열 otp다.
현재 DB 연락처를 다시 확인하고 OTP 소비 및 세션 생성을 한 Lua 실행에서 처리한다.
성공 응답은 accessToken, tokenType=Bearer, expiresIn=900이다. 토큰은 세션 저장 성공 후에만 반환한다.
POST `/customer/auth/logout`은 세션을 폐기한다. 인증 응답에는 Cache-Control: no-store를 적용한다.
세션 누락·만료·불일치는 401, 필요한 scope 부족은 403, Redis 오류는 503이다.

## Redis 키 및 원자성

모든 키는 `customer:{customer-auth}:` 접두사를 사용한다. Cluster에서 하나의 슬롯에 두어
여러 대상과 전역 제한을 한 Lua 실행에서 처리한다. 이 슬롯은 처리량 병목이 될 수 있다.

| 접미사 | 내용 | TTL |
| --- | --- | --- |
| otp:{UUID} | 예약 ID, 번호 HMAC, 코드 HMAC, 상태, 시도 수 | 180초 |
| active:{예약 HMAC} | 현재 challenge 키 | 180초 |
| cool-r:{예약 HMAC}, cool-p:{번호 HMAC} | 재발급 간격 | 60초 |
| issue-r-hour/day, issue-p-hour/day:{HMAC} | 대상별 발급 수 | 3600/86400초 |
| issue-ip:{IP HMAC}, verify-ip:{IP HMAC} | IP별 요청 수 | 3600초 |
| issue-global-hour/day | 전체 발급 요청 수 | 3600/86400초 |
| fail-p:{번호 HMAC}, fail-r:{예약 HMAC} | 누적 검증 실패 | 3600초 |
| session:{UUID} | 세션 ID, 예약 ID, 번호 HMAC, scope, 절대 만료시각 | 900초 |

시간·일 제한은 첫 요청부터 시작하는 고정 TTL 창이다. 대상별 시간당 5회/일 10회이며,
챌린지당 검증 시도는 최대 5회다. 번호·예약별 실패도 5회/시간으로 제한하여 재발급으로
실패 제한을 초기화하지 못한다. IP/전역 제한은 잘못된 요청도 포함하여 발송 상한을 보수적으로 제한한다.
OTP는 SecureRandom 8자리이며 challenge ID에 결합한 HMAC-SHA256으로만 저장한다.
전화번호·예약 식별자·IP의 HMAC은 서로 다른 도메인을 사용한다. 원본 번호·OTP·JWT는 Redis에 저장하지 않는다.

BEGIN Lua는 제한 검사·기존 challenge 제거·새 PENDING challenge 생성을 원자 처리한다.
SmsSender 성공 뒤 현재 challenge에만 READY를 설정한다. 실패 시 폐기하며 cooldown과 예산은 복원하지 않는다.
자동 발송 재시도는 없다. 공급자 어댑터는 challenge ID를 멱등키로 사용해야 한다.
모호한 발송 실패나 READY 전환 Redis 오류에서는 전달된 코드도 인증에 사용할 수 없다.
CONSUME Lua는 시도 제한·코드 대조·세션 생성·OTP 삭제를 원자 처리한다.
네트워크 타임아웃 시 이미 소비되었을 가능성이 있으므로 무조건 재실행하거나 성공으로 간주하지 않는다.

현재 기본 SmsSender는 비활성 구현이며 실제 문자를 발송하지 않는다.
외부 제공자를 연결하기 전에는 실제 고객이 OTP를 받을 수 없다.

## OTP 환경변수와 운영 전제

Spring ConfigurationProperties 환경변수 바인딩 규칙(필드명 하이픈 제거)을 따른다.

- `CUSTOMER_OTP_HMACSECRET`: 필수, 고객 JWT 키와 다른 UTF-8 최소 32바이트 난수 키
- `CUSTOMER_OTP_ISSUEIPHOURLYLIMIT`: 기본 50
- `CUSTOMER_OTP_VERIFYIPHOURLYLIMIT`: 기본 100
- `CUSTOMER_OTP_GLOBALHOURLYLIMIT`: 기본 1000
- `CUSTOMER_OTP_GLOBALDAILYLIMIT`: 기본 10000

기존 CUSTOMER_JWT_SECRET/ISSUER/AUDIENCE와 Redis 연결 설정도 필요하다.
HMAC 키 변경은 기존 challenge/제한 키와 호환되지 않으므로 폐기·전환 정책을 함께 계획해야 한다.
IP는 HttpServletRequest.getRemoteAddr를 사용하며 임의 X-Forwarded-For를 직접 신뢰하지 않는다.
프록시 뒤에서는 신뢰할 프록시와 전달 헤더 처리 정책을 별도로 검토한다.
Redis는 인증 상태의 권위 저장소이므로 제한 키의 조기 eviction을 막고 ACL·TLS·지속성 정책을 확인해야 한다.

## 예약 접근 정책

`ReservationAccessPolicy`는 인증된 `Authentication`의 `CustomerPrincipal`만 허용한다.
`authorizeReservation(authentication, requestedReservationId, operation)`은 READ_RESERVATION/CREATE_ORDER,
`authorizeOrder(authentication, orderId, operation)`은 READ_ORDER/CANCEL_ORDER를 처리한다.
요청 예약 ID는 Principal의 내부 예약 PK와 일치해야 한다. 예약번호와 고객명은 권한 판단에 사용하지 않는다.

매 호출마다 `CustomerSessionService.requireActiveSession`으로 Redis 세션 ID·예약 ID·만료·scope를 재검증하고,
반환된 동일 세션의 연락처 HMAC을 현재 DB 예약 연락처의 정규화 후 `phone` 도메인 HMAC과 비교한다.
번호 변경·누락·마스킹은 401, scope/업무 정책 거부는 403이다. 예약 누락과 타 예약 주문은 404,
Redis/예약·주문 조회 오류는 상세 정보를 노출하지 않고 503으로 거부한다.
관리자 Principal은 403, 미인증 요청은 401이다.

| 작업 | 필요한 JWT scope | 업무 조건 |
| --- | --- | --- |
| READ_RESERVATION | orders:read | 본인 예약·세션·현재 연락처 일치 |
| READ_ORDER | orders:read | 위 조건 및 주문의 reservation.id 일치 |
| CREATE_ORDER | orders:create | RESERVED, 정확히 SUCCESS, 영속 Site 연결, 주문 가능 날짜 |
| CANCEL_ORDER | orders:cancel | 신규 취소는 예약 업무 조건 및 PENDING_PAYMENT, CANCELLED는 멱등 재응답 |

기존 JWT의 허용 scope를 유지하므로 예약 조회도 `orders:read`를 요구한다.
예약 상태는 실제 enum의 RESERVED/CANCELLED만 사용한다. 별도 CHECKED_OUT 상태를 만들지 않는다.
취소·체크아웃·체크인 전·Site 누락·동기화/날짜 불명 상태의 본인 예약/기존 주문은 조회만 허용하고
Context의 readOnly를 true로 표시한다. PAID/EXPIRED/REFUNDED 주문의 취소는 409다.
CANCELLED는 상태를 다시 변경하지 않고 멱등 취소 응답만 허용한다.

`ReservationOrderWindow` 설정 기본값은 숙소 기준 `customer.reservation.time-zone=Asia/Seoul`,
`customer.reservation.order-days-before-check-in=0`이다. 기본적으로 체크인 날짜부터 체크아웃 전날까지
주문과 미결제 취소를 허용한다. 선행 일수 설정은 시작일만 앞당기며 체크아웃 날짜는 항상 제외한다.
Clock과 ZoneId로 숙소의 자정을 계산하며 누락/역전/동일 입퇴실 날짜는 변경 작업을 거부한다.
Site별 시간대 컬럼은 현재 없으므로 이 설정은 전체 숙소의 공통 기준 시간대이다.

예약 조회는 Site를 LEFT JOIN FETCH하여 미연결 예약도 읽을 수 있다.
Site 연결의 최소 근거는 조회된 영속 FK와 실제 Site의 내부 PK이다. 외부 매핑을 추정하거나 자동 연결하지 않는다.
주문은 `findByIdAndReservation_Id`로 DB에서부터 범위를 제한하고 반환된 주문 ID·예약 ID도 다시 확인한다.
`AccessContext`는 ID·작업·readOnly만 담는 내부 결과이며 Entity/연락처를 반환하지 않는다.
readOnly=false는 모든 작업의 허가를 의미하지 않으며 매 작업에 해당 메서드를 다시 호출해야 한다.

고객 읽기·수량형 주문 생성·미결제 취소 API에서 이 정책을 호출한다.
생성·취소 서비스는 같은 업무 트랜잭션에서 정책과 잠금을 적용한다.
Context를 캐시하거나 별도 요청의 허가로 재사용하지 않는다.
MySQL과 Redis 사이에는 분산 트랜잭션이 없으므로 검증 직후 로그아웃·예약 변경 경쟁은 별도 검토가 필요하다.
H2 쿼리 검증은 운영 MySQL의 격리 수준·잠금·외부 예약 동기화 및 Site 매핑 출처의 검증을 대체하지 않는다.

## 고객 읽기 전용 API

세 API 모두 기존 고객 SecurityFilterChain과 CustomerJwtAuthenticationFilter를 거친다.
Controller는 서버의 Authentication을 받고 정책이 그 안의 CustomerPrincipal을 검증한다.
예약 ID·Site ID를 요청에서 받지 않으며, 조회 범위는 검증된 Principal의 내부 예약 PK로만 결정한다.
성공 응답은 Cache-Control: no-store이며 DTO만 반환한다. 관리자 JWT·누락/만료 JWT·세션은 401,
scope 부족·상품 목록의 예약 주문 불가 상태는 403, Redis/DB 조회 장애는 503이다.

| API | 정책 작업 / JWT scope | 응답 |
| --- | --- | --- |
| GET /customer/reservation | READ_RESERVATION / orders:read | status, checkInDate, checkOutDate, orderable |
| GET /customer/reservation/additional-services | CREATE_ORDER / orders:create | id, name, price의 배열 |
| GET /customer/orders | READ_ORDER / orders:read | content, page, size, totalElements, totalPages |

예약 응답의 orderable은 예약·Site·동기화·날짜 조건의 결과다. JWT의 CREATE_ORDER 권한이나
상품별 재고/슬롯 확보를 보장하지 않으며, 상품 조회/주문 생성 시 각각 다시 인가해야 한다.
예약번호·고객명·연락처·HMAC·세션 ID를 응답에 넣지 않는다.
정책의 ReservationContext는 검증했던 동일 예약의 상태와 날짜를 전달하여 별도 예약 재조회를 피한다.

상품은 검증된 Site의 활성 QUANTITY 상품만 ID 오름차순으로 조회하고 ServiceOrderPreparation의
공통 상품 적격 조건도 확인한다. DELIVERY_TIME은 슬롯 기능 구현 전까지 제외하며 비활성/다른 Site/
이름·가격 정보가 유효하지 않은 상품은 반환하지 않는다. 조회 가격은 현재 값이며 실제 생성 시
상품 상태·가격·선택 수량·소유 관계·총액을 다시 검증하고 주문 스냅샷으로 저장해야 한다.

주문 목록은 예약이 취소·체크아웃·Site 미연결 상태여도 본인 연락처·세션·scope가 유효하면 조회한다.
각 주문은 id, status, totalAmount, items를 가지며 항목은 id, serviceName, unitPrice, quantity, totalPrice다.
상품명과 금액은 ServiceOrderItem의 저장된 스냅샷만 사용하고 현재 AdditionalService를 참조하지 않는다.
과거 serviceName NULL은 JSON null로 유지하며 현재 상품명으로 보정하지 않는다.

page는 0부터, size 기본 20·허용 1~100이다. 음수 페이지·허용 범위 밖 크기·JPA offset 한도 초과는 400이다.
주문 ID 내림차순으로 고정 정렬하고 항목은 ID 오름차순이다. 임의 정렬 속성을 요청에서 받지 않는다.
예약 범위로 주문 페이지와 건수를 조회한 뒤 해당 예약/페이지 ID의 항목을 한 번에 FETCH한다.
컬렉션 FETCH JOIN에 직접 페이지네이션하지 않으며 두 번째 조회에서도 예약 조건을 유지한다.
페이지 밖 결과는 빈 content와 해당 예약의 전체 건수를 반환한다. 두 조회 사이 주문이 사라지면
불완전한 응답을 반환하지 않고 503으로 처리한다.

읽기 API는 주문 생성/취소/결제/슬롯 점유를 수행하지 않는다. 동시 신규 주문에 따라 offset 페이지의
경계가 바뀔 수 있고, 전체 건수와 항목의 읽기 일관성은 실제 MySQL 트랜잭션 격리 수준에서도 확인해야 한다.

## 수량형 주문 생성 API

POST `/customer/orders`는 고객 JWT와 CREATE_ORDER / `orders:create` 권한을 요구한다.
필수 헤더 `Idempotency-Key`는 1~128자의 영문·숫자·`.`·`_`·`:`·`-`만 허용한다.
본문은 다음 형태이며 items는 1~100개, productId는 양의 Long JSON 정수, quantity는 양의 int JSON 정수다.
중복 상품은 합치지 않고 400으로 거부한다. 예약 ID·이름·가격·합계·상태 등 알 수 없는 필드,
문자열 숫자·소수·정수 범위 초과도 400이다.

```json
{"items":[{"productId":1,"quantity":2}]}
```

새 주문은 201, 재전송은 200이며 응답은 기존 CustomerOrderResponse와 같은 저장된 스냅샷 DTO다.
Client가 가격·상태를 정할 수 없고 최초 상태는 PENDING_PAYMENT다. 성공 응답은 no-store다.
다른 Site/누락 상품은 404, 비활성·시간형·유효하지 않은 상품 정보는 409, 금액 오버플로는 400이다.
신규 생성 시 취소·체크아웃·Site/동기화/날짜 불명 예약은 기존 CREATE_ORDER 정책에 따라 403이다.

### 멱등성 및 롤백

ServiceOrder의 nullable `idempotency_key_hash`와 `request_hash`에 각각 키와 정규화 요청의 SHA-256을 저장한다.
OTP/JWT 비밀키와 독립적이며 키 원문은 저장하지 않는다. 예약 ID + 키 해시에 DB UNIQUE 제약
`uk_order_reservation_idempotency`를 둔다. 다른 예약의 같은 키는 독립적이며 키 대소문자는 구분한다.
요청 해시는 상품 ID 오름차순으로 정렬한 ID:수량 목록에 버전 접두사를 붙여 계산한다.
따라서 항목 순서만 바뀐 재전송은 동일 요청이다. 같은 예약·키의 다른 유효 요청은 409다.
현재 가격·이름은 요청 해시에 포함하지 않으므로 상품 변경 이후에도 재전송은 원래 스냅샷을 반환한다.

재전송에서도 현재 JWT/세션/CREATE_ORDER scope/연락처는 검증한다. 이미 존재하는 동일 요청은
예약 취소·체크아웃이나 상품 비활성화 후에도 복구할 수 있다. 이 경로는 새 주문을 만들지 않으며,
새 키로 생성하려면 현재 예약 업무 조건과 상품 조건을 모두 통과해야 한다.

별도 Redis 멱등성 기록이나 선행 커밋은 없다. 주문과 멱등성 정보는 같은 행에 있으며 항목과 함께
하나의 트랜잭션으로 커밋/롤백한다. 기존 주문의 NULL 키는 유일 제약과 충돌하지 않는다.
기록 수명은 주문 수명과 같으며 별도 TTL/삭제 작업은 없다.

### 트랜잭션과 잠금

CustomerOrderService는 TransactionTemplate의 REQUIRES_NEW / READ_COMMITTED 쓰기 트랜잭션을 사용한다.
호출자의 오래된 영속성 컨텍스트·스냅샷을 재사용하지 않고 커밋 오류까지 트랜잭션 밖에서 처리한다.
순서는 고객 세션 검증 → 예약/Site 조회 PESSIMISTIC_WRITE → 기존 멱등 주문 확인 → 신규 CREATE_ORDER 검증
→ 상품 ID 오름차순의 개별 PK/Site 제한 PESSIMISTIC_WRITE 조회 → CREATE_ORDER 재검증
→ ServiceOrderPreparation.prepareEntity 계산 → 키/요청 해시 지정 → 주문·항목 저장/flush → 커밋이다.
상품 잠금을 기다린 뒤에도 세션과 날짜 조건을 다시 확인하며 어떤 신규 주문 INSERT도 그 전에 실행하지 않는다.

같은 예약의 생성 요청은 예약 잠금으로 직렬화되며 DB UNIQUE는 별도의 마지막 중복 방지 장치다.
상품의 배타 잠금은 트랜잭션 종료까지 유지한다. 먼저 커밋한 상품 변경은 최신 값으로 읽고,
주문이 먼저 잠갔다면 후속 UPDATE가 주문 커밋을 기다리므로 저장 중간에 가격·활성 상태가 바뀌지 않는다.
예약 상태/FK의 UPDATE도 같은 예약 행 잠금과 충돌한다. Site LEFT JOIN FETCH는 dialect/실행 계획에 따라
Site 행도 추가로 잠글 수 있으므로 여러 예약의 같은 Site에 대한 잠금 범위·처리량을 MySQL에서 확인해야 한다.
외부 시스템이 직접 갱신하더라도 모든 관련 테이블이 InnoDB이고 동일 DB의 트랜잭션 잠금을 따라야 한다.

트랜잭션 timeout은 10초, 잠금 조회에는 query timeout 5초 힌트를 둔다. MySQL 드라이버/dialect에서
실제로 적용되는 시간과 innodb_lock_wait_timeout을 별도로 검증해야 하며 설정을 임의로 바꾸지 않는다.
데드락·잠금 시간 초과·저장/커밋 오류는 롤백 후 상세 정보를 숨긴 503으로 처리한다. 자동 재시도는 없다.
응답 손실/커밋 결과 불명 시 클라이언트는 반드시 같은 키·같은 요청으로 재확인한다.
DB 트랜잭션 안에서 SMS나 결제를 호출하지 않는다. Redis 세션 검증은 필요하며 장애 시 저장을 중단한다.

### 스키마 및 검증 경계

기존 마이그레이션 도구는 확인되지 않았다. 수동 SQL은 `CUSTOMER_ORDER_MIGRATION.sql`에 제공하며
실행하지 않는다. 기존 데이터/환경 설정을 바꾸지 않았고 운영 스키마에 적용하지 않았다.
현재 기본 ddl-auto=create-drop은 데이터 보존용 배포/마이그레이션 방식이 아니다. 실제 적용 전에
별도 환경의 InnoDB 스키마와 안전한 배포 설정·기존 컬럼/인덱스 여부를 확인해야 한다.

H2 검증과 별도로 아래 독립 MySQL 통합 테스트를 제공한다. 기존 Redis 통합 테스트와도 구분한다.

### 독립 MySQL InnoDB 통합 검증

`MySqlCustomerOrderIntegrationTests`는 기존 주문 생성 시나리오 55개를 실제 MySQL에서 다시 실행하고
MySQL 전용 테스트 11개를 추가하여 총 66개를 실행한다. `OwnedMySql`이 이미 설치된 Windows
MySQL 실행 파일만 사용하며 현재 검증 버전은 MySQL Community Server 8.0.37이다.
Docker/WSL 접근이 제한되어도 새 서버를 설치하거나 기존 MySQL 서비스에 접속하지 않는다.

기본 바이너리 위치는 `C:/Program Files/MySQL/MySQL Server 8.0`이고 필요하면 테스트 실행 전에
`STAYON_TEST_MYSQL_HOME`으로 설치 위치만 지정할 수 있다. 기존 DB의 URL/포트/자격증명 입력은 지원하지 않는다.
매 실행마다 build/mysql-integration/UUID/data를 새로 초기화하고 별도 로그를 UUID 디렉터리에 남긴다.
`--no-defaults`, 독립 datadir, 임의 loopback TCP 포트, `--mysqlx=OFF`, `--skip-log-bin`으로 실행한다.
기존 my.ini·Windows 서비스·운영 데이터·기존 서버 설정은 사용하거나 변경하지 않는다.
자체 PID/준비 로그와 JDBC의 datadir·포트·버전을 검증한 뒤 테스트 전용 DB를 생성한다.
initialize-insecure의 빈 root 비밀번호는 이 새로운 loopback 테스트 인스턴스에서만 사용한다.

DynamicPropertySource가 MySQL 테스트 클래스에만 datasource를 연결하고 일반 테스트는 기존 H2를 유지한다.
Redis 세션은 테스트 대역을 사용한다. 운영 Redis와 연결하지 않으며 SMS/결제 호출은 없다.
테스트 스키마의 DDL은 JPA create-drop으로 생성하며 운영 마이그레이션 SQL을 실행하지 않는다.
각 테스트는 생성한 예약/Site ID에 해당하는 행만 정리한다. Spring이 EntityManagerFactory·DataSource를
닫은 다음 자체 datadir를 재검증하고 SHUTDOWN한다. 비정상 종료에는 소유 프로세스만 종료하는
정리 경로가 있으며 OS ACL·Sandbox·Hook 및 전역 DB 설정을 바꾸지 않는다. 진단용 데이터/로그는 build에 남긴다.
환경이 없거나 검증이 실패하면 실패로 표시하며 자동으로 건너뛰지 않는다.

검증한 결과:

- 모든 관련 테이블이 InnoDB이고 예약 ID + 전체 키 해시의 UNIQUE 인덱스가 실제 생성된다.
- 서버 기본 REPEATABLE_READ인 상태에서 생성 서비스는 별도 JDBC 연결의 READ_COMMITTED를 사용한다.
  외부 트랜잭션으로 돌아오면 원래 연결/격리 수준이 복구되며 외부 롤백이 이미 커밋한 생성 결과를 취소하지 않는다.
- performance_schema.data_locks에서 기존 예약 PK 및 상품 PK의 X,REC_NOT_GAP 잠금을 관찰했다.
  이번 MySQL/Hibernate 실행에서는 Site 행의 추가 RECORD 잠금은 관찰되지 않았다.
  다른 예약 행과 다른 상품의 가격 UPDATE는 진행할 수 있었다. 이는 해당 기존 PK 조회의 결과이며,
  UNIQUE/FK 검사·존재하지 않는 키·다른 실행 계획의 모든 gap lock을 배제한다는 의미는 아니다.
- 동일 예약·동일 키 8개 병렬 요청은 1개 생성/7개 재전송으로 수렴하고, 서로 다른 키는 독립 주문이다.
  동일 키·다른 요청의 경쟁은 1개 생성/1개 409로 수렴한다.
- 부모 예약 잠금을 우회한 테스트 INSERT 두 개로 실제 UNIQUE 인덱스 대기를 관찰하고,
  먼저 커밋한 INSERT 이후 중복 INSERT의 MySQL 1062와 전체 롤백을 확인한다.
  같은 물리 연결을 rollback 후 재사용할 수 있고 서비스의 새 트랜잭션은 같은 키의 기존 주문을 복구한다.
- 저장 직전 오류 및 실제 INSERT/항목 flush 이후 오류에서 주문·항목·멱등성 정보가 함께 롤백된다.
- 예약 취소·가격 변경·상품 비활성화가 먼저 잠그면 생성이 기다린 뒤 최신 조건을 검사한다.
  생성이 상품을 먼저 잠그면 가격 UPDATE가 커밋을 기다리고 기존 스냅샷은 변하지 않는다.
- 테스트 전용 서버의 innodb_lock_wait_timeout=2로 실제 MySQL 1205와 서비스 503·롤백·같은 키 재시도를 검증한다.
- 역순 상품 잠금과 더 무거운 반대 트랜잭션을 만들어 실제 1213 데드락을 발생시킨다.
  information_schema.INNODB_METRICS의 lock_deadlocks 증가, 생성 서비스의 503·롤백 및 새 트랜잭션 재시도를 확인한다.
- 서비스 커밋 직후 응답 전달 오류를 주입하고 동일 요청을 재전송하여 기존 주문을 복구한다.
  실제 TCP 소켓의 응답 손실이나 DB COMMIT 처리 도중 연결 손실을 일으키는 테스트는 아니다.

기존 관리자 상품 변경은 단일 상품 UPDATE의 InnoDB 잠금에 의존하며 생성 서비스와 동일한 전체 잠금
순서를 구현한 것은 아니다. 현재 단일 행 변경의 대기·가격 일관성은 검증했다. 향후 다중 상품 변경,
예약/주문 취소 및 결제 상태 변경은 예약 → 상품 ID 오름차순 등 공통 잠금 순서를 합의해야 한다.
업무 충돌 409와 실제 일시적 DB 잠금 실패 503은 구분하며 자동 재시도는 없다.
테스트 서버의 2초 timeout 검증은 운영 환경의 5초 query timeout/JDBC 취소·10초 transaction timeout
및 네트워크 단절 동작을 대체하지 않는다. 다른 MySQL 버전/격리 설정/실행 계획의 검증도 필요하다.

## 미결제 주문 취소 API 및 상태 전이

POST `/customer/orders/{orderId}/cancel`은 현재 고객 JWT의 `orders:cancel` scope를 요구한다.
본문과 Idempotency-Key 헤더는 필요하지 않으며 본문에 넣은 예약 ID·주문 상태는 사용하지 않는다.
예약 ID는 CustomerPrincipal에서만 얻고, 주문 잠금 쿼리는 orderId + reservationId를 함께 조건으로 사용한다.
현재 세션 ID·예약 ID·만료·scope와 예약 연락처 HMAC 검증은 기존 ReservationAccessPolicy를 재사용한다.

| 현재 주문 상태 | 처리 |
| --- | --- |
| PENDING_PAYMENT | 예약 업무 조건이 유효할 때 CANCELLED로 한 번 전이, 200 |
| PAYMENT_PROCESSING / CANCEL_PENDING | 현재 취소 권한 검증 후 취소 의도 저장/재응답, 202 |
| CANCELLED | 추가 UPDATE 없이 같은 저장 스냅샷으로 200 |
| PAID / EXPIRED / REFUNDED / 알 수 없는 상태 | 전이 거부, 409 |

예약 취소·체크아웃·Site 미연결·동기화/날짜 불명 등 기존 읽기 전용 예약에서는 PENDING_PAYMENT의
신규 취소를 기존 정책대로 403으로 거부한다. 이미 CANCELLED이면 예약이 이후 읽기 전용이 되어도
멱등 재응답을 허용한다. 단, 현재 연락처·세션·scope는 재요청에서도 검증하며 변경/누락/마스킹 번호는 401이다.
미인증/관리자 JWT·세션 누락/만료/불일치는 401, scope 부족은 403, 다른 예약/없는 주문은 404,
허용되지 않는 주문 상태는 409, 일시적 DB·Redis 오류는 503이다.

응답은 기존 CustomerOrderResponse(id, status, totalAmount, items)이고 Cache-Control: no-store다.
상품명·단가·수량·금액은 저장된 주문 항목에서만 가져오고 NULL 상품명을 보정하지 않는다.
상품의 현재 가격/활성 상태는 취소 조건이 아니며 기존 생성 멱등성 해시, 항목 스냅샷·fulfillment 상태는 변경하지 않는다.
별도 취소 키 기록 대신 주문 ID와 고정 목표 상태 CANCELLED가 자연스러운 멱등 기준이다.
기존 생성 키를 재전송하면 같은 주문의 현재 CANCELLED 상태를 반환하며 주문을 다시 생성하지 않는다.

ServiceOrder.cancelUnpaid()는 미결제 즉시 취소의 상태 변경 경로다. PENDING_PAYMENT이면 true와 함께 상태를
바꾸고, CANCELLED이면 false, 그 밖에는 예외를 반환한다. Service에는 단순 status setter를 노출하지 않는다.
이 메서드는 쓰기 트랜잭션에서 예약과 해당 주문을 잠근 뒤 호출해야 한다.

생성과 같은 REQUIRES_NEW / READ_COMMITTED / timeout 10초 TransactionTemplate을 사용한다.
공통 잠금 계층은 예약 → (필요한 경우 상품 ID 오름차순) → 주문이다.
생성은 예약 → 상품 → 새 주문 INSERT이고 취소는 예약 → 기존 주문 SELECT FOR UPDATE다.
취소는 상품·항목을 변경하지 않으므로 해당 행의 배타 잠금은 취하지 않는다.
잠금을 기다린 후에는 CANCEL_ORDER 인가와 예약 날짜 조건을 다시 확인한다.
변경된 경우만 flush하고, 재요청은 UPDATE하지 않는다. 반환 DTO는 커밋 전에 만들지만 커밋이 성공한 뒤에만
Controller로 반환한다. 잠금/flush/커밋 오류는 전체 롤백 후 상세 정보를 숨긴 503이며 자동 재시도는 없다.
이미 커밋한 취소의 응답이 유실되면 같은 주문의 취소 URL로 다시 확인할 수 있다.
REQUIRES_NEW이므로 외부 호출자 트랜잭션 롤백이 이미 커밋한 취소를 되돌리지는 않는다.

검증은 H2와 OwnedMySql의 새 독립 MySQL 8.0.37 인스턴스에서 실행한다. 기존 생성 통합 테스트도 유지한다.
실제 MySQL의 performance_schema에서 예약·주문의 PRIMARY X,REC_NOT_GAP 잠금을 확인하고
8개 병렬 취소의 service_order 실제 UPDATE가 정확히 1회임을 확인한다.
생성 요청은 취소가 가진 예약 잠금을 기다린 뒤 독립 주문을 저장한다.
실제 주문 행 잠금 timeout(1205), 역순 외부 작성자와의 데드락(1213), 상태 UPDATE 전후 오류에서
부분 변경 없이 롤백되고 같은 주문 취소 재시도가 성공함을 확인한다.

결제 경쟁 테스트는 PG 호출 없이 `UPDATE ... SET status='PAID' WHERE status='PENDING_PAYMENT'`를
별도 DB 트랜잭션에서 실행한다. 결제 상태 변경이 먼저 커밋하면 취소는 최신 PAID를 읽어 409이고,
취소가 먼저 커밋하면 후속 조건부 결제 UPDATE는 0행으로 CANCELLED를 덮어쓰지 못한다.
이는 결제 API 구현이 아니다. 향후 결제 승인/콜백도 동일 잠금 계층과 상태 재검증 또는 조건부 전이를
반드시 지켜야 한다. 무조건적인 PAID UPDATE는 취소 커밋 이후에도 상태를 덮어쓸 수 있으므로 금지한다.
실제 결제·환불 호출, 외부 콜백 수신·환불 정책은 별도 구현 사항이다.
결제 시도와 주문의 로컬 상태 반영·승인 중 취소는 PAYMENT_DESIGN.md의 PaymentOrchestrator가 조율한다.
미결제 즉시 취소 단계에서는 운영 DB/Redis·스키마와 ACL/Hook을 변경하지 않았으며 스키마 마이그레이션이 필요하지 않았다.

## 결제 승인·취소 기반

상태 전이·PaymentAttempt 제약·PG 호출 경계·복구 및 미구현 범위는 PAYMENT_DESIGN.md를 따른다.
기존 미결제 취소 200, CANCELLED 멱등 응답 200, PAID/EXPIRED/REFUNDED 취소 409는 유지한다.
승인 중 취소는 의도를 영속화하고 202로 반환한다. UNKNOWN이면 금융 결과 확인 전 취소를 완료하지 않는다.
PG Adapter 실구현은 없으며 Fake PG는 테스트에만 있다. 새 PG 엔드포인트나 실제 결제 호출을 추가하지 않는다.
기존 미결제 취소 단계 자체에는 스키마 변경이 없었지만, 이 결제 기반 단계는 PaymentAttempt 테이블과
주문 enum 확장을 위한 별도 수동 마이그레이션이 필요하다. PAYMENT_STATE_MIGRATION.sql은 검토용이며 적용하지 않았다.

고객 결제 생성·승인·조회 API와 자동 복구 Worker는 CUSTOMER_PAYMENT_API.md의 현재 계약을 따른다.
생성/승인은 기존 orders:create, 조회는 orders:read와 예약 접근 정책을 재사용한다.
PG 거래 ID/내부 선점 값은 공개하지 않으며 Worker는 고객 세션 만료와 독립적으로 금융 결과를 조회한다.
payment.recovery.enabled 기본값은 false다. 운영 DB/설정은 변경하지 않았고 실제 PG는 호출하지 않는다.

PG_WEBHOOK_INBOX.md에 Provider 생성 검증과 Webhook 전용 인증 경계를 정의했다.
Webhook POST는 고객/관리자 JWT 대신 등록된 Provider Verifier로 원본을 인증하고, 저장 커밋 후에만 200을 반환한다.
payment.webhook.enabled 기본값은 false이며 실제 PG 검증 구현은 등록하지 않았다.

다중 사업자 PG 가맹점과 Secret 접근은 PG_MERCHANT_ROUTING.md를 따른다. 고객은 가맹점 ID를 제출할 수 없고,
서버 소유 경로에서 선택해 PaymentAttempt에 고정한다. Webhook은 provider 및 인증된 가맹점/거래 참조를 모두 검증한다.

KAKAOPAY_INTEGRATION.md에 Ready 인증 컨텍스트와 브라우저 콜백 계약을 정의했다. pg_token은 JWT 인증된 승인 POST의
일시적 본문으로만 처리하고 DB/로그에 저장하지 않는다. 리다이렉트 자체는 결제 완료 또는 서버 간 Webhook이 아니다.

## 다음 단계 연결점

- CustomerPrincipal.reservationId/sessionId/scopes를 ReservationAccessPolicy 입력으로 사용한다.
- CustomerAuthStore가 세션 저장소 계약이며 RedisCustomerAuthStore가 Redis 구현이다.
- 서비스에서 예약 소유권, 등록 전화번호 변경, 예약·Site·주문 상태를 매 요청 검증한다.
- 일반 Access Token은 만료 전 반복 사용 가능하며 로그아웃은 Redis 세션을 즉시 삭제한다.
- 실제 발송 어댑터·공급자 멱등성·요청 타이밍과 SMS 비용·비정상 시도 감시를 검증한다.
- 독립 Redis에서 Lua 실행, TTL, 다중 인스턴스 경쟁, 타임아웃 및 Cluster 동작을 통합 검증한다.

운영 Redis·DB 변경, 실제 SMS 및 실제 결제·환불 API는 포함하지 않는다.
