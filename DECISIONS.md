# DECISIONS.md — 기술적 쟁점과 의사결정

설계 과정에서 고민한 트레이드오프와 선택의 근거를 기록한다.

---

## 1. TPS 급증 대응 — 시스템 붕괴 방지 구조

### 1-a. 현재 구조: 평시 50 TPS, 프로모션 시 순간 500~1,000 TPS

#### 핵심 관찰

초특가 숙소 상품 (10개 한정) -> 한 숙소 상품의 재고가 10개라는 의미로 해석.

현재 구조에서 스케일 아웃이 불가능한 자원인 DB에 트래픽이 꽂히는게 병목 지점이라고 판단.

500~1,000 TPS가 유입되더라도 성공할 수 있는 요청은 10건에 불과하다. 나머지 490~990 TPS는 모두 거부 대상이다. 따라서 이 거부를 얼마나 빠르고 가볍게 처리하느냐가 DB 부하를 결정한다.

#### 구조별 역할

| 계층 | 구조                                 | 역할                                                                        |
|------|------------------------------------|---------------------------------------------------------------------------|
| Redis | Lua 스크립트 원자적 DECR                  | 재고 소진 여부를 DB 접근 없이 즉시 판단, 초과 요청 조기 거부                                     |
| Redis | 멱등성 SETNX                          | 재시도·중복 요청이 DB에 도달하기 전 차단                                                  |
| DB | `(user_id, product_id)` UNIQUE 인덱스 | 한 사용자가 두 상품을 선점하는 시나리오를 DB 레벨에서 차단                                        |
| App | `InventoryInitializer`             | 개발 산출물 검증 편의를 위해 앱 기동 시 DB 재고를 Redis에 동기화하여 Redis 키 미존재(cold start) 상황 방지 |
| App | Resilience4j Circuit Breaker       | Redis 연속 실패 시 자동으로 DB fallback 경로 전환                                      |

#### 트래픽 흐름

```
500~1,000 TPS 유입
│
├─ Redis Lua DECR
│   ├─ 재고 소진 후 (490~990 TPS) ──► 즉시 SOLD_OUT 반환 (DB 미접근)
│   └─ 재고 있는 초기 10건 ──────────► DB 트랜잭션 진행
│
└─ 멱등성 SETNX
    └─ 동일 키 재시도 ────────────────► 즉시 200 PENDING 반환 (DB 미접근)
```

결과적으로 DB에 도달하는 요청은 최대 10건이며, DB 커넥션 풀은 스파이크의 직접적인 영향을 받지 않는다.

---

### 1-b. 메시지큐 도입을 고려했으나 도입하지 않은 이유

스파이크 트래픽을 메시지큐로 받아 컨슈머가 순차 처리하는 방식을 검토했다. 도입하지 않은 근거는 다음과 같다.

**① 재고 수가 병목을 결정한다**

메시지큐로 요청을 받아도 처리 완료 가능한 건수는 10건이다. 나머지는 컨슈머 단계에서 재고 소진으로 거부된다. 큐에 쌓이는 비용을 치르고도 얻는 이득이 없다.

**② 스파이크 지속 시간이 짧다**

메시지큐는 지속적인 고부하를 시간 축으로 평탄화하는 데 적합하다. 1~5분짜리 스파이크는 Redis 조기 거부로 충분히 흡수된다. 이미 재고 10건 외의 모든 요청은 Redis 단계에서 즉시 거부되므로 DB 부하 자체가 스파이크와 무관하다.

**③ 선착순 UX와 비동기 응답의 충돌**

선착순 특성상 클라이언트는 "지금 이 순간 성공/실패"를 즉시 알아야 한다. 메시지큐를 도입하면 결과를 비동기로 수신해야 하며, 이를 위해 폴링 API 또는 웹소켓이 추가로 필요하다. 이는 클라이언트 복잡도 증가와 응답 지연으로 이어진다.

**④ 운영 비용 대비 효과**

메시지큐를 도입하는건 운영 요소 (데드레터 처리, 메시지 보존 정책, 별도 컨슈머 운영)가 추가되어 현재 규모 대비 오버헤드라 판단. Redis 기반의 현재 구조가 동일한 문제를 더 단순하게 해결한다.

**메시지큐를 재고려할 시점**: 평시 트래픽 자체가 수천 TPS로 올라가 Redis 조기 거부 이후에도 DB에 도달하는 유효 요청이 지속적으로 많아질 때.

---

### 1-c. 향후 평시 트래픽이 500~1,000 TPS가 된다면

이 경우는 성격이 다르다. 스파이크가 아닌 상시 고부하로, 유효 예약 요청 자체가 많아진다는 의미다. (단일 프로모션 상품 10개가 아니라 상품 수와 재고가 대규모로 늘어난 상황을 전제한다.)

상시 고부하가 검증되면 메시지큐 도입 재검토. 예약 요청을 큐로 받아 컨슈머가 처리하는 구조로 전환하되, 응답 방식을 비동기(폴링 or SSE)로 변경하거나, **예약 진행중** 상태를 두고 별도 재처리 배치로 최종 일관성을 보장.

---

## 2. 중복 결제 방지 — 멱등성 구조

### 상황

주문서에서 버튼 중복 클릭, 네트워크 타임아웃 후 재시도 등으로 짧은 간격에 동일 요청이 복수 유입될 수 있다. 결제가 두 번 실행되거나 재고가 이중 선점되어서는 안 된다.

### 선택지

| 방식 | 문제점 |
|------|--------|
| DB `UNIQUE` 제약만 사용 | 중복 요청이 재고 선점 단계까지 통과한 뒤 DB 충돌 → 재고 이중 감소 후 DB 단에서 실패 |
| 애플리케이션 레벨 synchronized | 단일 인스턴스에서만 유효, 다중 인스턴스 환경에서 무의미 |
| **Redis SETNX (1차) + DB UNIQUE (2차)** | 중복 요청을 비즈니스 로직 진입 전에 차단 |

### 결정: Redis SETNX + DB UNIQUE 이중 보호

```
첫 요청  → SETNX "PROCESSING" 성공 → 재고 선점 → 결제 → DB 저장 → orderId 기록
재시도   → SETNX 실패 (키 존재)
           └─ "PROCESSING" → 200 PENDING 반환 (처리 중임을 클라이언트에 전달)
           └─ orderId     → DB 재조회 → 동일 응답 반환
```

**처리 중 응답을 200으로 반환하는 이유**: 클라이언트 입장에서 200를 받으면 성공으로 처리해 재시도를 멈출 수 있다. 200 PENDING은 "진행 중이니 잠시 기다려달라"는 의미이다.

**Redis에 orderId를 저장하는 이유**: 완료된 요청의 재시도는 orderId로 DB를 재조회해 응답을 재구성한다. DB가 단일 진실 소스(Single Source of Truth)이므로 캐시와의 불일치가 발생하지 않고, 모수 자체가 적어 부하를 크게 고민하지 않아도 된다 판단.

**실패 시 키를 삭제하는 이유**: 일시적 오류(PG 타임아웃, Redis 순간 장애 등)로 실패한 요청은 재시도했을 때 성공해야 한다. 실패 결과를 캐싱하면 재시도가 영구적으로 차단된다. 키를 삭제하면 다음 요청이 새 트랜잭션으로 처음부터 시작된다.

---

## 3. 결제 확장성 — Strategy 패턴 + Spring Bean 자동 등록

### 상황

현재 결제 수단은 신용카드, Y페이, Y포인트다. 향후 새로운 결제 수단이 추가될 때 `BookingService`의 비즈니스 로직 수정을 최소화해야 한다.

### 선택지

| 방식 | 문제점 |
|------|--------|
| `BookingService`에 `when` 분기 | 결제 수단 추가마다 핵심 예약 흐름 수정 → OCP 위반, 테스트 범위 확대 |
| **Strategy 패턴 + Spring DI** | 새 전략 Bean 등록만으로 확장 완료, 기존 코드 무수정 |

### 결정: Strategy 패턴

```kotlin
interface PaymentStrategy {
    val supportedMethod: PaymentMethod
    fun process(request: PaymentRequest): PaymentResult
    fun refund(transactionId: String, amount: Long): RefundResult
}
```

`PaymentProcessor`는 `List<PaymentStrategy>`를 주입받아 `Map<PaymentMethod, PaymentStrategy>`로 관리한다. 새로운 결제 수단을 추가할 때 변경이 필요한 범위:

- **추가**: `PaymentStrategy` 구현체 + `@Component` 등록, `PaymentMethod` enum 값 추가
- **무수정**: `BookingService`, `PaymentProcessor`, `PaymentValidator`

`PaymentValidator`의 조합 규칙은 새 수단 추가 시 검토가 필요하지만, 이는 비즈니스 정책 변경이므로 수정이 타당하다. 예약 흐름 자체는 수정되지 않는다.

### 복합 결제 처리 순서

`CREDIT_CARD → Y_PAY → Y_POINTS` 순서로 처리한다.

외부 PG 결제를 먼저 시도하고 Y포인트 차감을 마지막에 수행한다. Y포인트는 DB 차감이므로 `@Transactional` 롤백으로 즉시 복구가 가능하다. 반면 외부 PG 결제는 환불 API 호출이 필요하다. Y포인트를 먼저 차감했다가 PG 결제가 실패하면 포인트 환불 API도 별도로 호출해야 하는 복잡도가 생긴다. 이 순서를 지킴으로써 실패 시 환불 범위가 최소화된다.

---

## 4. 장애 대응 및 예외 처리

### 4-a. Redis 장애 시 Fallback 전략

#### 상황

Redis가 다운되면 재고 선점(Lua DECR)이 불가능해져 서비스 전체가 중단된다.

#### 선택지

| 방식 | 문제점 |
|------|--------|
| 낙관적 락 (`@Version`) | 동일 행에 대량 동시 UPDATE → 대부분 버전 충돌 → retry storm → DB 부하 폭증 |
| 비관적 락 (`SELECT FOR UPDATE`) | 락 보유 시간 동안 전체 직렬화 → 처리량 급감, 커넥션 점유 증가 |
| **원자적 조건부 UPDATE** | 단일 쿼리로 원자성 보장, retry 불필요 |

#### 결정: 원자적 조건부 UPDATE (QueryDSL)

```sql
UPDATE inventories
SET reserved_stock = reserved_stock + 1
WHERE product_id = ?
  AND (total_stock - reserved_stock) >= 1
```

InnoDB는 단일 UPDATE에서 내부적으로 행 레벨 락을 획득하므로 원자성이 보장된다. `affected rows = 1`이면 성공, `0`이면 재고 소진으로 즉시 판단하며 애플리케이션 레벨 retry가 필요 없다.

Resilience4j Circuit Breaker가 Redis 연속 실패를 감지해 자동으로 이 경로로 전환한다.

```yaml
resilience4j.circuitbreaker.instances.redis-inventory:
  failure-rate-threshold: 50       # 실패율 50% 초과 시 서킷 오픈
  wait-duration-in-open-state: 30s # 30초 후 half-open 재시도
  sliding-window-size: 10
  minimum-number-of-calls: 5
```

**트레이드오프**: Redis 장애 시 재고 선점이 DB로 내려오므로 DB 부하가 증가한다. 다만 Redis 장애 자체가 이미 비정상 상황이며, 가용성(장애 시에도 예약 처리 계속)을 선택했다. Redis가 복구되면 서킷이 half-open → closed로 복귀한다.

---

### 4-b. 결제 실패 케이스 대응

#### 결제 실패 시 처리 흐름

```
PaymentProcessor.processAll() 실패
│
├─ 1. 이미 성공한 외부 PG 결제를 역순으로 환불 (rollbackAll)
│      └─ Y포인트는 @Transactional 롤백으로 자동 복구 (별도 환불 호출 불필요)
│
├─ 2. 재고 복구: InventoryRedisService.increment(productId)
│
├─ 3. @Transactional 롤백: Order, Payment 모두 DB 미저장
│      └─ FAILED 상태의 주문이 DB에 남지 않음
│
└─ 4. 멱등키 release (Redis key 삭제): 클라이언트 재시도 허용
```

#### FAILED 주문을 DB에 저장하지 않는 이유

FAILED 주문을 저장하면 `uq_orders_user_product` UNIQUE 제약 때문에 동일 사용자의 재시도가 `ALREADY_PURCHASED`로 막힌다. 즉, 결제 실패 후 재시도가 불가능해진다. 실패한 주문은 DB가 아닌 애플리케이션 로그로 감사 가능하므로 DB 저장의 필요성이 없다.

#### 환불 실패 처리

환불 API 호출이 실패하는 경우(PG사 장애 등)는 예외를 삼키고 로그에 기록한다. 환불은 원거래 `transactionId`를 멱등키로 사용하므로 동일 `transactionId`로 재시도해도 이중 환불이 발생하지 않는다. 별도 배치 또는 운영팀의 수동 처리 프로세스로 위임한다. 환불 실패가 원래 결제 실패 응답 자체를 막아서는 안 된다.

#### Y포인트 처리의 특수성

Y포인트는 DB를 직접 차감하므로 외부 PG와 달리 `@Transactional` 롤백만으로 복구된다. 이 때문에 처리 순서에서 Y포인트를 마지막으로 배치했다. 신용카드 또는 Y페이가 실패할 경우 포인트는 이미 차감되지 않았으므로 별도 환불 로직이 불필요하다.
