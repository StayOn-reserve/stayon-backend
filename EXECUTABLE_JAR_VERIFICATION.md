# 실행 JAR 검증 및 외부 KakaoPay 사전 점검

검증일: 2026-11-09. 실제 PG 금융 API, 운영 데이터/스키마, 배포 작업은 수행하지 않는다.

## Gradle 문제와 수정

- 기존 DevTools 선언은 developmentOnly이며 버전은 Spring Boot BOM의 4.1.1이다. 버전이나 의존성 목록을 변경하지 않았다.
- 오프라인 캐시에 해당 DevTools 아티팩트가 없었다. 이번에 허용된 정상 Maven Central 다운로드를 시도했으나 Sandbox에서 Permission denied: getsockopt로 실패했다. 다운로드가 완료된 것은 아니다.
- 설치된 Spring Boot Gradle Plugin 4.1.1 코드를 확인했다. bootJar의 파일 차감 및 의존성 메타데이터 해석 과정에서 runtimeClasspath를 해석하여, 패키징에서 제외할 developmentOnly도 먼저 요구했다.
- runtimeClasspath의 개발 전용 상속을 분리하고 bootJar에는 main.runtimeClasspath를 직접 지정했다. bootRun에는 developmentOnly/testAndDevelopmentOnly를 명시적으로 더하여 개발 의존성 선언과 개발 실행 의도를 유지한다.
- 이 수정으로 캐시된 운영 의존성만 사용한 bootJar 생성이 성공했다. 개발용 DevTools 자체의 다운로드 문제는 남아 있어 bootRun/실제 hot reload는 별도 네트워크 접근 및 검증이 필요하다.

## 재현 명령

```powershell
$env:JAVA_HOME='C:\Users\Kevin\.jdks\corretto-21.0.6'
$env:GRADLE_USER_HOME='C:\dev\stayon-backend\.gradle-codex-home'
.\gradlew.bat bootJar verifyExecutableJar --offline --no-daemon
.\gradlew.bat compileJava test kakaoSandboxLocal --offline --no-daemon --rerun-tasks --no-build-cache
```

첫 명령은 실제 실행 JAR 검증 도구를 실행한다. 기존에 설치된 MySQL 8 실행 파일이 필요하며, 기존 OwnedMySql이 새 UUID datadir/랜덤 loopback 포트/--no-defaults로 별도 인스턴스를 시작한다. 접속 전 PID/포트/데이터 경로를 확인한다. 기존 MySQL 서버는 사용하지 않는다.

## 아카이브 및 실행 검증 방식

생성물: build/libs/stayon-backend-0.0.1-SNAPSHOT.jar.

ExecutableJarVerifier는 다음을 실제로 검사한다.

1. Manifest의 JarLauncher/Start-Class 및 PaymentOrchestrator 클래스 존재.
2. DevTools, Sandbox/Fake PG, 검증 Initializer, H2, JUnit이 실행 JAR에 포함되지 않음.
3. 별도 프로세스에서 실제 `java -jar` 실행, loopback 포트에서 시작 완료, 미인증 GET /customer/reservation의 401.
4. 동일한 실제 아카이브를 PropertiesLauncher로 실행하여 Bean/환경 주입을 검사. 외부 support JAR에는 진단 Initializer만 포함하며 애플리케이션 클래스/의존성을 교체하지 않는다.
5. PaymentOrchestrator, KakaoPayReadyService, KakaoPayAdapter, PgSecrets Bean 구성, 공식 요청 호스트, 8초 deadline, SANDBOX/LIVE 분리, Worker 세 종류 비활성.
6. 실행 JAR에 실제 PgSecretResolver 구현이 없고 자격 증명 부재 시 503으로 닫히는 동작.
7. 외부 properties의 placeholder가 자식 프로세스 환경변수로 주입되는지 별도 marker로 확인.

검증 지원 JAR은 build/jar-verification-support에 별도로 생성한다. build/libs의 운영 산출물과 섞이지 않으며 기본 `java -jar`에도 포함되지 않는다. 검증용 Initializer는 jar-verification 프로필과 명시적 검증 설정이 있어야 실행된다.

첫 자식 프로세스는 검증 후 도구가 종료하고, 두 번째는 검증 완료 시 컨텍스트를 닫는다. finally에서 두 프로세스 및 독립 MySQL을 정리한다.

## 설정 격리

실행마다 build/jar-verification/<UUID>/application.properties를 생성하고 --spring.config.location으로 지정한다. 기본 application.yaml 및 운영 .env를 읽지 않는다.
자식 환경은 Windows 실행에 필요한 소수의 OS 변수만 남기고 새로 구성한다. 기존 DB/PG/Secret/JAVA_TOOL_OPTIONS 등의 설정을 상속하지 않는다.

- DB: 도구가 생성한 MySQL 주소만 환경변수로 주입. create-drop은 이 신규 테스트 스키마에서만 사용.
- Redis 및 business API: 127.0.0.1:1로 지정. OTP/SMS/업무 API 요청을 수행하지 않음.
- JWT/HMAC: 서로 다른 임의의 테스트 키를 생성하여 자식 환경에만 전달. 원문을 파일/명령행/로그에 기록하지 않음.
- 서버: 127.0.0.1의 임시 포트. 미인증 확인 요청 한 건만 전송.
- KakaoPay: Bean 생성만 검증. 실제 금융 메서드 호출 없음. 실제 PG Secret 없음.
- 자동 승인 복구/취소/Webhook Worker: 명시적으로 비활성.

증거는 실행별 java-jar.log, beans.log, beans.properties, result.properties에 남는다. 보고서에는 자격 증명이 아닌 Boolean 결과만 쓴다.
이 검증은 시작·구성·인증 경계 검사이며 실제 Redis 세션이나 PG 인증/결제 호환성 검증을 대신하지 않는다.

## 외부 개발 환경 사전 점검

현재 세션에서 비밀값을 출력하지 않고 존재/일치 여부만 확인했다. 개발 Secret, 테스트 CID/SANDBOX, HTTPS 콜백, 공식 호스트 선언, DEV 확인, 도메인 등록 확인 및 실행 승인 플래그는 모두 미설정이다. 외부 호출은 준비되지 않은 비활성 상태다.

| 항목 | 구현된 방어 / 남은 작업 |
| --- | --- |
| CID | SandboxProbe는 TC0ONETIME만 허용. 운영 CID 불가 |
| Secret | 전용 개발 Secret 변수와 기존 Resolver 사용. owner/Provider/환경/가맹점/참조 scope 한 번 바인딩. 실제 DEV 여부는 발급 화면에서 확인 필요 |
| 환경 | 테스트 전용 소스셋, test 단독 프로필, SANDBOX 고정. 운영 JAR에는 Probe/개발 Resolver 없음 |
| 콜백 | HTTPS origin/path/attempt/state 및 중복 파라미터 검증. 실제 개발자센터 Web 도메인 등록과 콜백 페이지는 사용자가 준비해야 함 |
| 목적지 | HttpClient 생성 시 검증한 공식 origin을 고정. Mock transport는 test의 loopback에서만 가능 |
| 시간 제한 | connect 2초, 전체 응답 기본 8초/설정 최대 10초. timeout을 금융 실패 확정으로 해석하지 않음 |
| 재전송 | Ready 불명확/승인·취소 claim 후 금융 POST 자동 재전송 금지. 원래 TID 조회 및 기존 lease/백오프 사용 |
| 로그 | Secret/pg_token 미저장, 숨김 콘솔 입력, 일반화된 오류. 실제 실행 전 HTTP 헤더 debug, 프록시/콜백 query logging 및 터미널 녹화도 비활성 확인 |
| 추적 | Probe의 새 H2 파일 DB에 주문/attempt/고정 가맹점/TID/취소 의도 보존. 재실행은 새 거래이므로 미완료 거래 복구 목적으로 재실행하지 않음 |

실행 JAR에는 운영용 Secret Resolver 구현이 여전히 없다. 따라서 실제 PG를 JAR 서비스에서 사용하려면 별도 안전한 Resolver 제공이 필요하다. 이번 외부 테스트 준비는 기존 Probe 전용 Resolver를 사용하며 이를 운영 JAR에 넣지 않는다.

## 별도 승인 후 실제 테스트 절차

자세한 환경변수 이름과 보안 입력 명령은 [KAKAOPAY_COMPATIBILITY.md](KAKAOPAY_COMPATIBILITY.md)를 따른다.

1. 외부 개발 Mock 거래 한 건/2400 KRW와 Ready·Approve·Order·전체 Cancel·Order 범위를 명시하여 별도 승인.
2. TC0ONETIME과 개발용 Secret만 전용 터미널에 준비. 등록된 HTTPS 콜백 페이지 및 개발자센터 Web 도메인 확인.
3. prepareKakaoSandbox로 컴파일/classpath만 준비. 일반 JUnit이나 JAR 검증 명령은 외부 PG를 호출하지 않음.
4. 명시적 실행 허용 설정 후 대화형 터미널에서 기존 Probe 실행. PC URL의 테스트 인증 후 콜백 URL을 숨김 입력.
5. 불명확하면 즉시 중단하고 저장된 거래로 조회 대사. 금융 요청을 다시 실행하지 않음. 종료 후 Secret/허용 환경변수 제거.

일반 PAID 고객 환불 API는 추가하지 않는다. Probe의 취소는 승인 응답 반영 전에 취소 의도를 저장하는 기존 보상 정책이다.

## 미검증/남은 위험

실제 개발 Secret/도메인 등록/브라우저 callback/TLS 연결/PG 응답·오류·조회 지연은 검증하지 않았다. 최신 Cancel 문서 전체와 전체 오류 코드 분류의 확인 한계는 기존 호환성 문서에 남아 있다.
정산 가능 여부, 실결제 안전성 또는 운영 배포 적합성을 이 시작 검사만으로 보장하지 않는다. 기본 application.yaml의 create-drop은 이번에도 변경하지 않았으며 운영에서는 별도 안전한 스키마 정책이 필요하다.

## 실행 결과

| 범위 | 결과 |
| --- | --- |
| bootJar 및 실제 JAR 검증 | BUILD SUCCESSFUL. java -jar 시작, 고객 미인증 401, 별도 로더 Bean 검사 통과 |
| 운영 runtimeClasspath 해석 | 성공. DevTools/FAILED 항목 없음 |
| 전체 JUnit | 1457개, 실패 0 / 오류 0 / 건너뜀 0 |
| 실제 MySQL 8.0.37/InnoDB JUnit | 540개, 실패 0 / 오류 0 / 건너뜀 0 |
| 기존 로컬 Mock Probe | Ready/Approve/Order/Cancel/Order 및 최종 CANCELLED/CANCELED 확인, 정상 종료 |
| 외부 PG | 실행하지 않음 |
| DevTools 다운로드/bootRun | 네트워크 권한 오류로 다운로드 미완료. bootRun/hot reload 실행 미검증 |

로그: build/executable-jar-verification.log, build/jar-isolation-final.log, build/jar-final-regression.log, build/jar-runtime-dependencies.log.
JAR 진단 Bean 검사에서는 실제 Resolver 부재의 fail-closed 상태를 확인했다. 운영용 Resolver까지 준비되었다는 의미가 아니다.
새 JUnit을 추가하거나 기존 테스트를 제거하지 않았으며, JAR 프로세스 실행 검사는 JUnit 개수와 별도다.

최종 JAR 크기는 70,601,945바이트, SHA-256은 E3800B8031E77770722EDF62576FFA3E2CB488BC881B42557A98F80BD37055BC다.
지원 산출물 경로 분리 후 재실행한 JAR 검사도 BUILD SUCCESSFUL(1분 20초)로 종료했다. 최종 증거 디렉터리는 build/jar-verification/034a6497-902e-4dbc-8964-9adb50984b4f다.
이번 작업의 JAR 검사 2회 및 JUnit용 독립 MySQL 인스턴스 7개, 합계 9개 모두 Shutdown complete를 확인했다.

변경 파일: build.gradle, src/sandbox/java/com/stayon/stayon_backend/service/ExecutableJarVerifier.java, src/sandbox/java/com/stayon/stayon_backend/service/JarSmokeInitializer.java, 이 문서 및 KAKAOPAY_COMPATIBILITY.md.
