# taxinvoice-pdf-generation-service Layer Separation Refactoring

**Date:** 2026-04-30
**Author:** Claude Code
**Status:** Draft

## Context

`taxinvoice-pdf-generation-service` currently has saga infrastructure types (`SagaCommand`, `SagaReply`, `TraceEvent`) and Jackson serialization annotations placed in the domain layer or mixed across infrastructure/application layers. This violates Hexagonal/Port-Adapters architecture principles — the domain layer should have no framework or infrastructure dependencies.

The reference implementation is `taxinvoice-processing-service`, which correctly separates:
- Kafka DTOs with saga inheritance and Jackson annotations → `infrastructure/adapter/in/kafka/dto/`
- Use case interfaces with plain parameters → `application/port/in/`
- Pure domain events (no framework annotations) → `domain/event/`

Additionally, `invoice-pdf-generation-service` has already been refactored to this pattern, providing a second reference.

## Problem Statement

The current structure has several violations of the hexagonal architecture:

1. **`application/usecase/`** contains use case interfaces that accept `KafkaTaxInvoiceProcessCommand` (a Kafka DTO) — command objects leak into the application layer
2. **`SagaCommandHandler`** is in `application/service/` but should be in `infrastructure/adapter/in/kafka/` as it handles Kafka message routing
3. **`KafkaCommandMapper`** maps Kafka DTOs to use cases — this indirection is unnecessary when use cases accept plain parameters
4. **`TaxInvoicePdfGeneratedEvent`** lives in `infrastructure/adapter/out/messaging/` but should move to `application/dto/event/` (it's a notification DTO, not pure domain)
5. **`TaxInvoicePdfReplyEvent`** lives alongside generated event — should be inlined into `SagaReplyPublisher`
6. **`domain/event/`** may contain saga types if any exist there

## Target Architecture

```
infrastructure/adapter/in/kafka/dto/
├── ProcessTaxInvoicePdfCommand    ← extends SagaCommand + Jackson (rename from KafkaTaxInvoiceProcessCommand)
└── CompensateTaxInvoicePdfCommand ← extends SagaCommand + Jackson (rename from KafkaTaxInvoiceCompensateCommand)

infrastructure/adapter/in/kafka/
├── SagaCommandHandler              ← (moved from application/service/)
└── SagaRouteConfig                  ← (unchanged)

application/port/in/
├── ProcessTaxInvoicePdfUseCase     ← (refactored from usecase/, plain parameter signatures)
└── CompensateTaxInvoicePdfUseCase ← (refactored from usecase/, plain parameter signatures)

application/service/
└── TaxInvoicePdfDocumentService   ← (unchanged, method signatures will change)

application/dto/event/
└── TaxInvoicePdfGeneratedEvent    ← (moved from infrastructure/adapter/out/messaging/)

infrastructure/adapter/out/messaging/
├── SagaReplyPublisher              ← (unchanged, inline TaxInvoicePdfReplyEvent factory)
└── EventPublisher                  ← (unchanged)

domain/
├── model/                          ← (unchanged: TaxInvoicePdfDocument, GenerationStatus)
├── service/TaxInvoicePdfGenerationService ← (unchanged)
├── repository/                      ← (unchanged)
├── constants/                       ← (unchanged)
└── exception/                       ← (unchanged)
```

## Changes

### 1. `infrastructure/adapter/in/kafka/dto/`

**Rename and consolidate:**
- `KafkaTaxInvoiceProcessCommand` → `ProcessTaxInvoicePdfCommand`
- `KafkaTaxInvoiceCompensateCommand` → `CompensateTaxInvoicePdfCommand`
- Move Jackson annotations with the DTOs (they stay as Kafka deserialization concerns)

Package: `com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.dto`

### 2. `application/port/in/`

**Refactor use case interfaces** (existing files in `application/usecase/` are renamed and moved):

```java
public interface ProcessTaxInvoicePdfUseCase {
    void handle(String documentId, String documentNumber, String signedXmlUrl,
                String sagaId, SagaStep sagaStep, String correlationId);
}

public interface CompensateTaxInvoicePdfUseCase {
    void handle(String documentId, String sagaId, SagaStep sagaStep, String correlationId);
}
```

Package: `com.wpanther.taxinvoice.pdf.application.port.in`

### 3. `infrastructure/adapter/in/kafka/SagaCommandHandler`

**Move from** `application/service/SagaCommandHandler.java`
**To** `infrastructure/adapter/in/kafka/SagaCommandHandler.java`

Handles Kafka DTO → use case call translation. Calls `ProcessTaxInvoicePdfUseCase.handle()` and `CompensateTaxInvoicePdfUseCase.handle()` with extracted parameters — no command objects passed into domain/application layers.

### 4. `application/service/TaxInvoicePdfDocumentService`

**Modify method signatures.** Methods that currently accept command objects (`KafkaTaxInvoiceProcessCommand` / `KafkaTaxInvoiceCompensateCommand`) are updated to accept individual fields.

### 5. `application/dto/event/TaxInvoicePdfGeneratedEvent`

**Move from** `infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java`
**To** `application/dto/event/TaxInvoicePdfGeneratedEvent.java`

This class extends `TraceEvent` from the saga library — it's a notification DTO, not a pure domain event. Its proper home is `application/dto/event/`.

Package: `com.wpanther.taxinvoice.pdf.application.dto.event`

### 6. `application/service/SagaCommandHandler` — DELETE

After moving the routing logic to `infrastructure/adapter/in/kafka/SagaCommandHandler`, delete `application/service/SagaCommandHandler.java`.

### 7. `infrastructure/adapter/out/messaging/TaxInvoicePdfReplyEvent` — INLINE

Move the `TaxInvoicePdfReplyEvent` class inline into `SagaReplyPublisher` as a private static inner class, replacing the separate file.

### 8. `infrastructure/adapter/in/kafka/KafkaCommandMapper` — DELETE

The mapper becomes unnecessary since `SagaCommandHandler` directly extracts fields from DTOs and passes them as parameters. Remove after confirming no other usage.

### 9. `application/usecase/` — DELETE

After moving interfaces to `application/port/in/`, delete:
- `application/usecase/ProcessTaxInvoicePdfUseCase.java`
- `application/usecase/CompensateTaxInvoicePdfUseCase.java`

## Files to Modify

| Action | File |
|--------|------|
| Rename+move | `infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java` → `dto/ProcessTaxInvoicePdfCommand.java` |
| Rename+move | `infrastructure/adapter/in/kafka/KafkaTaxInvoiceCompensateCommand.java` → `dto/CompensateTaxInvoicePdfCommand.java` |
| Rename+move | `application/usecase/ProcessTaxInvoicePdfUseCase.java` → `port/in/ProcessTaxInvoicePdfUseCase.java` (signature changes) |
| Rename+move | `application/usecase/CompensateTaxInvoicePdfUseCase.java` → `port/in/CompensateTaxInvoicePdfUseCase.java` (signature changes) |
| Move | `application/service/SagaCommandHandler.java` → `infrastructure/adapter/in/kafka/SagaCommandHandler.java` |
| Move | `infrastructure/adapter/out/messaging/TaxInvoicePdfGeneratedEvent.java` → `application/dto/event/TaxInvoicePdfGeneratedEvent.java` |
| Delete | `infrastructure/adapter/in/kafka/KafkaCommandMapper.java` |
| Delete | `infrastructure/adapter/out/messaging/TaxInvoicePdfReplyEvent.java` (inlined into SagaReplyPublisher) |
| Delete | `application/service/SagaCommandHandler.java` (original location) |
| Delete | `application/usecase/ProcessTaxInvoicePdfUseCase.java` (original location) |
| Delete | `application/usecase/CompensateTaxInvoicePdfUseCase.java` (original location) |
| Modify | `TaxInvoicePdfDocumentService.java` (update method signatures) |
| Modify | `SagaRouteConfig.java` (remove KafkaCommandMapper usage) |
| Modify | `SagaReplyPublisher.java` (inline TaxInvoicePdfReplyEvent factory) |
| Modify | `EventPublisher.java` (import new package path for TaxInvoicePdfGeneratedEvent) |

## Testing Strategy

1. **Unit tests** — Update all test classes that reference the moved classes:
   - `SagaCommandHandlerTest` → update import for moved handler
   - `TaxInvoicePdfDocumentServiceTest` → update command parameter types
   - `TaxInvoicePdfGeneratedEvent` test → update package import

2. **Camel route tests** — `SagaRouteConfigTest` needs import updates

3. **Kafka consumer tests** — verify `ProcessTaxInvoicePdfCommand` / `CompensateTaxInvoicePdfCommand` deserialization still works

4. **Integration test** — Run full service against test infrastructure to verify saga orchestration still functions

## Verification

After refactoring, run:
```bash
mvn clean compile   # Verify no compilation errors
mvn clean test      # Verify all tests pass
```

## Scope

This refactor addresses **only** `taxinvoice-pdf-generation-service`. Other services with similar patterns are out of scope and should be audited separately.