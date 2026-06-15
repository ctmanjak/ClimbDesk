# First MANAGER bootstrap path

> 작성일: 2026-06-15
>
> 범위: fresh database에서 첫 번째 usable `MANAGER` 계정을 만드는 README-ready 운영 절차.

## 결정

MVP의 첫 `MANAGER` 계정 bootstrap은 production runtime 자동 seed가 아니라 수동 SQL 절차로 지원한다.

선택하지 않은 대안:

- Migration에 관리자 계정 또는 비밀번호를 넣지 않는다. Migration은 schema ownership에만 사용하고 secret을 repository에 남기지 않는다.
- `CommandLineRunner`, `ApplicationRunner`, bootstrap profile runner를 추가하지 않는다. Production profile에서 실수로 자동 계정이 생성될 위험을 만들지 않는다.
- `POST /api/v1/admin-users` 권한을 완화하지 않는다. API Spec대로 `MANAGER` 전용으로 유지한다.
- 일반 사용자 signup 또는 public bootstrap API를 추가하지 않는다. MVP 범위를 넓히지 않는다.

## 지원 절차

이 절차는 fresh database에 schema migration이 적용된 뒤 한 번 실행한다. 실제 이메일과 비밀번호는 운영자가 로컬 shell 또는 secret manager에서 관리하고 repository에 커밋하지 않는다.

### 1. 비밀번호 해시 생성

`password_hash`는 현재 애플리케이션의 `Pbkdf2PasswordVerifier.encode(...)`와 같은 형식이어야 한다.

현재 형식:

```plain text
pbkdf2_sha256$<iterations>$<base64(salt)>$<base64(hash)>
```

현재 파라미터:

```plain text
algorithm: PBKDF2WithHmacSHA256
iterations: 210000
salt bytes: 16
hash bytes: 32
key length: 256 bits
```

JDK가 설치된 로컬 환경에서는 `jshell`로 해시를 만들 수 있다. 입력한 raw password는 출력하지 않고, 출력된 hash만 다음 SQL에 사용한다.

```shell
jshell
```

```java
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

var password = new String(System.console().readPassword("Bootstrap MANAGER password: "));
var salt = new byte[16];
new SecureRandom().nextBytes(salt);
var spec = new PBEKeySpec(password.toCharArray(), salt, 210000, 256);
var hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
System.out.println("pbkdf2_sha256$210000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash));
```

### 2. 첫 MANAGER row 삽입

`<manager-email>`과 `<password-hash>`를 실제 값으로 바꿔 실행한다.

```sql
insert into admin_users (
  email,
  password_hash,
  role,
  status,
  created_at,
  updated_at
) values (
  '<manager-email>',
  '<password-hash>',
  'MANAGER',
  'ACTIVE',
  now(),
  now()
);
```

이 SQL은 첫 bootstrap 전용이다. 이후 관리자 추가, role 변경, 활성화, 비활성화는 `MANAGER`로 로그인한 뒤 `POST /api/v1/admin-users`와 관리 API를 사용한다.

### 3. 로그인 검증

애플리케이션을 실행한 뒤 public login API로 bootstrap 계정이 usable한지 확인한다.

```shell
curl -i -X POST "$CLIMBDESK_BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"<manager-email>","password":"<raw-password>"}'
```

성공 기준:

```plain text
HTTP/1.1 200
tokenType = Bearer
adminUser.role = MANAGER
adminUser.status = ACTIVE
```

그 다음 보호 API 검증은 반환된 access token을 사용한다.

```shell
curl -i -X POST "$CLIMBDESK_BASE_URL/api/v1/admin-users" \
  -H "Authorization: Bearer <access-token>" \
  -H "Content-Type: application/json" \
  -d '{"email":"staff@climbdesk.local","password":"password1234","role":"STAFF"}'
```

## README 반영 메모

README runtime guide는 이 문서의 절차를 요약해서 포함해야 한다.

- 자동 demo 계정이 없다고 명시한다.
- 첫 `MANAGER` 계정은 수동 SQL bootstrap으로 만든다고 명시한다.
- raw password, generated hash, database credentials, JWT secret은 repository에 저장하지 말라고 명시한다.
- `POST /api/v1/admin-users`는 bootstrap 이후에만 사용할 수 있는 `MANAGER` 전용 API라고 명시한다.

## 검증 기준

이 문서 절차는 다음 기존 코드와 테스트에 맞춰 정의됐다.

- `Pbkdf2PasswordVerifier.encode(...)`가 생성하는 hash 형식과 PBKDF2 파라미터.
- `admin_users` table의 `email`, `password_hash`, `role`, `status`, `created_at`, `updated_at` 필수 컬럼.
- `AuthLoginIntegrationTest`의 `active manager can log in with persisted admin user` 테스트: 같은 해시 방식으로 저장된 ACTIVE MANAGER가 `POST /api/v1/auth/login`으로 로그인 가능함을 검증한다.
- `AdminUserController`의 `POST /api/v1/admin-users` `@PreAuthorize("hasRole('MANAGER')")`: bootstrap 이후에도 관리자 생성 API는 public이 아니다.
