# KakaoPay 공식 규격 대조 및 격리 검증 절차

확인일: 2026-11-09. 외부 PG API는 호출하지 않았다. 문서 조회와 로컬 HTTP Mock 검증을 구분한다.

## 공식 출처 및 확인 범위

- [공통/환경/TLS/도메인](https://developers.kakaopay.com/docs/payment/online/common)
- [Ready/Approve 필드](https://developers.kakaopay.com/docs/payment/online/single-payment)
- [Order 상태/취소 금액](https://developers.kakaopay.com/docs/payment/online/payment-detail)
- [응답 및 오류 안내](https://developers.kakaopay.com/docs/payment/online/reference)
- [Cancel 문서](https://developers.kakaopay.com/docs/payment/online/cancellation)
- 공식 개발자센터가 연결한 [Toolkit 취소 구현](https://github.com/kakaopay-develop/agent-toolkit/blob/main/typescript/src/shared/payment/cancelPayment.ts) 및 [HTTP Client](https://github.com/kakaopay-develop/agent-toolkit/blob/main/typescript/src/shared/client.ts)

Cancel 문서는 이번에도 도구에서 탐색 메뉴만 반환했다. 최소 요청은 공식 Toolkit, 응답 필드는 기존에 확인한 공식 담당자의 [전체 취소 사례](https://developers.kakaopay.com/forum/t/api/376)와 교차 확인했다. 따라서 최신 Cancel의 전체 선택 필드/에러 코드별 계약을 확인했다고 주장하지 않는다.

## 규격과 구현의 대조표

| 항목 | 공식 계약과 대조 결과 | 구현/차이 및 처리 |
| --- | --- | --- |
| Ready 필수 값 | CID, 주문·사용자 참조, 상품명/수량, 금액/비과세, 성공·취소·실패 주소 | 모두 전송. 서버 스냅샷과 익명 UUID 사용. 명확한 불일치 없음 |
| Ready 선택 값 | VAT, 상품 코드, 결제수단/카드 제한 등 | 현재 상품 모델에 필요 없는 옵션은 생략. VAT는 PG 계산 사용. 혼합 과세/보증금 미지원 |
| Ready 응답 | TID 및 플랫폼별 결제 URL, 생성 시각 | TID/PC/Mobile URL 사용. app URL과 생성 시각은 사용하지 않음. HTTPS 호스트 제한은 서버의 추가 보안 정책 |
| Approve 필수 값 | 준비 거래와 일치하는 CID/TID·파트너 참조 및 인증 토큰 | 고정 가맹점·TID와 일회성 브라우저 proof 사용 |
| Approve 선택 금액 | 총액은 선택 값이며 Ready와 같아야 함 | 서버 금액을 추가 전송하여 일치 검증. 필수 값으로 오해하지 않음 |
| 승인 응답 | 원래 TID, 가맹점/파트너 참조, 금액, 승인 시각 | 해당 값을 검증. AID는 개별 작업 번호이므로 원래 TID로 대체하지 않음 |
| Order | CID/TID로 거래 조회, 상태 및 누적 취소/잔여 금액 | 승인 확정 또는 전액 취소 증거만 확정 처리. 다른 상태를 일괄 FAILED로 만들지 않음 |
| CANCEL_PAYMENT | 여러 부분 취소 합계로 전액 취소된 경우도 포함 | 조회에서는 누적 전액 취소를 검증. 직접 Cancel 응답은 이번 요청 금액도 전액인지 추가 검증 |
| Cancel 요청 | 공식 Toolkit의 CID/TID/취소액/비과세액 | 원래 승인 원금·세금 전액만 전송. 부분 취소/추가 선택 옵션은 미지원 |
| Cancel 응답 | 거래 참조, 이번/누적/잔여 취소 금액 | 현재 엄격 검증 유지. 최신 본문 전체와 실제 SANDBOX 호환성은 미확인 |
| HTTP 오류 | HTTP 상태와 PG 오류 본문은 별도 정보 | 원문을 고객에게 노출하지 않음. 4xx/5xx·timeout 자체로 금융 실패 확정/재전송하지 않는 보수적 정책 유지 |
| TLS/연결 | 공식 서버 TLS 1.2 지원, HTTPS JSON 및 SECRET_KEY 인증 | TLS 1.2, HTTP/1.1, 연결 2초, 응답 deadline 기본 8초/최대 10초, 리다이렉트 금지. 실 TLS 협상은 미검증 |
| HTTP 목적지 | 서버 자격 증명은 검증된 공식 호스트에만 전송 | **확인된 결함 수정:** 생성 시 주소를 검증하고도 요청마다 변경 가능한 설정 주소를 읽던 흐름을 제거. 검증된 origin을 final 필드로 고정 |
| 콜백 도메인 | PG 개발자센터 등록 Web 도메인과 일치 필요 | 도구가 선언한 등록 origin과 HTTPS 콜백을 비교. 개발자센터 실제 등록 여부는 운영자 확인이 필요하며 자동 조회하지 않음 |

명확히 확인한 요청 필드 불일치는 발견하지 않았다. 선택 기능 미지원, 추가 검증 정책, 검증하지 못한 계약을 구분한다. 실제 PG의 요청 멱등성, 조회 지연의 상한, 특정 오류 코드의 영구 실패 보장은 추정하지 않는다.

### HTTP/오류 분류

| 관측 | 공식 확인/현재 대응 |
| --- | --- |
| HTTP 200 | 응답 본문 검증까지 통과해야 금융 성공. 잘못된 JSON/참조/금액은 미확정 |
| HTTP 400, PG -731 예시 | 공식 참고 문서의 CID 오류 예시. 환경·가맹점·자격 증명을 확인하며 금융 자동 재전송 없음 |
| HTTP 400, PG -780 예시 | 공식 참고 문서의 원천사 승인 오류 및 extras 예시. 해당 코드만으로 영구 무과금/거래 종료를 보장하지 않음 |
| 기타 4xx/5xx/연결 오류 | 현재 Adapter는 금융 미확정으로 유지하고 조회 복구. Secret 등 사전 설정 실패는 금융 선점 전 503 가능 |
| 로컬 timeout | PG 플랫폼 timeout과 별개로 결과가 유실될 수 있음. 재승인/재취소 금지 |

참고 문서가 연결하는 전체 오류 코드 파일(v1.7)은 이번 검증에서 내려받아 대조하지 않았다. 코드별 영구 실패/자동 재시도 allowlist는 추가하지 않았다.

## 실행 환경 및 격리

`src/sandbox/java/.../KakaoPaySandboxProbe.java`는 운영 JAR 및 기본 JUnit 소스셋에 포함되지 않는 독립 도구다.
Spring 테스트 컨텍스트에서 기존 주문 서비스·인증/예약 정책·PaymentOrchestrator·PaymentTransactions·PgSecrets를 사용한다. Redis 세션은 테스트 더블이며 실제 Redis/MySQL/운영 데이터에는 연결하지 않는다.
실행마다 build/kakaopay-sandbox/<임의 UUID>/state에 새 H2 파일 DB를 만든다. 외부 datasource 입력은 받지 않고 이 경로를 강제한다.
DB는 update 모드로 종료 후에도 보존하여 불확정 거래의 고정 TID·상태·취소 의도를 확인할 수 있다. 재실행은 새 거래를 만들므로 미완료 거래 복구 수단으로 재실행하면 안 된다.

자동 Worker 세 종류는 모두 비활성이다. 실제 환경에서도 스케줄러의 기본 비활성 정책을 변경하지 않는다.

## 외부 호출 없는 준비 및 로컬 실행

각 Gradle 프로세스에서 Java 21과 승인된 프로젝트 캐시 경로를 사용한다.

```powershell
$env:JAVA_HOME='C:\Users\Kevin\.jdks\corretto-21.0.6'
$env:GRADLE_USER_HOME='C:\dev\stayon-backend\.gradle-codex-home'
.\gradlew.bat prepareKakaoSandbox kakaoSandboxLocal --offline --no-daemon
```

prepareKakaoSandbox는 컴파일 및 build/kakaopay-sandbox-classpath.txt 생성만 한다.
kakaoSandboxLocal은 `--local-smoke`로 동일 도구를 실행하며 **127.0.0.1의 Mock HTTP 서버만** 사용한다. 개발 Secret 환경변수를 읽지 않고 합성 자격 증명을 사용한다.
이 모드의 콜백 인증 결과는 fixture이며 실제 카카오페이 브라우저 인증 검증이 아니다.

## 별도 명시적 승인 후에만 가능한 외부 실행

이번 작업에서는 아래 외부 실행을 하지 않는다. 준비 명령의 성공이나 환경변수 설정이 외부 호출 승인 자체를 대신하지 않는다.

승인 요청에는 다음 범위를 명시한다: TC0ONETIME 개발 Mock 거래 한 건(2400 KRW), Ready 1회, Approve 최대 1회, 승인 Order 조회 1회, 전체 Cancel 최대 1회, 취소 후 Order 조회 1회. 오류 시 중단하고 금융 재전송은 하지 않는다.

전용 터미널에 다음 값을 설정한다. 운영 애플리케이션의 환경 파일이나 Jenkins 설정에 넣지 않는다.

| 환경변수 | 값/준비 |
| --- | --- |
| KAKAOPAY_SANDBOX_ENABLED | true (기본 미설정은 실행 거부) |
| KAKAOPAY_SANDBOX_APPROVAL | I_APPROVE_KAKAOPAY_DEVELOPMENT_MOCK_CALLS |
| KAKAOPAY_SANDBOX_SECRET_KIND | DEV (개발자센터에서 종류 확인 후 선언) |
| KAKAOPAY_SANDBOX_DOMAIN_REGISTRATION | confirmed (개발자센터 Web 등록 확인 후 선언) |
| KAKAOPAY_SANDBOX_ENVIRONMENT | SANDBOX |
| KAKAOPAY_SANDBOX_CID | TC0ONETIME |
| KAKAOPAY_SANDBOX_API_ORIGIN | https://open-api.kakaopay.com |
| KAKAOPAY_SANDBOX_REGISTERED_ORIGIN | 등록한 개발 전용 HTTPS origin, 끝 slash 없음 |
| KAKAOPAY_SANDBOX_CALLBACK | 해당 origin의 콜백 경로, query/fragment/userinfo 없음 |
| KAKAOPAY_SANDBOX_DEV_SECRET | 별도 발급한 개발 Secret. 저장소/명령 기록/로그에 원문을 입력하지 않음 |

Secret은 전용 터미널의 보안 입력으로 주입하고 세션 종료 시 제거한다. 예:

```powershell
$devCredential=Read-Host 'Development Secret only' -AsSecureString
$credentialPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($devCredential)
try { $env:KAKAOPAY_SANDBOX_DEV_SECRET=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($credentialPointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($credentialPointer); $devCredential.Dispose() }
```

환경변수는 자식 프로세스에서 읽을 수 있는 평문이므로 전용 개발 세션에만 사용하고 로그/환경 덤프를 금지한다. Secret의 내용만 보고 DEV/LIVE 여부를 판정하는 공개 규격은 확인하지 못했다. DEV 표시는 운영자 확인이며 잘못된 Secret 입력을 수학적으로 검출한다고 보장하지 않는다. 테스트 CID 고정·전용 변수·단일 merchant scope·격리 소스셋으로 오용을 제한한다.

승인 및 모든 설정 확인 후, 리다이렉트하지 않는 실제 대화형 터미널에서 실행한다. Gradle JavaExec로 외부 실행하지 않는다. System.console()이 없으면 외부 모드는 중단한다.

```powershell
$sandboxClasspath=Get-Content build/kakaopay-sandbox-classpath.txt -Raw
try {
    & "$env:JAVA_HOME\bin\java.exe" -cp $sandboxClasspath com.stayon.stayon_backend.service.KakaoPaySandboxProbe
} finally {
    Remove-Item Env:KAKAOPAY_SANDBOX_DEV_SECRET -ErrorAction SilentlyContinue
    Remove-Item Env:KAKAOPAY_SANDBOX_APPROVAL -ErrorAction SilentlyContinue
    Remove-Item Env:KAKAOPAY_SANDBOX_ENABLED -ErrorAction SilentlyContinue
}
```

개발자센터에 등록한 HTTPS 페이지는 pg_token이 포함된 최종 콜백 URL을 사용자가 확인할 수 있어야 한다. URL을 서버 로그·분석 도구·브라우저 저장소에 보관하지 않고 Referrer-Policy: no-referrer를 적용한다. React나 공개 콜백 서버는 이번 작업에서 만들지 않았다.
도구는 PC URL만 표시한다. 브라우저 인증 후 전체 콜백 URL을 숨김 콘솔 입력으로 전달한다. attempt/state/hash·원래 HTTPS 경로·중복/추가 파라미터를 확인하고 기존 고객 세션과 callback 소비 정책으로 승인한다. pg_token은 DB·파일·로그에 저장하지 않는다.

## 실제 검증 순서와 정책

1. 새 테스트 owner/예약/Site/주문과 가맹점 생성, Secret Resolver를 해당 owner/merchant/Provider/환경/참조에 한 번 바인딩.
2. Ready를 기존 서비스로 수행하고 TID 저장 후 PC URL 발급.
3. 숨김 입력 콜백 검증 및 기존 Orchestrator의 승인 선점/PG 승인.
4. 도구 전용 Adapter decorator가 승인 응답 반영 직전에 기존 고객 취소 서비스를 호출하여 취소 의도 저장. 승인 결과는 CANCEL_PENDING으로 반영.
5. Order로 승인 거래 조회, 기존 Orchestrator로 전체 Cancel, Order로 전액 취소 조회.
6. 최종 주문 CANCELLED / 결제 CANCELED 확인, H2 journal 보존 및 종료.

4번은 승인·취소 보상 시나리오이며 일반 PAID 주문의 고객 환불 허용이 아니다. 상태를 SQL로 강제 변경하거나 권한 정책을 우회하지 않는다. 도구의 Adapter decorator도 testOnly여서 운영 프로필에서 등록할 수 없다.
브라우저의 취소/실패 redirect에는 승인하지 않는다. 승인/취소 결과가 불명확하면 중단하며 UNKNOWN/CANCEL_PENDING과 원래 TID를 보존한다. 프로세스 재시작 후 이 도구를 다시 실행하여 같은 금융 요청을 재현하지 않는다.

## 장애 및 미검증 범위

| 장애 | 로컬 검증/대응 |
| --- | --- |
| Ready 응답 유실 | 기존 테스트로 UNKNOWN 및 재전송 방지 검증. TID 자체를 잃으면 주문번호 조회를 가정하지 않고 수동 확인 |
| Approve/Cancel 응답 유실 | 기존 실제 HTTP timeout fixture와 Order 조회 복구. 금융 POST 재전송 금지 |
| Secret/CID/TID 오류 | scope/환경/참조/응답 검증. 금융 실패를 추정하여 확정하지 않음 |
| 승인·취소 경쟁 | 기존 JUnit/MySQL + 도구의 승인 응답 전 취소 의도 저장 흐름 |
| PG 조회 지연 | 미확정 상태 보존 및 기존 Worker 백오프. 실제 PG 지연 상한은 미검증 |
| DB 커밋 실패/Worker lease 회수 | 기존 JUnit/InnoDB 롤백·조회 복구·stale lease 차단 테스트 유지 |

실제 개발 Mock도 외부 거래 생성·승인·취소 및 쿼터 사용을 발생시킨다. 공식 테스트 CID/DEV 키 조합을 사용하며 실결제를 의도하지 않지만, 잘못된 설정의 금융 영향을 코드만으로 보장하지 않는다. 실 사용자/운영 Secret/운영 가맹점을 사용하지 않는다.
실제 TLS 협상, 개발 Secret 권한, 브라우저 리다이렉트 및 콜백 파라미터의 정확한 형태, 최신 Cancel 응답의 전체 호환성, 실제 오류 코드와 조회 지연·정산 정책은 외부 승인 전까지 미검증이다.

## 검증 결과 (2026-11-09)

| 범위 | 테스트 | 실패 | 오류 | 건너뜀 |
| --- | ---: | ---: | ---: | ---: |
| 신규 JUnit 실행 케이스 | 30 | 0 | 0 | 0 |
| 전체 JUnit (기존 1427 포함) | 1457 | 0 | 0 | 0 |
| 실제 MySQL 8.0.37/InnoDB, 7개 클래스 합계 | 540 | 0 | 0 | 0 |

전체 검증 명령: `.\gradlew.bat compileJava test --offline --no-daemon --rerun-tasks --no-build-cache`.
BUILD SUCCESSFUL(3분 47초). 기존 deprecated/unchecked 컴파일 경고는 남아 있다. 로그: build/kakao-compat-full.log. 독립 MySQL 인스턴스 7개 모두 정상 종료를 확인했다.
신규 30개는 안전장치 테스트 29개와 검증 후 HTTP origin 변경 방지 테스트 1개다. 기존 금융 장애/동시성 테스트는 삭제하거나 약화하지 않았다.

초기 로컬 도구 실행에서 Mock HTTP 서버 미종료로 프로세스가 남는 문제를 발견하여 close 처리를 추가했다. 해당 실행은 실패로 종료했고 수정 후 대상 JUnit 38개 및 로컬 전체 여정을 정상 종료로 재검증했다(build/kakao-compat-target-fixed.log).
로컬 smoke는 별도 main 실행이며 위 JUnit 개수에 포함하지 않는다. 실제 외부 실행 및 실제 pg_token 입력은 수행하지 않았다.

추가 `bootJar --offline` 검사는 기존 developmentOnly 의존성 spring-boot-devtools:4.1.1의 오프라인 캐시 부재로 실패했다(build/kakao-compat-packaging.log). 의존성을 변경하거나 네트워크 다운로드하지 않았다. 소스셋 분리와 일반 compileJava/test는 검증했지만 새 실행 JAR의 내용 검사는 완료하지 못했다.
마지막 `prepareKakaoSandbox kakaoSandboxLocal --offline --no-daemon`도 BUILD SUCCESSFUL(50초)로 종료했다(build/kakao-compat-tool-final.log). 금액/Provider 사전 검사를 포함한 최종 도구로 전체 로컬 여정을 다시 검증했다. main 클래스 출력 경로에서 Sandbox/Fake PG 클래스가 0개인 것을 확인했다.

후속 작업에서 실행 JAR 패키징·시작·Bean 검사도 검증했다. DevTools 다운로드는 Sandbox 네트워크 제한으로 여전히 불가능하지만 운영 런타임 의존성 범위를 분리하여 bootJar는 오프라인 생성 가능하다. 최신 결과와 격리 실행 방식은 [EXECUTABLE_JAR_VERIFICATION.md](EXECUTABLE_JAR_VERIFICATION.md)를 따른다.
