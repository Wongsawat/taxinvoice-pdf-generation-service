# Hexagonal Architecture Migration Design
**Service**: taxinvoice-pdf-generation-service
**Date**: 2026-03-09
**Approach**: Full Canonical Hexagonal Architecture (DDD + Port/Adapter Pattern)
**Reference**: Mirrors invoice-pdf-generation-service design exactly.

---

## Goals

- Enforce the dependency rule end-to-end: `domain` ← `application` ← `infrastructure`
- Add missing `application/port/out/` (currently absent entirely)
- Add missing `application/usecase/` inbound ports
- Extract `MinioStorageAdapter` and `RestTemplateSignedXmlFetcher` from `SagaCommandHandler`
- Move all Kafka wire DTOs out of `domain/event/` into adapter packages
- Restructure `infrastructure/` into `adapter/in/` and `adapter/out/` sub-packages
- Rename `CamelRouteConfig` → `SagaRouteConfig`, `JpaTaxInvoicePdfDocumentRepositoryImpl` → `TaxInvoicePdfDocumentRepositoryAdapter`
- Add missing test classes to reach 90%+ JaCoCo coverage

---

## Layer Responsibilities

| Layer | Purpose | Contents |
|-------|---------|----------|
| `domain/` | Core business rules — zero framework imports | `model/`, `event/` (empty), `exception/`, `repository/` (domain-owned port), `service/` |
| `application/` | Orchestration — imports domain only | `usecase/` (inbound ports), `port/out/` (non-domain outbound ports), `service/` |
| `infrastructure/` | All outside-world interactions | `config/`, `adapter/in/`, `adapter/out/` |

---

## Target Package Structure

```
com.wpanther.taxinvoice.pdf/
│
├── domain/
│   ├── model/
│   │   ├── TaxInvoicePdfDocument.java            (unchanged)
│   │   └── GenerationStatus.java                 (unchanged)
│   ├── event/                                     (reserved — empty after migration)
│   ├── exception/
│   │   └── TaxInvoicePdfGenerationException.java (NEW)
│   ├── repository/
│   │   └── TaxInvoicePdfDocumentRepository.java  (STAYS — domain-owned outbound port)
│   └── service/
│       └── TaxInvoicePdfGenerationService.java   (unchanged)
│
├── application/
│   ├── usecase/                                   (NEW — inbound ports)
│   │   ├── ProcessTaxInvoicePdfUseCase.java
│   │   └── CompensateTaxInvoicePdfUseCase.java
│   ├── port/out/                                  (NEW — non-domain outbound ports)
│   │   ├── PdfStoragePort.java
│   │   ├── SagaReplyPort.java
│   │   ├── PdfEventPort.java
│   │   └── SignedXmlFetchPort.java
│   └── service/
│       ├── SagaCommandHandler.java                (implements both use-case interfaces)
│       └── TaxInvoicePdfDocumentService.java      (updated to use ports)
│
└── infrastructure/
    ├── config/
    │   ├── MinioConfig.java                       (STAYS — bean factory)
    │   └── OutboxConfig.java                      (STAYS — bean factory)
    └── adapter/
        ├── in/
        │   └── kafka/
        │       ├── SagaRouteConfig.java                    (MOVED+RENAMED from config/CamelRouteConfig.java)
        │       ├── KafkaTaxInvoiceProcessCommand.java      (MOVED+RENAMED from domain/event/)
        │       ├── KafkaTaxInvoiceCompensateCommand.java   (MOVED+RENAMED from domain/event/)
        │       └── KafkaCommandMapper.java                 (NEW)
        └── out/
            ├── persistence/
            │   ├── TaxInvoicePdfDocumentEntity.java
            │   │   (MOVED from infrastructure/persistence/)
            │   ├── TaxInvoicePdfDocumentRepositoryAdapter.java
            │   │   (RENAMED from JpaTaxInvoicePdfDocumentRepositoryImpl;
            │   │    implements TaxInvoicePdfDocumentRepository)
            │   ├── JpaTaxInvoicePdfDocumentRepository.java
            │   │   (MOVED)
            │   └── outbox/
            │       ├── OutboxEventEntity.java
            │       ├── JpaOutboxEventRepository.java
            │       └── SpringDataOutboxRepository.java
            ├── messaging/
            │   ├── EventPublisher.java               (MOVED; implements PdfEventPort)
            │   ├── SagaReplyPublisher.java           (MOVED; implements SagaReplyPort)
            │   ├── OutboxConstants.java              (MOVED)
            │   ├── TaxInvoicePdfReplyEvent.java      (MOVED from domain/event/)
            │   └── TaxInvoicePdfGeneratedEvent.java  (MOVED from domain/event/)
            ├── storage/
            │   └── MinioStorageAdapter.java          (NEW; implements PdfStoragePort)
            ├── client/
            │   └── RestTemplateSignedXmlFetcher.java (NEW; implements SignedXmlFetchPort)
            └── pdf/
                ├── FopTaxInvoicePdfGenerator.java        (MOVED from infrastructure/pdf/)
                ├── PdfA3Converter.java                   (MOVED)
                └── TaxInvoicePdfGenerationServiceImpl.java (MOVED)
```

---

## New Output Ports (`application/port/out/`)

```java
public interface PdfStoragePort {
    String store(String taxInvoiceNumber, byte[] pdfBytes);
    void delete(String s3Key);
    String resolveUrl(String s3Key);
}

public interface SagaReplyPort {
    void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId,
                        String pdfUrl, long pdfSize);
    void publishFailure(String sagaId, SagaStep sagaStep, String correlationId,
                        String errorMessage);
    void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId);
}

public interface PdfEventPort {
    void publishPdfGenerated(TaxInvoicePdfGeneratedEvent event);
}

public interface SignedXmlFetchPort {
    String fetch(String signedXmlUrl);
}
```

---

## New Input Ports (`application/usecase/`)

```java
public interface ProcessTaxInvoicePdfUseCase {
    void handle(KafkaTaxInvoiceProcessCommand command);
}

public interface CompensateTaxInvoicePdfUseCase {
    void handle(KafkaTaxInvoiceCompensateCommand command);
}
```

`SagaCommandHandler` implements both with `@Override`. `SagaRouteConfig` injects the interfaces — never the concrete class.

---

## New Adapter Classes

| Class | Package | Source |
|-------|---------|--------|
| `MinioStorageAdapter` | `adapter/out/storage/` | Logic extracted from `SagaCommandHandler`. Implements `PdfStoragePort`. Add Resilience4j circuit breaker. |
| `RestTemplateSignedXmlFetcher` | `adapter/out/client/` | Logic extracted from `SagaCommandHandler`. Implements `SignedXmlFetchPort`. |
| `KafkaCommandMapper` | `adapter/in/kafka/` | Maps wire DTOs → domain command objects. |
| `TaxInvoicePdfGenerationException` | `domain/exception/` | Replaces `IllegalStateException` in aggregate. |

---

## Renamed Classes

| Old | New | Reason |
|-----|-----|--------|
| `CamelRouteConfig` | `SagaRouteConfig` | Consistent with invoice service; moved to `adapter/in/kafka/` |
| `JpaTaxInvoicePdfDocumentRepositoryImpl` | `TaxInvoicePdfDocumentRepositoryAdapter` | "Adapter" is the Hexagonal term |
| `ProcessTaxInvoicePdfCommand` | `KafkaTaxInvoiceProcessCommand` | `Kafka` prefix marks it as wire DTO |
| `CompensateTaxInvoicePdfCommand` | `KafkaTaxInvoiceCompensateCommand` | Same |

---

## Data Flow

### Command Processing (Happy Path)

```
Kafka → SagaRouteConfig
    deserialise → KafkaTaxInvoiceProcessCommand
    KafkaCommandMapper.toProcess()
    ProcessTaxInvoicePdfUseCase.handle(command)       [SagaCommandHandler]
        [TX 1 ~10ms]  beginGeneration()
            TaxInvoicePdfDocumentRepository.findByTaxInvoiceId() → idempotency check
            TaxInvoicePdfDocument.startGeneration()              → PENDING→GENERATING
            TaxInvoicePdfDocumentRepository.save()
        [NO TX ~1-3s]
            SignedXmlFetchPort.fetch(signedXmlUrl)               → RestTemplateSignedXmlFetcher
            TaxInvoicePdfGenerationService.generatePdf(...)      → TaxInvoicePdfGenerationServiceImpl
                convertJsonToXml()
                FopTaxInvoicePdfGenerator.generatePdf()          → Semaphore-guarded
                PdfA3Converter.convertToPdfA3()
            PdfStoragePort.store(bytes)                          → MinioStorageAdapter (CB)
        [TX 2 ~100ms]  completeGenerationAndPublish()
            TaxInvoicePdfDocument.markCompleted(url, size)       → GENERATING→COMPLETED
            TaxInvoicePdfDocumentRepository.save()
            PdfEventPort.publishPdfGenerated(...)                → outbox row
            SagaReplyPort.publishSuccess(...)                    → outbox row
```

### Compensation Flow

```
Kafka → SagaRouteConfig
    → KafkaTaxInvoiceCompensateCommand
    CompensateTaxInvoicePdfUseCase.handle()           [SagaCommandHandler]
        [TX] deleteAndPublishCompensated()
            TaxInvoicePdfDocumentRepository.deleteById() + flush
            SagaReplyPort.publishCompensated(...)     → outbox row
        [best-effort, no TX]
            PdfStoragePort.delete(s3Key)              → MinioStorageAdapter (CB)
```

---

## Dependency Rule — Import Graph

```
infrastructure/adapter/in/kafka       → application/usecase
infrastructure/adapter/out/persistence → domain/repository, domain/model
infrastructure/adapter/out/messaging   → application/port/out
infrastructure/adapter/out/storage     → application/port/out
infrastructure/adapter/out/client      → application/port/out
infrastructure/adapter/out/pdf         → domain/service

application/service  → application/usecase (implements)
                     → application/port/out (injected)
                     → domain/repository    (injected)
                     → domain/model
                     → domain/service

domain/*  → (nothing outside domain)
```

---

## Error Handling

| Failure point | Behaviour |
|---|---|
| Deserialization error | Dead Letter Channel → `pdf.generation.tax-invoice.dlq` after 3 Camel retries |
| `SignedXmlFetchPort` throws | GENERATING→FAILED + `SagaReplyPort.publishFailure()` |
| FOP/PDFBox throws | Same — FAILED + FAILURE reply |
| `PdfStoragePort` throws (CB open) | Same path |
| TX 2 fails | Camel retry → idempotency check (COMPLETED re-publishes) |
| Max retries exceeded | `publishOrchestrationFailure()` in `REQUIRES_NEW` TX before DLQ |
| Domain invariant violated | `TaxInvoicePdfGenerationException` from aggregate |

---

## Testing Strategy

### Test Package Structure

```
test/java/com/wpanther/taxinvoice/pdf/
├── domain/
│   ├── model/TaxInvoicePdfDocumentTest.java                       (EXISTS — update exception type)
│   └── exception/TaxInvoicePdfGenerationExceptionTest.java        (NEW)
├── application/
│   └── service/
│       ├── SagaCommandHandlerTest.java                            (EXISTS — update imports + port mocks)
│       └── TaxInvoicePdfDocumentServiceTest.java                  (EXISTS — update imports)
└── infrastructure/
    ├── adapter/
    │   ├── in/kafka/
    │   │   ├── SagaRouteConfigTest.java                           (MOVED from config/CamelRouteConfigTest.java)
    │   │   └── KafkaCommandMapperTest.java                        (NEW)
    │   └── out/
    │       ├── persistence/
    │       │   ├── TaxInvoicePdfDocumentRepositoryAdapterTest.java (NEW — unit)
    │       │   └── TaxInvoicePdfDocumentRepositoryIntegrationTest.java (NEW — Testcontainers)
    │       ├── messaging/
    │       │   ├── EventPublisherTest.java                        (NEW)
    │       │   └── SagaReplyPublisherTest.java                    (NEW)
    │       ├── storage/
    │       │   └── MinioStorageAdapterTest.java                   (NEW)
    │       ├── client/
    │       │   └── RestTemplateSignedXmlFetcherTest.java          (NEW)
    │       └── pdf/
    │           ├── FopTaxInvoicePdfGeneratorTest.java             (MOVED from infrastructure/pdf/)
    │           ├── PdfA3ConverterTest.java                        (NEW — currently missing)
    │           └── TaxInvoicePdfGenerationServiceImplTest.java    (MOVED from infrastructure/pdf/)
    └── ApplicationContextLoadTest.java                            (NEW)
```

### Coverage Gates

| Scope | Target |
|-------|--------|
| `domain/` | 95%+ line coverage |
| `application/` | 95%+ line coverage |
| `infrastructure/adapter/` | 90%+ line coverage (JaCoCo `mvn verify`) |

---

## Migration Checklist

### Phase 1 — Domain Cleanup
- [ ] Add `TaxInvoicePdfGenerationException`
- [ ] Replace `IllegalStateException` in `TaxInvoicePdfDocument` with `TaxInvoicePdfGenerationException`

### Phase 2 — Application Ports
- [ ] Create all 4 `application/port/out/` interfaces (`PdfStoragePort`, `SagaReplyPort`, `PdfEventPort`, `SignedXmlFetchPort`)
- [ ] Create `application/usecase/ProcessTaxInvoicePdfUseCase`
- [ ] Create `application/usecase/CompensateTaxInvoicePdfUseCase`
- [ ] `SagaCommandHandler` implements both use-case interfaces (rename handler methods to `handle()`)

### Phase 3 — Refactor SagaCommandHandler to use ports
- [ ] Extract MinIO logic → `MinioStorageAdapter` implements `PdfStoragePort`
- [ ] Extract HTTP download logic → `RestTemplateSignedXmlFetcher` implements `SignedXmlFetchPort`
- [ ] Update `SagaCommandHandler` to inject and call ports
- [ ] Update `TaxInvoicePdfDocumentService` to use `PdfEventPort` and `SagaReplyPort`
- [ ] Update `EventPublisher` to implement `PdfEventPort`
- [ ] Update `SagaReplyPublisher` to implement `SagaReplyPort`

### Phase 4 — Kafka Inbound Adapter
- [ ] Create `KafkaTaxInvoiceProcessCommand` (renamed from `ProcessTaxInvoicePdfCommand`)
- [ ] Create `KafkaTaxInvoiceCompensateCommand` (renamed from `CompensateTaxInvoicePdfCommand`)
- [ ] Create `KafkaCommandMapper`
- [ ] Create new `SagaRouteConfig` in `adapter/in/kafka/` (renamed from `CamelRouteConfig`; injects use-case interfaces)
- [ ] Delete `infrastructure/config/CamelRouteConfig.java`
- [ ] Delete `domain/event/ProcessTaxInvoicePdfCommand.java`
- [ ] Delete `domain/event/CompensateTaxInvoicePdfCommand.java`

### Phase 5 — Outbound Adapters Restructure
- [ ] Move `infrastructure/persistence/` → `adapter/out/persistence/`; rename `JpaTaxInvoicePdfDocumentRepositoryImpl` → `TaxInvoicePdfDocumentRepositoryAdapter`
- [ ] Move `infrastructure/messaging/` → `adapter/out/messaging/`
- [ ] Move `TaxInvoicePdfReplyEvent` + `TaxInvoicePdfGeneratedEvent` (from `domain/event/`) → `adapter/out/messaging/`
- [ ] Move `infrastructure/pdf/` → `adapter/out/pdf/`
- [ ] Place `MinioStorageAdapter` in `adapter/out/storage/`
- [ ] Place `RestTemplateSignedXmlFetcher` in `adapter/out/client/`

### Phase 6 — Config Cleanup & Verification
- [ ] Verify `infrastructure/config/` contains only `MinioConfig` and `OutboxConfig`
- [ ] Delete empty `domain/event/` package
- [ ] Run `mvn verify` — confirm 90% JaCoCo gate passes

### Phase 7 — Test Migration & Additions
- [ ] Move all test files to mirror new package structure
- [ ] Add `TaxInvoicePdfGenerationExceptionTest`
- [ ] Add `KafkaCommandMapperTest`
- [ ] Add `TaxInvoicePdfDocumentRepositoryAdapterTest` (unit + Testcontainers integration)
- [ ] Add `EventPublisherTest`, `SagaReplyPublisherTest`
- [ ] Add `MinioStorageAdapterTest`, `RestTemplateSignedXmlFetcherTest`
- [ ] Add `PdfA3ConverterTest`
- [ ] Add `ApplicationContextLoadTest`
- [ ] Run `mvn verify` — all tests pass, coverage gates met

---

## Files Changed Summary

| Action | Count |
|--------|-------|
| New (domain) | `TaxInvoicePdfGenerationException` |
| New (application ports) | 4 output port interfaces + 2 use-case interfaces |
| New (adapters) | `MinioStorageAdapter`, `RestTemplateSignedXmlFetcher`, `KafkaCommandMapper` |
| Moved + renamed (classes) | `CamelRouteConfig` → `SagaRouteConfig`, `JpaTaxInvoicePdfDocumentRepositoryImpl` → `TaxInvoicePdfDocumentRepositoryAdapter`, `ProcessTaxInvoicePdfCommand` → `KafkaTaxInvoiceProcessCommand`, `CompensateTaxInvoicePdfCommand` → `KafkaTaxInvoiceCompensateCommand` |
| Moved (outbound Kafka DTOs) | `TaxInvoicePdfReplyEvent`, `TaxInvoicePdfGeneratedEvent` → `adapter/out/messaging/` |
| Moved (infrastructure packages) | `persistence/`, `messaging/`, `pdf/` → `adapter/out/` sub-packages |
| Deleted packages | `domain/event/`, `infrastructure/config/CamelRouteConfig`, old infrastructure flat packages |
| New tests | 10 new test classes |
| Updated tests | 4 existing test classes (import + port mock updates) |
