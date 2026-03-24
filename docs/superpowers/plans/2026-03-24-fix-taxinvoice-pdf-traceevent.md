# Fix TaxInvoicePdfGeneratedEvent correlationId Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `TaxInvoicePdfGeneratedEvent` so `TraceEvent.getCorrelationId()` returns the correct value and `sagaId` is populated from the actual saga command.

**Architecture:** Add `sagaId` as a new first parameter to the creation constructor, fix the `super()` call to pass both `sagaId` and `correlationId`, remove the `@JsonIgnore getCorrelationId()` workaround, and update the `@JsonCreator` to the 9-arg `TraceEvent` form.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Jackson, JUnit 5, Mockito, Maven (`mvn test`)

---

## File Map

| Action | File |
|--------|------|
| Modify | `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java` |
| Modify | `src/main/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentService.java` |
| Modify | `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/EventPublisherTest.java` |
| Modify | `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfigTest.java` |

---

### Task 1: Add a regression test that proves the current bug

The current code stores `correlationId` in the `sagaId` slot. We need a test that asserts `getCorrelationId()` returns the correct value AND `getSagaId()` returns the saga's ID — both independently — so that the test fails today and passes after the fix.

**Files:**
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/EventPublisherTest.java`

- [ ] **Step 1: Add regression test to `EventPublisherTest`**

In `EventPublisherTest.java`, add this test method after the existing tests:

```java
@Test
@DisplayName("TaxInvoicePdfGeneratedEvent stores sagaId and correlationId independently")
void testSagaIdAndCorrelationIdStoredIndependently() {
    // Given
    String sagaId = "saga-001";
    String correlationId = "corr-456";

    // When
    TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
            sagaId,
            "doc-123", "tax-inv-001", "TXINV-2024-001",
            "http://localhost:9000/taxinvoices/test.pdf", 12345L, true,
            correlationId);

    // Then
    assertThat(event.getSagaId()).isEqualTo(sagaId);
    assertThat(event.getCorrelationId()).isEqualTo(correlationId);
}
```

This test uses the *new* 8-arg constructor signature (with `sagaId` as first param) — it won't compile yet.

- [ ] **Step 2: Verify the test cannot compile**

```bash
cd /path/to/taxinvoice-pdf-generation-service
mvn test-compile 2>&1 | grep -A3 "ERROR\|cannot find symbol"
```

Expected: compilation error on the new test (constructor with 8 args doesn't exist yet).

---

### Task 2: Fix `TaxInvoicePdfGeneratedEvent.java`

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java`

- [ ] **Step 1: Update the creation constructor**

Replace the current creation constructor (lines 44–60):

```java
// BEFORE
public TaxInvoicePdfGeneratedEvent(
        String documentId,
        String taxInvoiceId,
        String taxInvoiceNumber,
        String documentUrl,
        long fileSize,
        boolean xmlEmbedded,
        String correlationId
) {
    super(correlationId, SOURCE, TRACE_TYPE, null);
    this.documentId = documentId;
    // ...
}
```

With:

```java
// AFTER
public TaxInvoicePdfGeneratedEvent(
        String sagaId,
        String documentId,
        String taxInvoiceId,
        String taxInvoiceNumber,
        String documentUrl,
        long fileSize,
        boolean xmlEmbedded,
        String correlationId
) {
    super(sagaId, correlationId, SOURCE, TRACE_TYPE, null);
    this.documentId = documentId;
    this.taxInvoiceId = taxInvoiceId;
    this.taxInvoiceNumber = taxInvoiceNumber;
    this.documentUrl = documentUrl;
    this.fileSize = fileSize;
    this.xmlEmbedded = xmlEmbedded;
}
```

- [ ] **Step 2: Remove the `@JsonIgnore getCorrelationId()` override**

Delete these 4 lines entirely (currently around lines 62–68):

```java
/**
 * Returns the correlation ID (stored as sagaId in TraceEvent).
 */
@JsonIgnore
public String getCorrelationId() {
    return getSagaId();
}
```

- [ ] **Step 3: Update the `@JsonCreator` constructor**

Replace the `@JsonCreator` constructor (currently lines 75–99). The key changes are:
- Insert `@JsonProperty("correlationId") String correlationId` after `sagaId` and before `source`
- Switch the `super()` call from the old 8-arg form to the new 9-arg form

```java
@JsonCreator
public TaxInvoicePdfGeneratedEvent(
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
        @JsonProperty("taxInvoiceId") String taxInvoiceId,
        @JsonProperty("taxInvoiceNumber") String taxInvoiceNumber,
        @JsonProperty("documentUrl") String documentUrl,
        @JsonProperty("fileSize") long fileSize,
        @JsonProperty("xmlEmbedded") boolean xmlEmbedded
) {
    super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
    this.documentId = documentId;
    this.taxInvoiceId = taxInvoiceId;
    this.taxInvoiceNumber = taxInvoiceNumber;
    this.documentUrl = documentUrl;
    this.fileSize = fileSize;
    this.xmlEmbedded = xmlEmbedded;
}
```

Also remove the `import com.fasterxml.jackson.annotation.JsonIgnore;` line since `@JsonIgnore` is no longer used.

---

### Task 3: Fix `TaxInvoicePdfDocumentService.java`

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentService.java`

- [ ] **Step 1: Update `buildGeneratedEvent()` to pass `sagaId`**

Find `buildGeneratedEvent()` (around line 163). Replace:

```java
// BEFORE
private TaxInvoicePdfGeneratedEvent buildGeneratedEvent(TaxInvoicePdfDocument doc,
                                                         KafkaTaxInvoiceProcessCommand command) {
    return new TaxInvoicePdfGeneratedEvent(
            command.getDocumentId(),
            doc.getTaxInvoiceId(),
            doc.getTaxInvoiceNumber(),
            doc.getDocumentUrl(),
            doc.getFileSize(),
            doc.isXmlEmbedded(),
            command.getCorrelationId());
}
```

With:

```java
// AFTER
private TaxInvoicePdfGeneratedEvent buildGeneratedEvent(TaxInvoicePdfDocument doc,
                                                         KafkaTaxInvoiceProcessCommand command) {
    return new TaxInvoicePdfGeneratedEvent(
            command.getSagaId(),
            command.getDocumentId(),
            doc.getTaxInvoiceId(),
            doc.getTaxInvoiceNumber(),
            doc.getDocumentUrl(),
            doc.getFileSize(),
            doc.isXmlEmbedded(),
            command.getCorrelationId());
}
```

---

### Task 4: Update test call sites

**Files:**
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/EventPublisherTest.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfigTest.java`

- [ ] **Step 1: Fix `EventPublisherTest` — `testPublishPdfGenerated()` (line 49)**

Add `"saga-001"` as the new first argument:

```java
// BEFORE
TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
        documentId, taxInvoiceId, taxInvoiceNumber, documentUrl, fileSize, xmlEmbedded, correlationId);

// AFTER
TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
        "saga-001",
        documentId, taxInvoiceId, taxInvoiceNumber, documentUrl, fileSize, xmlEmbedded, correlationId);
```

- [ ] **Step 2: Fix `EventPublisherTest` — `testPublishPdfGenerated_Headers()` (line 70)**

Add `"saga-001"` as the new first argument:

```java
// BEFORE
TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
        "doc-123", "tax-inv-001", "TXINV-001",
        "http://localhost:9000/taxinvoices/test.pdf", 12345L, true, "corr-456");

// AFTER
TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
        "saga-001",
        "doc-123", "tax-inv-001", "TXINV-001",
        "http://localhost:9000/taxinvoices/test.pdf", 12345L, true, "corr-456");
```

The assertion `assertThat(headersJson).contains("\"correlationId\":\"corr-456\"")` must continue to pass — it will, because `getCorrelationId()` now comes from `TraceEvent` which was set correctly in the constructor.

- [ ] **Step 3: Fix `CamelRouteConfigTest` — `testTaxInvoicePdfGeneratedEventSerialization()` (line 99)**

Add `"saga-001"` as the new first argument:

```java
// BEFORE
TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
        "doc-123", "tax-inv-001", "TXINV-2024-001",
        "http://example.com/doc.pdf", 12345L, true, "corr-456"
);

// AFTER
TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
        "saga-001",
        "doc-123", "tax-inv-001", "TXINV-2024-001",
        "http://example.com/doc.pdf", 12345L, true, "corr-456"
);
```

---

### Task 5: Run tests and commit

- [ ] **Step 1: Run all tests**

```bash
cd /path/to/taxinvoice-pdf-generation-service
mvn test
```

Expected: **BUILD SUCCESS** — all tests pass, including the new regression test in `EventPublisherTest`.

- [ ] **Step 2: Commit**

```bash
git add \
  src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java \
  src/main/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentService.java \
  src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/EventPublisherTest.java \
  src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfigTest.java
git commit -m "fix: propagate sagaId and correlationId correctly in TaxInvoicePdfGeneratedEvent"
```
