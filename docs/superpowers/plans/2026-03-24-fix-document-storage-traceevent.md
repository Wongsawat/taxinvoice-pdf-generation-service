# Fix DocumentStoredEvent correlationId Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `DocumentStoredEvent` so `TraceEvent.getCorrelationId()` returns the correct value by removing the shadowing subclass field and wiring the existing constructor parameter to `super()`.

**Architecture:** The subclass currently holds a duplicate `private final String correlationId` field that shadows `TraceEvent.getCorrelationId()`. Remove the field, remove the `this.correlationId = correlationId` assignments, and change the `super()` calls to pass `correlationId` to `TraceEvent`. No caller or test call-site changes needed — the external constructor signature is unchanged.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Jackson, JUnit 5, Maven (`mvn test`)

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/com/wpanther/storage/application/dto/event/DocumentStoredEvent.java` |

---

### Task 1: Add a regression test that proves the current bug

The subclass field `correlationId` shadows `TraceEvent.getCorrelationId()`. `event.getCorrelationId()` returns the subclass field value (which happens to match), but `TraceEvent.getCorrelationId()` called polymorphically returns `null`. Add a test that proves the polymorphic accessor works:

**Files:**
- The test files for this service are `MessagePublisherAdapterTest.java` and `DocumentStoredEventTest.java`. Locate either one and add the regression test there.

- [ ] **Step 1: Add regression test**

Find `DocumentStoredEventTest.java` at:
```
src/test/java/com/wpanther/storage/
```

Add:

```java
@Test
void getCorrelationId_polymorphicAccessorShouldReturnProvidedValue() {
    // Arrange
    DocumentStoredEvent event = new DocumentStoredEvent(
            "doc-123", "inv-001", "INV-001",
            "invoice.pdf", "http://localhost/doc.pdf", 12345L,
            "abc123checksum", "TAX_INVOICE", "corr-abc");

    // Act — call via polymorphic TraceEvent reference
    TraceEvent traceEvent = event;

    // Assert
    assertThat(traceEvent.getCorrelationId()).isEqualTo("corr-abc");
}
```

You may need `import com.wpanther.saga.domain.model.TraceEvent;`.

- [ ] **Step 2: Run it to confirm the bug**

```bash
cd /path/to/document-storage-service
mvn test -Dtest="DocumentStoredEventTest" 2>&1 | grep -E "FAIL|getCorrelationId|AssertionError"
```

Expected: the new test fails because `traceEvent.getCorrelationId()` returns `null`.

---

### Task 2: Fix `DocumentStoredEvent.java`

**Files:**
- Modify: `src/main/java/com/wpanther/storage/application/dto/event/DocumentStoredEvent.java`

- [ ] **Step 1: Remove the duplicate `correlationId` field**

Delete lines 47–48:
```java
@JsonProperty("correlationId")
private final String correlationId;
```

- [ ] **Step 2: Fix the creation constructor `super()` call and remove field assignment**

Find the public creation constructor (around line 50). Change the `super()` call from the old 3-arg form to the new 5-arg form that passes `correlationId`, and remove `this.correlationId = correlationId;`:

```java
// BEFORE
public DocumentStoredEvent(String documentId, String invoiceId, String invoiceNumber,
                            String fileName, String storageUrl, long fileSize,
                            String checksum, String documentType, String correlationId) {
    super(invoiceId, SOURCE, TRACE_TYPE);
    this.documentId = documentId;
    this.invoiceId = invoiceId;
    this.invoiceNumber = invoiceNumber;
    this.fileName = fileName;
    this.storageUrl = storageUrl;
    this.fileSize = fileSize;
    this.checksum = checksum;
    this.documentType = documentType;
    this.correlationId = correlationId;    // <-- remove this line
}
```

```java
// AFTER
public DocumentStoredEvent(String documentId, String invoiceId, String invoiceNumber,
                            String fileName, String storageUrl, long fileSize,
                            String checksum, String documentType, String correlationId) {
    super(invoiceId, correlationId, SOURCE, TRACE_TYPE, null);
    this.documentId = documentId;
    this.invoiceId = invoiceId;
    this.invoiceNumber = invoiceNumber;
    this.fileName = fileName;
    this.storageUrl = storageUrl;
    this.fileSize = fileSize;
    this.checksum = checksum;
    this.documentType = documentType;
}
```

Note: `invoiceId` is passed as `sagaId` (preserving the existing behaviour) and `null` is passed for `context`.

- [ ] **Step 3: Fix the `@JsonCreator` constructor**

Find the `@JsonCreator` constructor (around line 70). The `correlationId` parameter **already exists** in the parameter list — it was wired to `this.correlationId` which is now removed. Do NOT add a duplicate parameter. Make two changes:

1. Remove the `this.correlationId = correlationId;` assignment from the body.
2. Change the `super()` call to the 9-arg form:

```java
// BEFORE (super call, around line 89)
super(eventId, occurredAt, eventType, version, sagaId, source, traceType, context);
// ...
this.correlationId = correlationId;   // <-- remove this

// AFTER
super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
// (no this.correlationId assignment)
```

---

### Task 3: Run tests and commit

- [ ] **Step 1: Run all tests**

```bash
cd /path/to/document-storage-service
mvn test
```

Expected: **BUILD SUCCESS** — all tests pass. The regression test from Task 1 now passes. Existing assertions on `getCorrelationId()` in `MessagePublisherAdapterTest` and `DocumentStoredEventTest` continue to pass because the value now comes from `TraceEvent.getCorrelationId()` instead of the removed subclass field, but the value is identical.

- [ ] **Step 2: Commit**

```bash
git add \
  src/main/java/com/wpanther/storage/application/dto/event/DocumentStoredEvent.java \
  src/test/java/  # include whatever test file you added the regression test to
git commit -m "fix: remove shadowing correlationId field from DocumentStoredEvent"
```
