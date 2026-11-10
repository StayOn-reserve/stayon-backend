# Gradle 캐시 및 개발 Secret 재검증 (2026-11-09)

## 권한 진단과 조치

- 기본 경로: `C:\dev\stayon-backend\.gradle-user-home`.
- 실제 오류: `wrapper\dists\gradle-9.5.1-bin\iq79hdu3mqx29lgffhp8bfmx\gradle-9.5.1-bin.zip.lck`의 `RandomAccessFile` 열기에서 접근 거부. Wrapper 단계에서 실패하므로 테스트 결함이 아니다.
- 같은 잠금 파일을 PowerShell/.NET으로 읽기·쓰기 열기해도 거부된다. 같은 캐시 루트에 새 일반 파일 생성도 거부되어, 기존 잠금의 경합만으로 설명되지 않는다.
- 실행 계정은 `DESKTOP-MLEIN3E\CodexSandboxOffline`(SID 끝 1007), 저장소 소유자는 Kevin(SID 끝 1001)이다. 기본 캐시와 잠금 파일 소유자는 오히려 실행 계정이다. 따라서 소유자가 다르다는 사실만으로 캐시 오류를 설명할 수 없다.
- `Get-Acl`, `icacls`, `whoami /groups`에서 일반 Windows 그룹의 Modify 허용을 확인했다. 그러나 실제 Sandbox 프로세스에서는 쓰기가 거부된다. 기본 캐시와 대체 캐시의 상속된 SID ACL 항목도 다르다. 일반 DACL 표시와 Sandbox의 실효 접근 권한을 구분해야 한다.
- 확인된 직접 원인은 **기본 캐시 경로의 실효 쓰기 권한 부족**이다. 제한 토큰/파일시스템 정책 중 정확한 거부 계층은 이 세션의 조회 권한으로 확정하지 못했다. Kevin 계정이나 비 Sandbox 실행으로 직접 비교하지 않았다.
- `Get-Process` 조사에서 초기 Java/Gradle 프로세스는 표시되지 않았으나, WMI `Win32_Process` 조회는 접근 거부되었고 handle 도구도 없어 잠금 소유 프로세스가 없다고 단정하지 않는다. 기존 MySQL 프로세스는 건드리지 않았다.
- 기존 `.gradle-codex-home`은 잠금 파일 열기와 일반 파일 생성이 모두 성공했다. 이 별도 캐시로 프로세스별 `GRADLE_USER_HOME`만 변경하여 검증을 수행했다. 프로젝트 밖 쓰기는 현재 정책상 허용되지 않아 저장소 안의 별도 캐시를 사용했다.
- ACL·소유권·전역 환경변수·전역 Git 설정은 변경하지 않았다. 잠금 파일 및 캐시를 삭제하지 않았으며 권한 상승도 하지 않았다.
- Git의 소유권 경고는 명령별 `git -c safe.directory=C:/dev/stayon-backend ...`로만 처리했다.

진단 증거: `build/secret-cache-acl.json`. 읽기·쓰기 확인용 임시 파일은 생성 성공한 파일만 즉시 정리했다.

## 실행 명령

모든 Gradle 프로세스에 다음 환경을 명시했다. 기본 캐시에서 첫 Resolver 실행은 접근 거부로 실패했고, 이후 대체 캐시에서 실행했다.

```powershell
$env:JAVA_HOME='C:\Users\Kevin\.jdks\corretto-21.0.6'
$env:GRADLE_USER_HOME="$PWD\.gradle-codex-home"
.\gradlew.bat --offline --no-daemon compileJava test --tests '*DevelopmentPgSecretResolverTests'
.\gradlew.bat --offline --no-daemon test --tests '*SecurityTests' --tests '*ConfigurationTests' --tests '*KakaoPaySandboxSafetyTests' --tests '*DevelopmentPgSecretResolverTests' --rerun-tasks
.\gradlew.bat --offline --no-daemon compileJava test
.\gradlew.bat --offline --no-daemon bootJar verifyExecutableJar prepareKakaoSandbox
```

MySQL 검증은 전체 test에 포함되는 `MySql*Tests` 7개 클래스가 수행한다. 각각 새 UUID datadir, `--no-defaults`, 임의 loopback 포트 및 접속 전 PID/데이터 경로 검증을 사용한다. 운영 DB 또는 기존 로컬 서버는 사용하지 않는다.

## 테스트 결과

| 범위 | 결과 |
| --- | --- |
| Resolver 단독 | 5개 통과 |
| Resolver 및 보안·설정 선택 실행 | 109개 통과 |
| 전체 JUnit | 38 suites, 1,462개; 실패 0, 오류 0, 건너뜀 0 |
| 위 전체 실행에 포함된 실제 MySQL | 7 suites, 540개; 실패 0, 오류 0, 건너뜀 0 |

MySQL 실행 파일 버전 로그는 8.0.37이다. `mysqlEnvironmentUsesOwnedServerAndInnoDb`는 기본 엔진과 주요 테이블의 InnoDB, REPEATABLE-READ 및 lock timeout을 직접 검사한다. 해당 테스트를 포함하여 모두 통과했다. JUnit이 생성한 서버 7개 모두 `Shutdown complete`를 확인했다.

Resolver 테스트는 합성 값으로 정상 주입, 미설정·빈 값·공백·제어문자, owner/merchant/provider/CID/reference 불일치, LIVE 설정, 혼합 프로필 거부 및 Bean 등록 경계를 확인한다. 실제 개발 키의 발급 환경과 유효성은 검증하지 않는다.

집계 증거: `build/secret-security-summary.json`, `build/secret-regression-summary.json`, `build/secret-mysql-evidence.json`, `build/test-results/test/`. 실행 로그: `build/secret-security-verification.log`, `build/secret-full-regression.log`.

## 실행 JAR 및 외부 호출 없는 preflight

`bootJar verifyExecutableJar prepareKakaoSandbox`는 BUILD SUCCESSFUL로 종료했다. 증거 디렉터리는 `build/jar-verification/f9ea7389-9ac4-48c0-b2b0-e12646f721bc/`이며 아래 결과 파일을 직접 확인했다.

- `result.properties`: archiveIsolation, javaJarStarted, customerUnauthenticated401, beanVerification 모두 true, externalPgCalls=0.
- `beans.properties`: PaymentOrchestrator/KakaoPayAdapter/PgSecrets 구성, SANDBOX 환경, worker 비활성, 환경변수 주입 및 dev가 아닌 프로필의 Resolver 부재 시 차단 모두 true.
- `development-beans.properties`: dev 프로필의 DevelopmentPgSecretResolver Bean 및 합성 Secret 환경변수 주입, SANDBOX 환경, worker 비활성 모두 true.
- 기본 Manifest를 통한 `java -jar` 시작 검증 후, 동일 애플리케이션 JAR을 PropertiesLauncher와 별도 진단 support JAR로 실행하여 Bean 구성을 검사했다. Bean 진단은 기본 `java -jar` 실행과 별도 단계다.
- JUnit 7개와 JAR 검증 1개를 합한 신규 MySQL 서버 총 8개 모두 `Shutdown complete`를 확인했다.

실행 JAR: `build/libs/stayon-backend-0.0.1-SNAPSHOT.jar`.
SHA-256: `C0FE8D4433FAF40B2E53EDAF7A6C15C3A78A0D3BB5C8686F29F698AB2466947E`.
실행 로그: `build/secret-jar-verification.log`.

아래 스크립트는 각 자식 JVM을 **반드시 `--preflight` 인수로만** 실행했다. 합성 사례는 OS 실행 변수만 남긴 새 환경에 가짜 Secret과 예제 HTTPS 도메인을 넣었다. 부모 프로세스의 환경변수는 변경하지 않았다. 실제 키나 도메인 등록 확인을 대신하는 결과가 아니다.

```powershell
& .\build\verify-secret-preflight.ps1
# 각 자식 프로세스가 실행하는 명령:
& "$env:JAVA_HOME\bin\java.exe" -cp (Get-Content build/kakaopay-sandbox-classpath.txt -Raw) com.stayon.stayon_backend.service.KakaoPaySandboxProbe --preflight
```

| preflight 사례 | 실제 종료 코드 | 판정 |
| --- | --- | --- |
| 현재 환경 그대로 | 1 | 준비 조건 미충족 차단 확인 |
| 합성 정상 설정 | 0 | DB/HTTP 호출 없음 메시지 확인 |
| Secret 누락 | 1 | 차단 확인 |
| LIVE 환경 | 1 | 차단 확인 |
| LIVE Secret 종류 | 1 | 차단 확인 |
| CID 불일치 | 1 | 차단 확인 |
| 혼합 프로필 | 1 | 차단 확인 |

7개 실행 기대값이 모두 일치했다. 증거: `build/secret-preflight-results.json`. preflight 분기는 Spring context/DB/HTTP 생성 전에 반환하거나 예외로 종료한다. 실제 외부 개발 API 준비 상태는 통과하지 않았고 외부 통신도 수행하지 않았다.

컴파일에는 기존 deprecated/unchecked 경고 및 sandbox 소스셋의 apiguardian enum annotation 관련 17개 경고가 있었다. 빌드·테스트 실패는 없으며 이 작업에서 의존성을 추가하거나 경고 수정을 위한 범위 확대는 하지 않았다. `git diff --check`도 통과했다(AGENTS.md의 기존 LF/CRLF 안내 경고만 출력).

## 변경 범위와 남은 사항

이 작업에서 변경한 소스 관리 대상은 `.gitignore`(대체 캐시 제외), `KAKAOPAY_DEVELOPMENT_SECRET.md`(실행 캐시와 결과 링크), 이 보고서다. 기존 구현과 테스트는 수정하지 않았다. `build/`의 로그·검증 스크립트·산출물은 Git 제외 대상이다. 기존 미커밋 변경 사항은 보존했다.

기본 캐시 경로의 권한 문제 자체는 남아 있으므로 대체 캐시를 계속 명시해야 한다. 권한 복구를 위해 Sandbox 밖에서 ACL/제한 토큰 정책을 추가 조사할 수 있으나 이번 작업에서는 변경하지 않았다.

실제 카카오페이 개발 API 시험 전에는 DEV Secret 발급 환경, TC0ONETIME/SANDBOX 가맹점과 DB reference 일치, HTTPS callback 및 개발자센터 도메인 등록, 개발 DB/Redis/JWT/OTP 설정을 별도로 준비·확인해야 한다. 실제 결제·취소를 포함한 외부 실행은 별도 명시적 승인 후 수행한다. 이번 검증에서 실제 PG API, 결제·취소, 운영 DB, commit/push/배포는 수행하지 않았다.
