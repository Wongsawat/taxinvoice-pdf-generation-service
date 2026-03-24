# Fix EbmsSentNotificationEvent correlationId Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `EbmsSentNotificationEvent` so `TraceEvent.getCorrelationId()` returns the correct value by adding `correlationId` to the `create()` factory, private constructor, and `@JsonCreator`.

**Architecture:** Add `String correlationId` as a new second parameter (after `sagaId`) through the entire call chain: `EbmsSendingEventPublisher.publishSuccess()` → `NotificationEventPublisher.publishEbmsSentNotification()` → `EbmsSentNotificationEvent.create()` → private constructor → `super()`.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Jackson, JUnit 5, Mockito, Maven (`mvn test`)

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/com/wpanther/ebmssending/domain/event/EbmsSentNotificationEvent.java` |
| Modify | `src/main/java/com/wpanther/ebmssending/infrastructure/messaging/NotificationEventPublisher.java` |
| Modify | `src/main/java/com/wpanther/ebmssending/infrastructure/messaging/EbmsSendingEventPublisher.java` |
| Modify | `src/test/java/com/wpanther/ebmssending/domain/event/EbmsSentNotificationEventTest.java` |
| Modify | `src/test/java/com/wpanther/ebmssending/infrastructure/messaging/NotificationEventPublisherTest.java` |
| Modify | `src/test/java/com/wpanther/ebmssending/adapters/outbound/messaging/AsyncNotificationEventPublisherTest.java` |
| Modify | `src/test/java/com/wpanther/ebmssending/infrastructure/messaging/EbmsSendingEventPublisherTest.java` |

---

### Task 1: Add a regression test that proves the current bug

`EbmsSentNotificationEvent.create()` currently passes `sagaId` to the `super()` but never sets `correlationId`, so `TraceEvent.getCorrelationId()` returns `null`. Add a test that asserts both fields independently — it will fail until the fix is applied.

**Files:**
- Modify: `src/test/java/com/wpanther/ebmssending/domain/event/EbmsSentNotificationEventTest.java`

- [ ] **Step 1: Add regression test**

Find an appropriate location in `EbmsSentNotificationEventTest.java` and add:

```java
@Test
void getCorrelationId_shouldReturnProvidedValue() {
    // Arrange
    EbmsSentNotificationEvent event = EbmsSentNotificationEvent.create(
            "saga-001",
            "corr-abc",   // <-- new second parameter (doesn't exist yet)
            "doc-123", "inv-001", "INV-001",
            "TAX_INVOICE", "EBMS-MSG-1", Instant.now());

    // Assert
    assertThat(event.getCorrelationId()).isEqualTo("corr-abc");
    assertThat(event.getSagaId()).isEqualTo("saga-001");
}
```

This test calls a `create()` overload that doesn't exist yet — it won't compile.

- [ ] **Step 2: Verify the test cannot compile**

```bash
cd /path/to/ebms-sending-service
mvn test-compile 2>&1 | grep -A3 "ERROR\|cannot find symbol"
```

Expected: compilation error.

---

### Task 2: Fix `EbmsSentNotificationEvent.java`

**Files:**
- Modify: `src/main/java/com/wpanther/ebmssending/domain/event/EbmsSentNotificationEvent.java`

- [ ] **Step 1: Update the `create()` factory method**

Replace the existing `create()` method (lines 48–57):

```java
// BEFORE
public static EbmsSentNotificationEvent create(
        String sagaId,
        String documentId, String invoiceId, String invoiceNumber,
        String documentType, String ebmsMessageId, Instant sentAt) {

    return new EbmsSentNotificationEvent(
        sagaId, documentId, invoiceId, invoiceNumber,
        documentType, ebmsMessageId, sentAt
    );
}
```

With:

```java
// AFTER
public static EbmsSentNotificationEvent create(
        String sagaId,
        String correlationId,
        String documentId, String invoiceId, String invoiceNumber,
        String documentType, String ebmsMessageId, Instant sentAt) {

    return new EbmsSentNotificationEvent(
        sagaId, correlationId, documentId, invoiceId, invoiceNumber,
        documentType, ebmsMessageId, sentAt
    );
}
```

- [ ] **Step 2: Update the private constructor**

Replace the existing private constructor (lines 62–74):

```java
// BEFORE
private EbmsSentNotificationEvent(
        String sagaId,
        String documentId, String invoiceId, String invoiceNumber,
        String documentType, String ebmsMessageId, Instant sentAt) {

    super(sagaId, SOURCE, TRACE_TYPE, null);
    this.documentId = documentId;
    // ...
}
```

With:

```java
// AFTER
private EbmsSentNotificationEvent(
        String sagaId,
        String correlationId,
        String documentId, String invoiceId, String invoiceNumber,
        String documentType, String ebmsMessageId, Instant sentAt) {

    super(sagaId, correlationId, SOURCE, TRACE_TYPE, null);
    this.documentId = documentId;
    this.invoiceId = invoiceId;
    this.invoiceNumber = invoiceNumber;
    this.documentType = documentType;
    this.ebmsMessageId = ebmsMessageId;
    this.sentAt = sentAt;
}
```

- [ ] **Step 3: Update the `@JsonCreator` constructor**

In the `@JsonCreator` constructor (lines 80–104), insert `@JsonProperty("correlationId") String correlationId` after `sagaId` and before `source`, then switch the `super()` call to the 9-arg form:

```java
// AFTER
@JsonCreator
public EbmsSentNotificationEvent(
    @JsonProperty("eventId") UUID eventId,
    @JsonProperty("occurredAt") Instant occurredAt,
    @JsonProperty("eventType") String eventType,
    @JsonProperty("version") int version,
    @JsonProperty("sagaId") String sagaId,
    @JsonProperty("correlationId") String correlationId,
    @JsonProperty("source") String source,
    @JsonProperty("traceType") String traceType,
    @JsonProperty("context") String context,
    @JsonProperty("documentId") String documentId,
    @JsonProperty("invoiceId") String invoiceId,
    @JsonProperty("invoiceNumber") String invoiceNumber,
    @JsonProperty("documentType") String documentType,
    @JsonProperty("ebmsMessageId") String ebmsMessageId,
    @JsonProperty("sentAt") Instant sentAt
) {
    super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
    this.documentId = documentId;
    this.invoiceId = invoiceId;
    this.invoiceNumber = invoiceNumber;
    this.documentType = documentType;
    this.ebmsMessageId = ebmsMessageId;
    this.sentAt = sentAt;
}
```

---

### Task 3: Fix `NotificationEventPublisher.java`

**Files:**
- Modify: `src/main/java/com/wpanther/ebmssending/infrastructure/messaging/NotificationEventPublisher.java`

- [ ] **Step 1: Add `correlationId` parameter and pass it to `create()`**

In `publishEbmsSentNotification()` (lines 30–57), add `String correlationId` as the second parameter (after `sagaId`) and pass it to `EbmsSentNotificationEvent.create()`:

```java
// AFTER
@Transactional(propagation = Propagation.MANDATORY)
public void publishEbmsSentNotification(
        String sagaId,
        String correlationId,
        String documentId, String invoiceId, String invoiceNumber,
        String documentType, String ebmsMessageId, Instant sentAt) {

    EbmsSentNotificationEvent notification = EbmsSentNotificationEvent.create(
        sagaId, correlationId, documentId, invoiceId, invoiceNumber,
        documentType, ebmsMessageId, sentAt
    );

    Map<String, String> headers = new HashMap<>();
    headers.put("eventType", "EbmsSent");
    headers.put("documentType", documentType);
    headers.put("sagaId", sagaId);
    headers.put("documentId", documentId);

    outboxService.saveWithRouting(
        notification,
        "EbmsSubmission",
        documentId,
        ebmsSentTopic,
        documentId,
        toJson(headers)
    );

    log.info("Published EbmsSent notification for documentId={}, invoiceNumber={}, documentType={}",
        documentId, invoiceNumber, documentType);
}
```

---

### Task 4: Fix `EbmsSendingEventPublisher.java`

**Files:**
- Modify: `src/main/java/com/wpanther/ebmssending/infrastructure/messaging/EbmsSendingEventPublisher.java`

- [ ] **Step 1: Pass `correlationId` to `publishEbmsSentNotification()`**

In `publishSuccess()` (lines 23–36), `correlationId` is already in scope. Update the `notificationEventPublisher.publishEbmsSentNotification(...)` call to add `correlationId` as the second argument (after `sagaId`):

```java
// BEFORE
notificationEventPublisher.publishEbmsSentNotification(
    sagaId, documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, sentAt
);

// AFTER
notificationEventPublisher.publishEbmsSentNotification(
    sagaId, correlationId, documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, sentAt
);
```

---

### Task 5: Update `EbmsSentNotificationEventTest.java`

**Files:**
- Modify: `src/test/java/com/wpanther/ebmssending/domain/event/EbmsSentNotificationEventTest.java`

- [ ] **Step 1: Update `shouldCreateEventWithFullConstructor()` direct `@JsonCreator` call**

`EbmsSentNotificationEventTest.shouldCreateEventWithFullConstructor()` (around line 64) directly calls the `@JsonCreator` constructor with positional arguments. After the fix the constructor has 15 params (new `correlationId` inserted between `sagaId` and `source`). Update this call:

```java
// BEFORE
EbmsSentNotificationEvent event = new EbmsSentNotificationEvent(
    eventId, occurredAt, eventType, version,
    sagaId, source, traceType, context,
    documentId, invoiceId, invoiceNumber, documentType,
    ebmsMessageId, sentAt
);

// AFTER
EbmsSentNotificationEvent event = new EbmsSentNotificationEvent(
    eventId, occurredAt, eventType, version,
    sagaId, "corr-" + sagaId,   // correlationId inserted between sagaId and source
    source, traceType, context,
    documentId, invoiceId, invoiceNumber, documentType,
    ebmsMessageId, sentAt
);
```

Also add an assertion to verify the value is correctly stored:
```java
assertEquals("corr-" + sagaId, event.getCorrelationId());
```

- [ ] **Step 2: Add `correlationId` to all `create()` call sites**

`EbmsSentNotificationEventTest` has **14** `EbmsSentNotificationEvent.create()` call sites. For each, insert a `correlationId` argument as the second argument (after `sagaId`). Derive a meaningful value from the existing test context. For example:

```java
// BEFORE
EbmsSentNotificationEvent event = EbmsSentNotificationEvent.create(
    "saga-001",
    "doc-123", "inv-001", "INV-001",
    "TAX_INVOICE", "EBMS-MSG-1", Instant.now());

// AFTER
EbmsSentNotificationEvent event = EbmsSentNotificationEvent.create(
    "saga-001",
    "corr-doc-123",   // correlationId derived from documentId
    "doc-123", "inv-001", "INV-001",
    "TAX_INVOICE", "EBMS-MSG-1", Instant.now());
```

Use a consistent pattern: where a `documentId` variable is in scope, use `"corr-" + documentId`. Where only literals are available, use a descriptive literal like `"corr-test"`.

- [ ] **Step 2: Update `shouldHavePrivateConstructor()` reflection test**

Find `shouldHavePrivateConstructor()`. It currently calls:
```java
EbmsSentNotificationEvent.class.getDeclaredConstructor(
    String.class, String.class, String.class, String.class,
    String.class, String.class, Instant.class   // 7 params
);
```

After the fix, the private constructor has 8 params (new `String correlationId` at index 1, after `sagaId`). Update to:
```java
EbmsSentNotificationEvent.class.getDeclaredConstructor(
    String.class, String.class, String.class, String.class,
    String.class, String.class, String.class, Instant.class   // 8 params
);
```

- [ ] **Step 3: Update `shouldHaveJsonCreatorConstructor()` reflection test**

Find `shouldHaveJsonCreatorConstructor()`. It currently calls `getDeclaredConstructor()` with 14 types (the last being `Instant.class` for `sentAt`). After the fix, insert `String.class` at index 5 (after `String.class` for `sagaId`, before `String.class` for `source`), giving 15 total:

```java
EbmsSentNotificationEvent.class.getDeclaredConstructor(
    UUID.class, Instant.class, String.class, int.class,
    String.class,   // sagaId
    String.class,   // correlationId  <-- INSERT HERE
    String.class,   // source
    String.class,   // traceType
    String.class,   // context
    String.class,   // documentId
    String.class,   // invoiceId
    String.class,   // invoiceNumber
    String.class,   // documentType
    String.class,   // ebmsMessageId
    Instant.class   // sentAt
);
```

---

### Task 6: Update `NotificationEventPublisherTest.java`

**Files:**
- Modify: `src/test/java/com/wpanther/ebmssending/infrastructure/messaging/NotificationEventPublisherTest.java`

- [ ] **Step 1: Add `correlationId` to all `publishEbmsSentNotification()` call sites**

`NotificationEventPublisherTest` has multiple `publishEbmsSentNotification()` call sites. For every call, insert a `correlationId` argument as the second parameter (after `sagaId`). Derive a meaningful value from the test context (e.g., use `"corr-" + documentId` or the constant pattern already used in that test). For example:

```java
// BEFORE
publisher.publishEbmsSentNotification(
    sagaId, documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, sentAt);

// AFTER
publisher.publishEbmsSentNotification(
    sagaId, correlationId, documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, sentAt);
```

Where `correlationId` is not already declared, add `String correlationId = "corr-" + documentId;` (or a suitable literal) in the test setup section.

---

### Task 7: Update `AsyncNotificationEventPublisherTest.java`

**Files:**
- Modify: `src/test/java/com/wpanther/ebmssending/adapters/outbound/messaging/AsyncNotificationEventPublisherTest.java`

Note: The **production** `AsyncNotificationEventPublisher` class does NOT call `create()` — it receives pre-built events via `@TransactionalEventListener`. No production code changes are needed. Only the test class has `create()` calls that must be updated.

- [ ] **Step 1: Add `correlationId` to all 6 `create()` call sites**

`AsyncNotificationEventPublisherTest` has exactly **6** `EbmsSentNotificationEvent.create()` call sites. For each, insert a `correlationId` argument as the second parameter (after `sagaId`):

```java
// BEFORE
EbmsSentNotificationEvent event = EbmsSentNotificationEvent.create(
    sagaId,
    documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, Instant.now());

// AFTER
EbmsSentNotificationEvent event = EbmsSentNotificationEvent.create(
    sagaId,
    "corr-" + documentId,
    documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, Instant.now());
```

If a `documentId` variable is not in scope, use a descriptive literal such as `"corr-test"`.

---

### Task 8: Update `EbmsSendingEventPublisherTest.java`

**Files:**
- Modify: `src/test/java/com/wpanther/ebmssending/infrastructure/messaging/EbmsSendingEventPublisherTest.java`

- [ ] **Step 1: Add `correlationId` to all positive `verify()` call sites**

Find every `verify(notificationEventPublisher).publishEbmsSentNotification(...)` call (at lines 55, 143, 154, 165, 187, 289). For each, insert the `correlationId` value as the second argument (after `sagaId`). The `correlationId` value is already in scope in each test method (it is set as `"corr-456"` or `"corr-1"`, `"corr-2"`, `"corr-3"` in the parameterized tests). For example:

```java
// BEFORE
verify(notificationEventPublisher).publishEbmsSentNotification(
    sagaId, documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, sentAt
);

// AFTER
verify(notificationEventPublisher).publishEbmsSentNotification(
    sagaId, correlationId, documentId, invoiceId, invoiceNumber,
    documentType, ebmsMessageId, sentAt
);
```

- [ ] **Step 2: Add 8th `any()` to all three `never()` verify calls**

There are exactly **3** `verify(notificationEventPublisher, never()).publishEbmsSentNotification(...)` calls (at lines 74, 91, 260). Each currently has exactly 7 `any()` matchers. After `correlationId` is added as the 8th parameter, add one more `any()` matcher to each:

```java
// BEFORE
verify(notificationEventPublisher, never()).publishEbmsSentNotification(
    any(), any(), any(), any(), any(), any(), any()
);

// AFTER
verify(notificationEventPublisher, never()).publishEbmsSentNotification(
    any(), any(), any(), any(), any(), any(), any(), any()
);
```

---

### Task 9: Run tests and commit

- [ ] **Step 1: Run all tests**

```bash
cd /path/to/ebms-sending-service
mvn test
```

Expected: **BUILD SUCCESS** — all tests pass, including the new regression test.

- [ ] **Step 2: Commit**

```bash
git add \
  src/main/java/com/wpanther/ebmssending/domain/event/EbmsSentNotificationEvent.java \
  src/main/java/com/wpanther/ebmssending/infrastructure/messaging/NotificationEventPublisher.java \
  src/main/java/com/wpanther/ebmssending/infrastructure/messaging/EbmsSendingEventPublisher.java \
  src/test/java/com/wpanther/ebmssending/domain/event/EbmsSentNotificationEventTest.java \
  src/test/java/com/wpanther/ebmssending/infrastructure/messaging/NotificationEventPublisherTest.java \
  src/test/java/com/wpanther/ebmssending/adapters/outbound/messaging/AsyncNotificationEventPublisherTest.java \
  src/test/java/com/wpanther/ebmssending/infrastructure/messaging/EbmsSendingEventPublisherTest.java
git commit -m "fix: propagate correlationId through EbmsSentNotificationEvent and publisher chain"
```
