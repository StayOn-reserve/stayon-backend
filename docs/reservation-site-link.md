# Reservation–Site 연결 계약

## 확인한 생산자 코드

인접 저장소 `reserve-by-sms-webhook/reserve-by-sms-webhook`의 다음 파일을
읽기 전용으로 확인했다. 배포된 버전과 과거 데이터의 생산 경로는 별도 확인이 필요하다.

- `src/main/java/wonoong/sms_webhook/entity/Reservation.java`
  - `accommodationItemId`는 nullable `Long`으로 선언된다.
  - `Reservation.from()`은 해당 값을 설정하지 않는다.
- `src/main/java/wonoong/sms_webhook/service/ReservationService.java`
  - 현재 소스에서 `accommodationItemId` 대입 경로는 발견하지 못했다.
  - `blockCamfit()`은 TRIP_ELEVEN 예약에 CAMFIT 차단 대상 ID를 저장한다.
  - `blockTripEleven()`은 CAMFIT 예약에 TRIP_ELEVEN 차단 대상 ID를 저장한다.
  - `externalSiteId`는 예약 원본 플랫폼의 Site ID가 아니다.
  - 취소는 예약 행 삭제가 아니라 `eventType` 변경으로 저장한다.

`Long` 타입만으로 `accommodation_item_id`가 내부 PK라는 사실을 보장할 수 없다.
값이 NULL이면 검증된 ExternalSite 관계만 사용한다. 값이 있으면 의미가 확인될 때까지
연결을 거부한다. 외부 상품 ID를 내부 PK로 변환하거나 이름으로 대체 조회하지 않는다.

## 기본 안전 정책

다음 설정은 모두 기본값이 false이며 운영 설정을 자동 변경하지 않는다.

- `reservation-site-link.external-site-id-is-block-target`
  - 대상 예약의 생산 경로가 위 계약과 일치함을 확인한 후에만 활성화한다.
  - 활성화 시 예약의 반대 플랫폼으로 ExternalSite를 조회한다.
  - 미확인 상태는 `UNVERIFIED_EXTERNAL_SITE_ID`로 반환한다.
- `reservation-site-link.accommodation-item-id-is-internal-pk`
  - 값이 실제 `accommodation_item.id`임을 확인한 후에만 활성화한다.
  - 활성화해도 값이 Site의 부모 PK와 정확히 일치해야 한다.
  - 미확인 상태는 `UNVERIFIED_ACCOMMODATION_ITEM_ID`로 반환한다.

`RESERVED`이면서 `syncStatus=SUCCESS`인 예약만 새로 연결한다.
취소된 예약은 `RESERVATION_CANCELLED`, 동기화 미완료·알 수 없는 상태는
`RESERVATION_NOT_READY`로 반환한다. 이미 연결된 Site는 자동 변경하지 않는다.

## 트랜잭션과 경쟁 조건

`ReservationSiteLinkService.link()`를 Spring Bean을 통해 호출해야 한다.
독립 트랜잭션에서 매핑과 부모 행을 `FOR UPDATE`로 조회하고 JDBC로 `site_id`만 갱신한다.
Resolver는 기존 트랜잭션을 요구한다. 같은 예약의 잠금을 이미 가진 상위 트랜잭션에서
호출하면 대기·타임아웃 위험이 있으므로 그런 호출 구조를 만들지 않는다.

조건부 UPDATE는 미연결 상태, 관찰한 플랫폼·외부 ID·상품 ID와 현재 예약 상태를 재검증한다.
0행 갱신은 `CONCURRENT_UPDATE`이며 오래된 후보로 자동 재시도하지 않는다.
인프라 예외는 미매핑으로 변환하지 않고 전파해 트랜잭션을 롤백한다.

실제 DB의 `(platform, external_site_id)` unique 제약과 InnoDB 잠금 검증이 필요하다.
조회 이후 새 중복 매핑 삽입, 교착, 잠금 시간 초과는 단위 테스트로 입증되지 않았다.
연결 커밋 이후의 예약 취소·외부 매핑 변경까지 차단하는 기능은 아니다.
상위 호출자가 이미 로드한 JPA Reservation은 JDBC 갱신 이후 다시 읽어야 한다.

## 검증 범위

- 단위 테스트: 매핑 판정, 기존 연결 보호, 갱신 0행 처리, SQL 쓰기 대상과 상태 조건.
- 컨텍스트 테스트: 전용 H2 메모리 DB 및 트랜잭션 프록시 구성.
- 테스트 리소스의 `application.yaml`은 운영 연결 설정을 사용하지 않는다.
- H2 컨텍스트 성공은 MySQL 전용 SQL, 실제 필드 보존 또는 동시성 검증을 대신하지 않는다.

MySQL 통합 테스트 전에 실제 컬럼명·타입, FK·unique 제약, 과거 데이터 생산 경로,
배포된 sms-webhook 버전을 확인한다. 생산자 변경, 데이터 백필, 공유 DB DDL은 이 기능에
포함하지 않는다.
