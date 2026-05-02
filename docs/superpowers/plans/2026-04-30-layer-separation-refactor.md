# taxinvoice-pdf-generation-service Layer Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor taxinvoice-pdf-generation-service to place saga infrastructure types in `infrastructure/` and use case interfaces with plain parameters in `application/port/in/`, matching the architecture of taxinvoice-processing-service and invoice-pdf-generation-service.

**Architecture:** Kafka DTOs (SagaCommand/SagaReply + Jackson) live in `infrastructure/adapter/in/kafka/dto/`. Use case interfaces accept plain field parameters. `SagaCommandHandler` routes DTOs to use cases by extracting fields — no command objects flow into domain or application layers. `TaxInvoicePdfGeneratedEvent` moves to `application/dto/event/` as it extends TraceEvent (notification DTO, not domain).

**Tech Stack:** Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4, saga-commons library, Jackson, MinIO (S3-compatible storage)

---

## File Changes Overview

```
CREATING:
  infrastructure/adapter/in/kafka/dto/ProcessTaxInvoicePdfCommand.java        (rename from Kafka*Command)
  infrastructure/adapter/in/kafka/dto/CompensateTaxInvoicePdfCommand.java       (rename from Kafka*Command)
  application/port/in/ProcessTaxInvoicePdfUseCase.java                          (new interface, plain params)
  application/port/in/CompensateTaxInvoicePdfUseCase.java                       (new interface, plain params)
  application/dto/event/TaxInvoicePdfGeneratedEvent.java                        (moved from infrastructure/)

MOVING:
  SagaCommandHandler.java          application/service/ → infrastructure/adapter/in/kafka/

DELETING:
  infrastructure/adapter/in/kafka/KafkaCommandMapper.java
  infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java
  infrastructure/adapter/in/kafka/KafkaTaxInvoiceCompensateCommand.java
  infrastructure/adapter/out/messaging/TaxInvoicePdfReplyEvent.java           (inlined into SagaReplyPublisher)
  infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java       (moved to application/dto/event/)
  application/service/SagaCommandHandler.java                                (original location)
  application/usecase/ProcessTaxInvoicePdfUseCase.java
  application/usecase/CompensateTaxInvoicePdfUseCase.java
  test/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java          (orphaned after mapper deletion)

MODIFYING:
  TaxInvoicePdfDocumentService.java    (method signatures change from command objects to plain fields)
  SagaRouteConfig.java                 (remove KafkaCommandMapper, use new DTO names)
  SagaReplyPublisher.java              (inline TaxInvoicePdfReplyEvent factory)
  EventPublisher.java                  (import new package path for TaxInvoicePdfGeneratedEvent)
```

---

## Before You Start

- Build the service to confirm it compiles: `mvn clean compile -q`
- Run tests to confirm baseline: `mvn clean test -q`
- Work inside the service directory: `/home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service`

---

## Task 1: Create `dto/` directory and new `ProcessTaxInvoicePdfCommand`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/dto/ProcessTaxInvoicePdfCommand.java`
- Source: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java`

- [ ] **Step 1: Write the new file**

Create directory and new file:
```bash
mkdir -p src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/dto
```

Create `ProcessTaxInvoicePdfCommand.java` — this is a rename of `KafkaTaxInvoiceProcessCommand` with package changed:
```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
public class ProcessTaxInvoicePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("documentNumber")
    private final String documentNumber;

    @JsonProperty("signedXmlUrl")
    private final String signedXmlUrl;

    @JsonCreator
    public ProcessTaxInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("signedXmlUrl") String signedXmlUrl) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.signedXmlUrl = signedXmlUrl;
    }

    public ProcessTaxInvoicePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                        String documentId, String documentNumber,
                                        String signedXmlUrl) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
        this.documentNumber = Objects.requireNonNull(documentNumber, "documentNumber is required");
        this.signedXmlUrl = Objects.requireNonNull(signedXmlUrl, "signedXmlUrl is required");
    }

    @Override public String getSagaId()        { return super.getSagaId(); }
    @Override public SagaStep getSagaStep()    { return super.getSagaStep(); }
    @Override public String getCorrelationId() { return super.getCorrelationId(); }
    public String getDocumentId()     { return documentId; }
    public String getDocumentNumber() { return documentNumber; }
    public String getSignedXmlUrl()   { return signedXmlUrl; }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors related to the new file

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/dto/ProcessTaxInvoicePdfCommand.java
git commit -m "refactor: rename KafkaTaxInvoiceProcessCommand to ProcessTaxInvoicePdfCommand in dto/ package"
```

---

## Task 2: Create `CompensateTaxInvoicePdfCommand` in `dto/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/dto/CompensateTaxInvoicePdfCommand.java`
- Source: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceCompensateCommand.java`

- [ ] **Step 1: Write the new file**

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Getter
public class CompensateTaxInvoicePdfCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")
    private final String documentId;

    @JsonCreator
    public CompensateTaxInvoicePdfCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
    }

    public CompensateTaxInvoicePdfCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                           String documentId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = Objects.requireNonNull(documentId, "documentId is required");
    }

    @Override public String getSagaId()        { return super.getSagaId(); }
    @Override public SagaStep getSagaStep()   { return super.getSagaStep(); }
    @Override public String getCorrelationId() { return super.getCorrelationId(); }
    public String getDocumentId() { return documentId; }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/dto/CompensateTaxInvoicePdfCommand.java
git commit -m "refactor: rename KafkaTaxInvoiceCompensateCommand to CompensateTaxInvoicePdfCommand in dto/ package"
```

---

## Task 3: Create `ProcessTaxInvoicePdfUseCase` in `application/port/in/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/port/in/ProcessTaxInvoicePdfUseCase.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/ProcessTaxInvoicePdfUseCase.java` (later)

- [ ] **Step 1: Write the new interface**

```java
package com.wpanther.taxinvoice.pdf.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port for tax invoice PDF generation.
 * Called by SagaCommandHandler with plain fields — no command objects.
 */
public interface ProcessTaxInvoicePdfUseCase {

    void handle(String documentId, String documentNumber, String signedXmlUrl,
                String sagaId, SagaStep sagaStep, String correlationId);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/application/port/in/ProcessTaxInvoicePdfUseCase.java
git commit -m "refactor: add ProcessTaxInvoicePdfUseCase in application/port/in/ with plain parameter signatures"
```

---

## Task 4: Create `CompensateTaxInvoicePdfUseCase` in `application/port/in/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/port/in/CompensateTaxInvoicePdfUseCase.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/CompensateTaxInvoicePdfUseCase.java` (later)

- [ ] **Step 1: Write the new interface**

```java
package com.wpanther.taxinvoice.pdf.application.port.in;

import com.wpanther.saga.domain.enums.SagaStep;

/**
 * Inbound port for tax invoice PDF compensation.
 * Called by SagaCommandHandler with plain fields — no command objects.
 */
public interface CompensateTaxInvoicePdfUseCase {

    void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId);
}
```

- [ ] **Step 2: Verify it compiles**

Run: `mvn compile -q 2>&1 | head -20`
Expected: No errors

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/application/port/in/CompensateTaxInvoicePdfUseCase.java
git commit -m "refactor: add CompensateTaxInvoicePdfUseCase in application/port/in/ with plain parameter signatures"
```

---

## Task 5: Rewrite `SagaCommandHandler` in `infrastructure/adapter/in/kafka/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/SagaCommandHandler.java` (new location)
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandler.java` (later)
- Modify: `TaxInvoicePdfDocumentService.java` (Task 7)

This is the most complex rewrite. The handler now:
1. Accepts `ProcessTaxInvoicePdfCommand` / `CompensateTaxInvoicePdfCommand` from Kafka
2. Extracts all fields and calls use case interfaces with plain parameters
3. Calls `TaxInvoicePdfDocumentService` with plain parameters (not command objects)

- [ ] **Step 1: Write the new SagaCommandHandler**

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.taxinvoice.pdf.application.port.in.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.port.in.ProcessTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.service.TaxInvoicePdfDocumentService;
import com.wpanther.taxinvoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.ProcessTaxInvoicePdfCommand;
import com.wpanther.saga.domain.enums.SagaStep;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Saga command handler — driving adapter that receives Kafka messages and calls use cases.
 * No command objects flow into domain or application layers — only plain field parameters.
 */
@Service
@Slf4j
public class SagaCommandHandler implements ProcessTaxInvoicePdfUseCase, CompensateTaxInvoicePdfUseCase {

    private static final String MDC_SAGA_ID         = "sagaId";
    private static final String MDC_CORRELATION_ID  = "correlationId";
    private static final String MDC_DOCUMENT_NUMBER = "documentNumber";
    private static final String MDC_DOCUMENT_ID     = "documentId";

    private final TaxInvoicePdfDocumentService pdfDocumentService;
    private final TaxInvoicePdfGenerationService pdfGenerationService;
    private final PdfStoragePort pdfStoragePort;
    private final SagaReplyPort sagaReplyPort;
    private final SignedXmlFetchPort signedXmlFetchPort;
    private final int maxRetries;

    public SagaCommandHandler(TaxInvoicePdfDocumentService pdfDocumentService,
                              TaxInvoicePdfGenerationService pdfGenerationService,
                              PdfStoragePort pdfStoragePort,
                              SagaReplyPort sagaReplyPort,
                              SignedXmlFetchPort signedXmlFetchPort,
                              @Value("${app.pdf.generation.max-retries:3}") int maxRetries) {
        this.pdfDocumentService = pdfDocumentService;
        this.pdfGenerationService = pdfGenerationService;
        this.pdfStoragePort = pdfStoragePort;
        this.sagaReplyPort = sagaReplyPort;
        this.signedXmlFetchPort = signedXmlFetchPort;
        this.maxRetries = maxRetries;
    }

    @Override
    public void handle(String documentId, String documentNumber, String signedXmlUrl,
                       String sagaId, SagaStep sagaStep, String correlationId) {
        MDC.put(MDC_SAGA_ID,         sagaId);
        MDC.put(MDC_CORRELATION_ID,  correlationId);
        MDC.put(MDC_DOCUMENT_NUMBER, documentNumber);
        MDC.put(MDC_DOCUMENT_ID,     documentId);
        try {
            log.info("Handling ProcessTaxInvoicePdfCommand for saga {} document {}",
                    sagaId, documentNumber);
            try {
                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "signedXmlUrl is null or blank in saga command");
                    return;
                }
                if (documentNumber == null || documentNumber.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentNumber is null or blank in saga command");
                    return;
                }
                if (documentId == null || documentId.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "documentId is null or blank in saga command");
                    return;
                }

                Optional<TaxInvoicePdfDocument> existing = pdfDocumentService.findByTaxInvoiceId(documentId);

                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), documentId, documentNumber, sagaId, sagaStep, correlationId);
                    return;
                }

                int previousRetryCount = existing.map(TaxInvoicePdfDocument::getRetryCount).orElse(-1);

                if (existing.isPresent()) {
                    TaxInvoicePdfDocument prior = existing.get();
                    if (!prior.isFailed()) {
                        log.warn("Found document in non-terminal state (status={}) for document {} saga {} — TX2 may have rolled back; will delete and retry",
                                prior.getStatus(), documentId, sagaId);
                    }
                    if (prior.isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(sagaId, sagaStep, correlationId, documentId, documentNumber);
                        return;
                    }
                }

                TaxInvoicePdfDocument document;
                if (existing.isPresent()) {
                    document = pdfDocumentService.replaceAndBeginGeneration(
                            existing.get().getId(), previousRetryCount, documentId, documentNumber);
                } else {
                    document = pdfDocumentService.beginGeneration(documentId, documentNumber);
                }

                String s3Key = null;
                try {
                    String xml = signedXmlFetchPort.fetch(signedXmlUrl);
                    byte[] pdfBytes = pdfGenerationService.generatePdf(documentNumber, xml);
                    s3Key = pdfStoragePort.store(documentNumber, pdfBytes);
                    String fileUrl = pdfStoragePort.resolveUrl(s3Key);

                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length,
                            previousRetryCount, documentId, documentNumber, sagaId, sagaStep, correlationId);

                    log.debug("Successfully processed PDF generation for saga {} document {}", sagaId, documentNumber);

                } catch (CallNotPermittedException e) {
                    log.warn("MinIO circuit breaker OPEN for saga {} document {} — no upload attempted, will retry when CB re-closes: {}",
                            sagaId, documentNumber, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "MinIO circuit breaker open: " + e.getMessage(),
                            previousRetryCount, sagaId, sagaStep, correlationId);

                } catch (Exception e) {
                    if (s3Key != null) {
                        try {
                            pdfStoragePort.delete(s3Key);
                            log.warn("Deleted orphaned MinIO object {} after processing failure for saga {}",
                                    s3Key, sagaId);
                        } catch (Exception deleteEx) {
                            log.error("[ORPHAN_PDF] s3Key={} saga={} documentNumber={} error={} — manual recovery required: delete object from MinIO bucket",
                                    s3Key, sagaId, documentNumber, describeThrowable(deleteEx));
                        }
                    }
                    log.error("PDF generation/upload failed for saga {} document {}: {}",
                            sagaId, documentNumber, e.getMessage(), e);
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeThrowable(e), previousRetryCount, sagaId, sagaStep, correlationId);
                }

            } catch (OptimisticLockingFailureException e) {
                log.warn("Concurrent modification conflict for saga {} document {} — retryable: {}",
                        sagaId, documentNumber, e.getMessage());
                pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, "Concurrent modification conflict: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error for saga {} document {}: {}", sagaId, documentNumber, e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(sagaId, sagaStep, correlationId, describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId) {
        MDC.put(MDC_SAGA_ID,         sagaId);
        MDC.put(MDC_CORRELATION_ID,  correlationId);
        MDC.put(MDC_DOCUMENT_ID,     documentId);
        try {
            log.info("Handling compensation for saga {} document {}", sagaId, documentId);
            try {
                Optional<TaxInvoicePdfDocument> existing = pdfDocumentService.findByTaxInvoiceId(documentId);

                if (existing.isPresent()) {
                    TaxInvoicePdfDocument document = existing.get();
                    pdfDocumentService.deleteById(document.getId());
                    if (document.getDocumentPath() != null) {
                        try {
                            pdfStoragePort.delete(document.getDocumentPath());
                        } catch (Exception e) {
                            log.warn("Failed to delete PDF from MinIO for saga {} key {}: {}",
                                    sagaId, document.getDocumentPath(), e.getMessage());
                        }
                    }
                    log.info("Compensated TaxInvoicePdfDocument {} for saga {}", document.getId(), sagaId);
                } else {
                    log.info("No document found for documentId {} — already compensated or never processed", documentId);
                }

                pdfDocumentService.publishCompensated(sagaId, sagaStep, correlationId);

            } catch (Exception e) {
                log.error("Failed to compensate for saga {} document {}: {}", sagaId, documentId, e.getMessage(), e);
                pdfDocumentService.publishCompensationFailure(sagaId, sagaStep, correlationId, "Compensation failed: " + describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailure(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            String error = "Message routed to DLQ after retry exhaustion: " + describeThrowable(cause);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
            log.error("Published FAILURE reply after DLQ routing for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ failure for saga {} — orchestrator must timeout", sagaId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailureForUnparsedMessage(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            String error = "Message routed to DLQ after deserialization failure: " + describeThrowable(cause);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
            log.error("Published FAILURE reply after DLQ routing (deserialization failure) for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ deserialization failure for saga {} — orchestrator must timeout", sagaId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensationOrchestrationFailure(String sagaId, SagaStep sagaStep, String correlationId, Throwable cause) {
        try {
            String error = "Compensation message routed to DLQ after retry exhaustion: " + describeThrowable(cause);
            sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
            log.error("Published FAILURE reply after compensation DLQ routing for saga {}", sagaId);
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of compensation DLQ failure for saga {} — orchestrator must timeout", sagaId, e);
        }
    }

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message != null ? ": " + message : "");
    }
}
```

- [ ] **Step 2: Compile and fix any errors**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Compile errors in `TaxInvoicePdfDocumentService` (method signatures don't match yet) — this is expected

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/SagaCommandHandler.java
git commit -m "refactor: move SagaCommandHandler to infrastructure/adapter/in/kafka/ with plain parameter calls"
```

---

## Task 6: Move `TaxInvoicePdfGeneratedEvent` to `application/dto/event/`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/dto/event/TaxInvoicePdfGeneratedEvent.java`
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/EventPublisher.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java` (original, after verifying)

- [ ] **Step 1: Create the new file**

```java
package com.wpanther.taxinvoice.pdf.application.dto.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.model.TraceEvent;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when tax invoice PDF generation is completed.
 * Consumed by notification-service.
 */
@Getter
public class TaxInvoicePdfGeneratedEvent extends TraceEvent {

    private static final String EVENT_TYPE = "pdf.generated.tax-invoice";
    private static final String SOURCE = "taxinvoice-pdf-generation-service";
    private static final String TRACE_TYPE = "PDF_GENERATED";

    @JsonProperty("documentId")
    private final String documentId;

    @JsonProperty("documentNumber")
    private final String documentNumber;

    @JsonProperty("documentUrl")
    private final String documentUrl;

    @JsonProperty("fileSize")
    private final long fileSize;

    @JsonProperty("xmlEmbedded")
    private final boolean xmlEmbedded;

    /**
     * Convenience constructor for creating a new tax invoice PDF generated event.
     */
    public TaxInvoicePdfGeneratedEvent(
            String sagaId,
            String documentId,
            String documentNumber,
            String documentUrl,
            long fileSize,
            boolean xmlEmbedded,
            String correlationId
    ) {
        super(sagaId, correlationId, SOURCE, TRACE_TYPE, null);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

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
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("documentUrl") String documentUrl,
            @JsonProperty("fileSize") long fileSize,
            @JsonProperty("xmlEmbedded") boolean xmlEmbedded
    ) {
        super(eventId, occurredAt, eventType, version, sagaId, correlationId, source, traceType, context);
        this.documentId = documentId;
        this.documentNumber = documentNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
    }
}
```

- [ ] **Step 2: Update EventPublisher import**

Change the import in `EventPublisher.java` from:
```java
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfGeneratedEvent;
```
to:
```java
import com.wpanther.taxinvoice.pdf.application.dto.event.TaxInvoicePdfGeneratedEvent;
```

- [ ] **Step 3: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Errors in `TaxInvoicePdfDocumentService` (will fix in Task 7)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/application/dto/event/TaxInvoicePdfGeneratedEvent.java
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/EventPublisher.java
git commit -m "refactor: move TaxInvoicePdfGeneratedEvent to application/dto/event/"
```

---

## Task 7: Update `TaxInvoicePdfDocumentService` method signatures

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentService.java`

This is the largest change — update all method signatures to accept plain fields instead of command objects.

- [ ] **Step 1: Rewrite TaxInvoicePdfDocumentService.java with updated method signatures**

Full file rewrite. The key changes:
- Remove imports for `KafkaTaxInvoiceProcessCommand`, `KafkaTaxInvoiceCompensateCommand` from infrastructure
- Add import for `com.wpanther.saga.domain.enums.SagaStep`
- All methods that took command objects now take individual field parameters

```java
package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.application.dto.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.metrics.PdfGenerationMetrics;
import com.wpanther.saga.domain.enums.SagaStep;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Application service for TaxInvoicePdfDocument lifecycle.
 *
 * <p>MDC Context: This service relies on MDC context being set by the caller
 * (SagaCommandHandler) for correlation IDs and saga IDs. MDC is thread-local
 * and is preserved across @Transactional boundaries since transactions do not
 * switch threads.</p>
 *
 * <p>Each method is a short, focused @Transactional unit — no CPU or network I/O
 * inside any transaction.</p>
 *
 * <p>TX1: beginGeneration() / replaceAndBeginGeneration()
 * TX2: completeGenerationAndPublish() / failGenerationAndPublish()</p>
 */
@Service
@Slf4j
public class TaxInvoicePdfDocumentService {

    private final TaxInvoicePdfDocumentRepository repository;
    private final PdfEventPort pdfEventPort;
    private final SagaReplyPort sagaReplyPort;

    @Autowired(required = false)
    private PdfGenerationMetrics pdfGenerationMetrics;

    public TaxInvoicePdfDocumentService(TaxInvoicePdfDocumentRepository repository,
                                         PdfEventPort pdfEventPort,
                                         SagaReplyPort sagaReplyPort,
                                         PdfGenerationMetrics pdfGenerationMetrics) {
        this.repository = repository;
        this.pdfEventPort = pdfEventPort;
        this.sagaReplyPort = sagaReplyPort;
        this.pdfGenerationMetrics = pdfGenerationMetrics;
    }

    @Transactional(readOnly = true)
    public Optional<TaxInvoicePdfDocument> findByTaxInvoiceId(String taxInvoiceId) {
        return repository.findByTaxInvoiceId(taxInvoiceId);
    }

    @Transactional
    public TaxInvoicePdfDocument beginGeneration(String taxInvoiceId, String taxInvoiceNumber) {
        log.info("Initiating PDF generation for tax invoice: {}", taxInvoiceNumber);
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId(taxInvoiceId)
                .taxInvoiceNumber(taxInvoiceNumber)
                .build();
        doc.startGeneration();
        return repository.save(doc);
    }

    @Transactional
    public TaxInvoicePdfDocument replaceAndBeginGeneration(
            UUID existingId, int previousRetryCount, String taxInvoiceId, String taxInvoiceNumber) {
        log.info("Replacing document {} and re-starting generation for tax invoice: {}", existingId, taxInvoiceNumber);
        repository.deleteById(existingId);
        repository.flush();
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId(taxInvoiceId)
                .taxInvoiceNumber(taxInvoiceNumber)
                .build();
        doc.startGeneration();
        // Restore retry count state using incrementRetryCountTo for monotonic guarantee
        doc.incrementRetryCountTo(previousRetryCount + 1);
        return repository.save(doc);
    }

    @Transactional
    public void completeGenerationAndPublish(UUID documentId, String s3Key, String fileUrl,
                                             long fileSize, int previousRetryCount,
                                             String cmdDocumentId, String cmdDocumentNumber,
                                             String sagaId, SagaStep sagaStep, String correlationId) {
        TaxInvoicePdfDocument doc = requireDocument(documentId);
        doc.markCompleted(s3Key, fileUrl, fileSize);
        doc.markXmlEmbedded();
        applyRetryCount(doc, previousRetryCount);
        doc = repository.save(doc);

        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(doc, cmdDocumentId, cmdDocumentNumber, sagaId, correlationId));
        sagaReplyPort.publishSuccess(
                sagaId, sagaStep, correlationId,
                doc.getDocumentUrl(), doc.getFileSize());

        log.info("Completed PDF generation for saga {} tax invoice {}",
                sagaId, doc.getTaxInvoiceNumber());
    }

    @Transactional
    public void failGenerationAndPublish(UUID documentId, String errorMessage,
                                         int previousRetryCount,
                                         String sagaId, SagaStep sagaStep, String correlationId) {
        String safeError = errorMessage != null ? errorMessage : "PDF generation failed";
        TaxInvoicePdfDocument doc = requireDocument(documentId);
        doc.markFailed(safeError);
        applyRetryCount(doc, previousRetryCount);
        repository.save(doc);

        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, safeError);

        log.warn("PDF generation failed for saga {} tax invoice {}: {}",
                sagaId, doc.getTaxInvoiceNumber(), safeError);
    }

    @Transactional
    public void deleteById(UUID documentId) {
        repository.deleteById(documentId);
        repository.flush();
    }

    @Transactional
    public void publishIdempotentSuccess(TaxInvoicePdfDocument existing,
                                         String cmdDocumentId, String cmdDocumentNumber,
                                         String sagaId, SagaStep sagaStep, String correlationId) {
        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(existing, cmdDocumentId, cmdDocumentNumber, sagaId, correlationId));
        sagaReplyPort.publishSuccess(sagaId, sagaStep, correlationId, existing.getDocumentUrl(), existing.getFileSize());
        log.warn("Tax invoice PDF already generated for saga {} — re-publishing SUCCESS reply", sagaId);
    }

    @Transactional
    public void publishRetryExhausted(String sagaId, SagaStep sagaStep, String correlationId,
                                       String cmdDocumentId, String cmdDocumentNumber) {
        if (pdfGenerationMetrics != null) {
            pdfGenerationMetrics.recordRetryExhausted(sagaId, cmdDocumentId, cmdDocumentNumber);
        }
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, "Maximum retry attempts exceeded");
        log.error("Max retries exceeded for saga {} document {}", sagaId, cmdDocumentNumber);
    }

    @Transactional
    public void publishGenerationFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage) {
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, errorMessage);
    }

    @Transactional
    public void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId) {
        sagaReplyPort.publishCompensated(sagaId, sagaStep, correlationId);
    }

    @Transactional
    public void publishCompensationFailure(String sagaId, SagaStep sagaStep, String correlationId, String error) {
        sagaReplyPort.publishFailure(sagaId, sagaStep, correlationId, error);
    }

    private TaxInvoicePdfDocument requireDocument(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> {
                    log.error("TaxInvoicePdfDocument not found for id={}", documentId);
                    return new IllegalStateException("Expected tax invoice PDF document is absent");
                });
    }

    /**
     * Restore retry count state when replacing a document.
     */
    private void applyRetryCount(TaxInvoicePdfDocument doc, int previousRetryCount) {
        if (previousRetryCount < 0) return;
        doc.incrementRetryCountTo(previousRetryCount + 1);
    }

    private TaxInvoicePdfGeneratedEvent buildGeneratedEvent(TaxInvoicePdfDocument doc,
                                                             String cmdDocumentId, String cmdDocumentNumber,
                                                             String sagaId, String correlationId) {
        return new TaxInvoicePdfGeneratedEvent(
                sagaId,
                cmdDocumentId,
                doc.getTaxInvoiceNumber(),
                doc.getDocumentUrl(),
                doc.getFileSize(),
                doc.isXmlEmbedded(),
                correlationId);
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Should compile now (or very few errors)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentService.java
git commit -m "refactor: update TaxInvoicePdfDocumentService method signatures to use plain fields instead of command objects"
```

---

## Task 8: Inline `TaxInvoicePdfReplyEvent` factory in `SagaReplyPublisher`

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/SagaReplyPublisher.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfReplyEvent.java` (later in Task 9)

`TaxInvoicePdfReplyEvent` will be replaced with static factory methods inside `SagaReplyPublisher`. The `SagaReplyPort` interface already defines `publishSuccess`, `publishFailure`, and `publishCompensated` with plain parameters — those stay the same.

- [ ] **Step 1: Rewrite SagaReplyPublisher to inline the reply event factory**

Remove the `TaxInvoicePdfReplyEvent` import and create local static factory methods. The `ReplyStatus` enum and `SagaReply` class are from `com.wpanther.saga.domain.enums` and `com.wpanther.saga.domain.model`.

Review the current `SagaReplyPublisher` and `TaxInvoicePdfReplyEvent` to understand the current structure, then inline the reply event as a private static inner class. The key method signatures to keep:
- `publishSuccess(String sagaId, SagaStep sagaStep, String correlationId, String pdfUrl, long pdfSize)`
- `publishFailure(String sagaId, SagaStep sagaStep, String correlationId, String errorMessage)`
- `publishCompensated(String sagaId, SagaStep sagaStep, String correlationId)`

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Should compile cleanly

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/SagaReplyPublisher.java
git commit -m "refactor: inline TaxInvoicePdfReplyEvent factory in SagaReplyPublisher"
```

---

## Task 9: Delete old files from `infrastructure/adapter/in/kafka/`

**Files:**
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceCompensateCommand.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfReplyEvent.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java`  # original location
- Delete: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java`  # becomes dead code after mapper deletion

- [ ] **Step 1: Delete all old files**

```bash
rm src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java
rm src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceCompensateCommand.java
rm src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java
rm src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfReplyEvent.java
rm src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java
rm src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Clean compile

- [ ] **Step 3: Commit**

```bash
git add -A  # stage deletions
git commit -m "refactor: delete old saga command classes from infrastructure/"
```

---

## Task 10: Delete `application/service/SagaCommandHandler` (original location) and `application/usecase/`

**Files:**
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandler.java` (original location)
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/ProcessTaxInvoicePdfUseCase.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/CompensateTaxInvoicePdfUseCase.java`

```bash
rm src/main/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandler.java
rm src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/ProcessTaxInvoicePdfUseCase.java
rm src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/CompensateTaxInvoicePdfUseCase.java
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Clean compile

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: delete original SagaCommandHandler and old usecase interfaces"
```

---

## Task 11: Update `SagaRouteConfig` — remove `KafkaCommandMapper`, use new DTO names

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java`

Key changes:
1. Remove `KafkaCommandMapper` field and constructor parameter
2. Change `commandMapper.toProcess(cmd)` calls to direct DTO usage
3. The route processors now call `processUseCase.handle(docId, docNumber, signedXmlUrl, sagaId, step, corrId)` directly using DTO getters
4. `onPrepareFailure` block uses `ProcessTaxInvoicePdfCommand` / `CompensateTaxInvoicePdfCommand` (from `dto/` package)

- [ ] **Step 1: Rewrite SagaRouteConfig**

Review the current `SagaRouteConfig` and update it to:
1. Remove `KafkaCommandMapper` dependency
2. Use `ProcessTaxInvoicePdfCommand` and `CompensateTaxInvoicePdfCommand` from the new `dto/` package
3. Call use case interfaces with extracted fields

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.pdf.application.port.in.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.port.in.ProcessTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto.ProcessTaxInvoicePdfCommand;
import com.wpanther.saga.domain.enums.SagaStep;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

    private final ProcessTaxInvoicePdfUseCase processUseCase;
    private final CompensateTaxInvoicePdfUseCase compensateUseCase;
    private final SagaCommandHandler sagaCommandHandler;
    private final ObjectMapper objectMapper;

    public SagaRouteConfig(ProcessTaxInvoicePdfUseCase processUseCase,
                           CompensateTaxInvoicePdfUseCase compensateUseCase,
                           SagaCommandHandler sagaCommandHandler,
                           ObjectMapper objectMapper) {
        this.processUseCase = processUseCase;
        this.compensateUseCase = compensateUseCase;
        this.sagaCommandHandler = sagaCommandHandler;
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure() throws Exception {

        errorHandler(deadLetterChannel(
                        "kafka:{{app.kafka.topics.dlq}}?brokers={{app.kafka.bootstrap-servers}}")
                        .maximumRedeliveries(3)
                        .redeliveryDelay(1000)
                        .useExponentialBackOff()
                        .backOffMultiplier(2)
                        .maximumRedeliveryDelay(10000)
                        .logExhausted(true)
                        .logStackTrace(true)
                        .onPrepareFailure(exchange -> {
                            Throwable cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
                            Object body = exchange.getIn().getBody();
                            if (body instanceof ProcessTaxInvoicePdfCommand cmd) {
                                log.error("DLQ: notifying orchestrator of retry exhaustion for saga {} document {}",
                                        cmd.getSagaId(), cmd.getDocumentNumber());
                                sagaCommandHandler.publishOrchestrationFailure(
                                        cmd.getSagaId(), cmd.getSagaStep(), cmd.getCorrelationId(), cause);
                            } else if (body instanceof CompensateTaxInvoicePdfCommand cmd) {
                                log.error("DLQ: notifying orchestrator of compensation retry exhaustion for saga {} document {}",
                                        cmd.getSagaId(), cmd.getDocumentId());
                                sagaCommandHandler.publishCompensationOrchestrationFailure(
                                        cmd.getSagaId(), cmd.getSagaStep(), cmd.getCorrelationId(), cause);
                            } else {
                                log.error("DLQ: body not deserialized ({}); attempting saga metadata recovery",
                                        body == null ? "null" : body.getClass().getSimpleName());
                                recoverAndNotifyOrchestrator(body, cause);
                            }
                        }));

        from("kafka:{{app.kafka.topics.saga-command-tax-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.command-group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count}}")
                        .routeId("saga-command-consumer")
                        .log(LoggingLevel.DEBUG, "Received saga command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, ProcessTaxInvoicePdfCommand.class)
                        .process(exchange -> {
                                ProcessTaxInvoicePdfCommand cmd = exchange.getIn().getBody(ProcessTaxInvoicePdfCommand.class);
                                log.info("Processing saga command for saga: {}, document: {}",
                                                cmd.getSagaId(), cmd.getDocumentNumber());
                                processUseCase.handle(
                                        cmd.getDocumentId(),
                                        cmd.getDocumentNumber(),
                                        cmd.getSignedXmlUrl(),
                                        cmd.getSagaId(),
                                        cmd.getSagaStep(),
                                        cmd.getCorrelationId());
                        })
                        .log("Successfully processed saga command");

        from("kafka:{{app.kafka.topics.saga-compensation-tax-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.compensation-group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count}}")
                        .routeId("saga-compensation-consumer")
                        .log(LoggingLevel.DEBUG, "Received compensation command from Kafka: partition=${header[kafka.PARTITION]}, offset=${header[kafka.OFFSET]}")
                        .unmarshal().json(JsonLibrary.Jackson, CompensateTaxInvoicePdfCommand.class)
                        .process(exchange -> {
                                CompensateTaxInvoicePdfCommand cmd = exchange.getIn().getBody(CompensateTaxInvoicePdfCommand.class);
                                log.info("Processing compensation for saga: {}, document: {}",
                                                cmd.getSagaId(), cmd.getDocumentId());
                                compensateUseCase.handle(
                                        cmd.getDocumentId(),
                                        cmd.getSagaId(),
                                        cmd.getSagaStep(),
                                        cmd.getCorrelationId());
                        })
                        .log("Successfully processed compensation command");
    }

    private void recoverAndNotifyOrchestrator(Object body, Throwable cause) {
        if (body == null) {
            log.error("DLQ: null message body — orchestrator must timeout");
            return;
        }
        try {
            byte[] rawBytes = body instanceof byte[] b
                    ? b
                    : body.toString().getBytes(StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(rawBytes);
            String sagaId = node.path("sagaId").asText(null);
            String sagaStepStr = node.path("sagaStep").asText(null);
            String correlationId = node.path("correlationId").asText(null);

            if (sagaId == null || sagaStepStr == null) {
                log.error("DLQ: saga metadata missing in raw message — orchestrator must timeout");
                return;
            }
            SagaStep sagaStep = objectMapper.readValue("\"" + sagaStepStr + "\"", SagaStep.class);
            sagaCommandHandler.publishOrchestrationFailureForUnparsedMessage(sagaId, sagaStep, correlationId, cause);
        } catch (Exception parseEx) {
            log.error("DLQ: cannot parse raw message for saga metadata — orchestrator must timeout", parseEx);
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `mvn compile -q 2>&1 | head -40`
Expected: Clean compile

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java
git commit -m "refactor: update SagaRouteConfig to use new DTO package and remove KafkaCommandMapper"
```

---

## Task 12: Build and test

**Files:**
- All modified files

- [ ] **Step 1: Full clean compile**

Run: `mvn clean compile 2>&1 | tail -20`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run tests**

Run: `mvn clean test 2>&1 | tail -30`
Expected: BUILD SUCCESS (all tests pass)

- [ ] **Step 3: Commit all remaining changes**

```bash
git add -A
git commit -m "refactor: complete layer separation — saga types in infrastructure, plain-parameter use cases in application/port/in/"
```

---

## Verification Checklist

After all tasks:

```bash
# 1. Compile check
mvn clean compile -q

# 2. All tests pass
mvn clean test -q

# 3. Confirm new structure
ls src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/dto/
# Expected: CompensateTaxInvoicePdfCommand.java, ProcessTaxInvoicePdfCommand.java

ls src/main/java/com/wpanther/taxinvoice/pdf/application/port/in/
# Expected: CompensateTaxInvoicePdfUseCase.java, ProcessTaxInvoicePdfUseCase.java

ls src/main/java/com/wpanther/taxinvoice/pdf/application/dto/event/
# Expected: TaxInvoicePdfGeneratedEvent.java

ls src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/
# Expected: SagaCommandHandler.java, SagaRouteConfig.java (NO KafkaCommandMapper, NO Kafka*Command files)
```

---

## Self-Review Checklist

- [ ] `ProcessTaxInvoicePdfCommand` and `CompensateTaxInvoicePdfCommand` are in `infrastructure/adapter/in/kafka/dto/`
- [ ] Use case interfaces in `application/port/in/` have plain parameter signatures (no command objects)
- [ ] `SagaCommandHandler` is in `infrastructure/adapter/in/kafka/` and calls use cases with extracted fields
- [ ] `TaxInvoicePdfGeneratedEvent` is in `application/dto/event/`
- [ ] `TaxInvoicePdfReplyEvent` is inlined in `SagaReplyPublisher`
- [ ] `KafkaCommandMapper` is deleted
- [ ] `KafkaCommandMapperTest.java` is deleted
- [ ] `KafkaTaxInvoiceProcessCommand` and `KafkaTaxInvoiceCompensateCommand` are deleted
- [ ] `application/usecase/` is deleted (interfaces moved to `application/port/in/`)
- [ ] All tests pass with `mvn clean test`
- [ ] Compilation succeeds with `mvn clean compile`