# 주문 스냅샷 영속화

`ServiceOrderPreparation.prepareEntity`는 기존 계산 결과의 상품명, 단가, 수량, 금액을
저장 전 엔티티로 복사한다. 인증이나 DB 저장은 수행하지 않는다.
`ServiceOrderRepository.save` 시 주문의 PERSIST cascade로 항목을 함께 저장한다.
예약·상품에 cascade를 전파하지 않으며 삭제 cascade도 없다.

| ItemSnapshot | ServiceOrderItem |
| --- | --- |
| additionalServiceId | additionalService FK |
| serviceName | service_name (추가) |
| unitPrice | unit_price |
| quantity | quantity |
| totalPrice | total_price |

주문 합계는 service_order.total_amount, 초기 상태는 PENDING_PAYMENT로 저장한다.
스냅샷 필드는 setter 없이 보관하며 JPA UPDATE에서 제외한다.
주문 표시에는 스냅샷을 사용하고 현재 상품명·가격으로 대체하지 않는다.
updatable=false는 JPA 보호이며 직접 SQL이나 벌크 UPDATE를 막는 DB 제약은 아니다.

## 기존 데이터 및 마이그레이션

Flyway/Liquibase 구성은 없다. 아래는 실행하지 않은 MySQL 검토용 SQL이다.
실제 테이블과 컬럼 존재 여부를 확인하고 별도 승인된 절차로 앱 배포 전에 적용해야 한다.

```sql
ALTER TABLE service_order_item
    ADD COLUMN service_name VARCHAR(255) NULL;
```

기존 행의 상품명은 NULL로 유지한다. 현재 상품명으로 과거 이름을 추정하여 보정하지 않는다.
과거 주문 표시에서는 이름 미확인으로 처리해야 한다. 신규 엔티티 조립은 상품명을 필수로 받는다.
과거 이름 복구와 모든 쓰기 경로 전환 전에는 NOT NULL로 변경하지 않는다.
기존 단가·수량·금액·FK는 유지하며 항목 컬렉션에는 새 컬럼이 필요하지 않다.
기본 ddl-auto=create-drop은 데이터 보존용 마이그레이션 절차가 아니다.
운영 적용 전 실제 프로필과 스키마 관리 설정을 확인해야 한다.

## 주문 API 구현 전 남은 작업

- 고객/OTP 인증, 예약 소유권 인가 및 주문 가능 상태 확인
- 상품 조회·계산·저장 트랜잭션과 가격 변경 동시성 정책
- 중복 요청 방지와 주문 생성 멱등성
- 시간형 상품의 5분 슬롯 점유·충돌·만료·해제
- 결제 연동과 금액 검증, 상태 전이 및 실패·취소 처리
- 스냅샷 기반 주문 조회 DTO 및 과거 상품명 미확인 표시
