# DECISIONS.md — 기술적 쟁점과 의사결정

---

## 1. 재고 정합성 — Redis Lua 스크립트 원자적 감소

### 상황
00시에 1,000명이 동시에 POST /bookings를 요청할 때, 재고 10개가 정확히 10건만 성공해야 한다.

### 선택지
| 방식 | 설명 | 문제점 |
|------|------|--------|
| DB SELECT + UPDATE | 재고 조회 후 감소 | 두 쿼리 사이 경쟁 조건 → 초과 판매 가능 |
| DB SELECT FOR UPDATE | 비관적 락 | 락 직렬화로 TPS 급감, 커넥션 풀 고갈 위험 |
| Redis DECR | 단순 감소 | DECR 후 음수 확인 → 음수 도달 전 여러 스레드 통과 가능 |
| **Redis Lua 스크립트** | 조회-감소를 원자적 실행 | 없음 |

### 결정: Redis Lua 스크립트
```lua
local current = tonumber(redis.call('GET', KEYS[1]))
if current == nil then return -1 end
if current < tonumber(ARGV[1]) then return -2 end
return redis.call('DECRBY', KEYS[1], ARGV[1])
```
Redis는 싱글 스레드로 Lua 스크립트를 원자적으로 실행하므로, 재고 조회와 감소 사이의 경쟁 조건이 원천 차단된다. `DECRBY` 반환값으로 성공/실패를 즉시 판단하여 DB 접근 없이 초기 거부(early rejection)가 가능하다.

---

## 2. 고가용성 — 순간 TPS 스파이크 대응

### 상황
평시 50 TPS → 00시 순간 500~1,000 TPS. 인프라 증설이 제한적인 상황.

### 선택과 근거

**Redis early rejection**
재고 소진 여부를 Redis에서 즉시 판단해 DB 트랜잭션 진입 전에 거부한다. 재고 소진 후 유입되는 수백~수천 TPS가 DB에 도달하지 않으므로, DB 커넥션 풀이 보호된다.

**사용자 중복 구매 차단 (DB UNIQUE 제약)**
`orders.(user_id, product_id)` UNIQUE 제약으로 동일 사용자의 다중 재고 선점 시도를 DB 레벨에서 차단한다. 이로써 한 사용자가 여러 슬롯을 점유하는 불공정 시나리오를 방지한다.

**멱등성 키**
클라이언트의 재시도로 인한 중복 요청이 Redis SETNX로 걸러지므로, 재시도 트래픽이 DB로 유입되지 않는다.

**Redis Lettuce 커넥션 풀**
```yaml
lettuce.pool.max-active: 30
lettuce.pool.max-wait: 200ms
```
커넥션 대기 상한을 200ms로 제한해 스파이크 시 스레드 블로킹을 최소화한다.

---

## 3. 멱등성 — Redis SETNX 기반 중복 처리 방지

### 상황
주문서에서 짧은 간격으로 POST /bookings가 중복 호출될 때, 결제가 두 번 일어나서는 안 된다.

### 선택지
| 방식 | 문제점 |
|------|--------|
| DB `idempotency_key` UNIQUE만 사용 | DB 트랜잭션 직전까지 중복 요청이 처리 파이프라인을 통과 → 재고 이중 선점 후 DB에서 충돌 |
| **Redis SETNX + DB UNIQUE 이중 보호** | Redis가 처리 파이프라인 진입 전에 차단 |

### 결정: Redis SETNX (1차) + DB UNIQUE (2차)
- `SETNX idempotency:{key} PROCESSING` — 최초 요청만 통과, 이후 동일 키는 즉시 거부
- 처리 완료 후 결과(JSON)를 같은 키에 덮어써 캐싱, 재요청 시 캐시된 응답 반환
- DB의 `idempotency_key` UNIQUE 제약은 Redis 장애 시 최후 안전망 역할

---

## 4. Redis 장애 Fallback — 원자적 조건부 UPDATE

### 상황
Redis가 다운되면 재고 선점 불가 → 서비스 전체 중단.

### 선택지 비교
| 방식 | 문제점 |
|------|--------|
| 낙관적 락 (`@Version`) | 동일 행에 대량 동시 UPDATE → 대부분 버전 충돌 → retry storm → DB 부하 폭증 |
| 비관적 락 (SELECT FOR UPDATE) | 락 보유 시간 동안 전체 직렬화 → 처리량 급감, 커넥션 점유 |
| **원자적 조건부 UPDATE** | DB 내부 행 레벨 락으로 원자성 보장, retry 불필요 |

### 결정: 원자적 조건부 UPDATE (DB fallback)
```sql
UPDATE inventories
SET reserved_stock = reserved_stock + 1
WHERE product_id = ? AND (total_stock - reserved_stock) >= 1
```
InnoDB는 단일 UPDATE 실행 시 내부적으로 행 레벨 락을 획득하므로 원자성이 보장된다. `affected rows`가 1이면 성공, 0이면 재고 소진으로 즉시 판단하며 애플리케이션 레벨 retry가 필요 없다.

Resilience4j 서킷브레이커가 Redis 연속 실패를 감지하면 자동으로 이 경로로 전환된다.

```yaml
resilience4j.circuitbreaker.instances.redis-inventory:
  failure-rate-threshold: 50       # 실패율 50% 초과 시 오픈
  wait-duration-in-open-state: 30s # 30초 후 half-open 시도
  sliding-window-size: 10
  minimum-number-of-calls: 5
```

**비용 대비 효과**
Redis 추가 비용(서버 1대): 대량 트래픽을 DB 앞단에서 흡수해 DB 스케일업 비용보다 저렴하다. 서킷브레이커로 Redis 장애 시에도 DB fallback이 자동 작동하므로 가용성이 유지된다.

---

## 5. 결제 확장성 — Strategy 패턴 + Spring Bean 자동 등록

### 상황
현재 결제 수단: 신용카드, Y페이, Y포인트. 향후 새로운 결제 수단 추가 시 BookingService 수정을 최소화해야 한다.

### 선택지
| 방식 | 문제점 |
|------|--------|
| BookingService에 `when` 분기 | 결제 수단 추가마다 핵심 비즈니스 로직 수정 → OCP 위반 |
| **Strategy 패턴 + Spring DI** | 새 전략 Bean 등록만으로 확장 완료 |

### 결정: Strategy 패턴
```kotlin
interface PaymentStrategy {
    val supportedMethod: PaymentMethod
    fun process(request: PaymentRequest): PaymentResult
    fun refund(transactionId: String, amount: Long): RefundResult
}
```
`PaymentProcessor`는 `List<PaymentStrategy>`를 주입받아 `Map<PaymentMethod, PaymentStrategy>`로 관리한다. 새 결제 수단 추가 시:

1. `PaymentStrategy` 구현체 작성
2. `@Component` 등록

→ `BookingService`, `PaymentProcessor`, `PaymentValidator` 코드 수정 없음.

**복합 결제 처리 순서**
`CREDIT_CARD → Y_PAY → Y_POINTS` 순으로 처리한다. 외부 PG 결제를 먼저 처리하고 포인트를 마지막에 차감하여, 실패 시 환불 복잡도를 최소화한다 (포인트는 DB 차감이므로 트랜잭션 내 즉시 롤백 가능).

---

## 6. 결제 실패 대응

### 처리 흐름
1. `PaymentProcessor.processAll()` — 결제를 순서대로 처리
2. 하나라도 실패 시 → 이미 성공한 결제를 역순으로 즉시 환불
3. 재고 복구 → `InventoryRedisService.increment()` (Redis 장애 시엔 이미 DB fallback 경로)
4. Order 상태 → `FAILED`
5. 멱등성 키 → `FAILED` + 실패 사유 저장

### 환불 실패 처리
환불 자체가 실패하는 경우(PG사 장애 등)는 로그에 기록하고, 별도 배치/수동 처리 프로세스로 위임한다. 이중 결제보다 이중 환불 시도가 더 안전하므로 환불은 멱등하게 설계된다.
