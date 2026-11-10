# 개발 Secret 연결

`DevelopmentPgSecretResolver`는 `dev` 단독 프로필에서만 Secret을 해석한다. prod/production/live와 혼합하면 Bean이 등록되지 않으며 다른 혼합 프로필에서도 해석을 거부한다. 환경변수는 Spring 속성 대신 프로세스 환경에서 직접 읽는다.

| 환경변수 | 필수 값 |
| --- | --- |
| SPRING_PROFILES_ACTIVE | dev |
| PAYMENT_MERCHANT_ENVIRONMENT | SANDBOX |
| PAYMENT_KAKAOPAY_LIVE_ENABLED | false |
| KAKAOPAY_SANDBOX_SECRET_KIND | DEV |
| KAKAOPAY_SANDBOX_OWNER_ID | 개발 DB 가맹점 소유자 ID |
| KAKAOPAY_SANDBOX_MERCHANT_ID | 개발 DB 가맹점 ID |
| KAKAOPAY_SANDBOX_DEV_SECRET | Secret key(dev), 접두사 없이 |

DB 가맹점은 provider=KAKAOPAY, environment=SANDBOX, externalId=TC0ONETIME, secretReference=`dev/kakaopay/sandbox/{ownerId}/{merchantId}`이어야 한다. 기존 PgSecrets의 DB 컨텍스트 재검증과 라우팅은 유지된다. 이 작업은 DB를 변경하지 않으며 참조가 다르면 503으로 거부한다. 누락·빈 키·앞뒤 공백·제어문자·범위 불일치·LIVE 설정도 503이다. 키 자체의 발급 환경/유효성은 외부 호출 없이 확인할 수 없으므로 실제 DEV 키를 입력해야 한다. LIVE fallback은 없다.

## PowerShell 실행

기존 DB/Redis/JWT/OTP 등 필수 환경변수는 개발 환경용으로 미리 준비한다. DDL validate와 비활성 PG/worker 설정으로 실행한다. Secret을 명령행·파일에 넣지 않는다. 자식 프로세스가 사용하는 환경변수와 JVM 문자열의 완전한 메모리 삭제는 보장하지 않는다.

```powershell
Set-Location C:\dev\stayon-backend
$env:JAVA_HOME = 'C:\Users\Kevin\.jdks\corretto-21.0.6'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-codex-home'
.\gradlew.bat --offline --no-daemon compileJava test bootJar verifyExecutableJar
if ($LASTEXITCODE -ne 0) { throw '검증 실패' }
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:PAYMENT_MERCHANT_ENVIRONMENT = 'SANDBOX'
$env:PAYMENT_KAKAOPAY_LIVE_ENABLED = 'false'
$env:PAYMENT_KAKAOPAY_ENABLED = 'false'
$env:PAYMENT_RECOVERY_ENABLED = 'false'
$env:PAYMENT_CANCELLATION_WORKER_ENABLED = 'false'
$env:PAYMENT_WEBHOOK_ENABLED = 'false'
$env:KAKAOPAY_SANDBOX_SECRET_KIND = 'DEV'
$env:KAKAOPAY_SANDBOX_OWNER_ID = Read-Host '개발 가맹점 소유자 ID'
$env:KAKAOPAY_SANDBOX_MERCHANT_ID = Read-Host '개발 가맹점 ID'
$secureKey = Read-Host 'Secret key(dev)' -AsSecureString
$keyPointer = [IntPtr]::Zero
try {
    $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureKey)
    $env:KAKAOPAY_SANDBOX_DEV_SECRET = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer)
    & "$env:JAVA_HOME\bin\java.exe" -jar .\build\libs\stayon-backend-0.0.1-SNAPSHOT.jar --spring.jpa.hibernate.ddl-auto=validate
    if ($LASTEXITCODE -ne 0) { throw '실행 실패' }
} finally {
    Remove-Item Env:KAKAOPAY_SANDBOX_DEV_SECRET -ErrorAction SilentlyContinue
    if ($keyPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer) }
    $secureKey.Dispose()
}
```

## 검증

`DevelopmentPgSecretResolverTests`: 정상 주입, 누락, 잘못된 범위/CID/참조, LIVE 혼용, 프로필 Bean 구성. 합성 키만 사용한다.

`verifyExecutableJar`: 기존 검증에 dev 프로필 실제 JAR Resolver Bean 및 합성 환경변수 주입 검사를 추가했다. 새로 소유한 MySQL만 사용하며 PG 호출은 없다.

기존 Probe는 test 프로필 전용 일회성 Resolver를 유지한다. 새 `--preflight` 인수는 기존 Settings의 승인/CID/HTTPS 등록 도메인과 Secret 존재 조건을 검사하고 DB·HTTP를 만들지 않는다. `prepareKakaoSandbox`로 컴파일 후, 기존 Probe 필수 환경변수를 준비한 test 전용 셸에서 다음 명령으로 실행한다. 인수를 생략하면 기존 결제 흐름이므로 실행하지 않는다.

```powershell
& "$env:JAVA_HOME\bin\java.exe" -cp (Get-Content .\build\kakaopay-sandbox-classpath.txt -Raw) com.stayon.stayon_backend.service.KakaoPaySandboxProbe --preflight
```

기본 `.gradle-user-home`에서 접근 거부가 발생하면 이 세션에서 쓰기를 확인한 별도 캐시 `.gradle-codex-home`을 사용한다. 기본 캐시의 잠금 파일·ACL·소유권을 변경하거나 삭제하지 않는다. 캐시는 Git에서 제외하며, 재검증 결과와 권한 진단은 [GRADLE_SECRET_VERIFICATION.md](GRADLE_SECRET_VERIFICATION.md)를 참고한다.
