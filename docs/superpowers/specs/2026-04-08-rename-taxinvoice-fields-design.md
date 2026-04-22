# Design: Rename taxInvoiceId/taxInvoiceNumber to documentId/documentNumber in Contract Events

**Date**: 2026-04-08
**Status**: Approved
**Scope**: taxinvoice-pdf-generation-service contract events only

## Background

The taxinvoice-processing-service recently renamed its contract events to use `documentId`/`documentNumber` instead of `taxInvoiceId`/`taxInvoiceNumber`. The orchestrator will send `documentId`/`documentNumber` fields going forward. The taxinvoice-pdf-generation-service must follow the same pattern.

The current `documentId` and `taxInvoiceId` fields carry the **same value** from the orchestrator. The `taxInvoiceId` field is redundant.

## Pattern (from taxinvoice-processing-service)

| Concept | Contract Event Field | Domain Model Field |
|---------|---------------------|-------------------|
| Primary identifier | `documentId` | `taxInvoiceId` (unchanged) |
| Display number | `documentNumber` | `taxInvoiceNumber` (unchanged) |

## Changes

### Kafka DTOs (infrastructure/adapter/in/kafka/)

**KafkaTaxInvoiceProcessCommand:**
- Remove `taxInvoiceId` field
- Rename `taxInvoiceNumber` → `documentNumber`
- Update both constructors and `@JsonProperty` annotations

**KafkaTaxInvoiceCompensateCommand:**
- Remove `taxInvoiceId` field (keep `documentId` only)
- Update both constructors

**KafkaCommandMapper:**
- No change needed (identity mapper — `toProcess()` and `toCompensate()` return `src` directly)

### Outbound Event

**TaxInvoicePdfGeneratedEvent:**
- Remove `taxInvoiceId` field
- Rename `taxInvoiceNumber` → `documentNumber`
- Update both constructors

### Application Service

**SagaCommandHandler:**
- `command.getTaxInvoiceId()` → `command.getDocumentId()`
- `command.getTaxInvoiceNumber()` → `command.getDocumentNumber()`
- MDC keys and all log messages updated

**TaxInvoicePdfDocumentService:**
- `buildGeneratedEvent()`: remove `taxInvoiceId` param
- `publishRetryExhausted()`: `command.getTaxInvoiceId()` → `command.getDocumentId()`

### Infrastructure

**SagaRouteConfig:**
- All `cmd.getTaxInvoiceId()` → `cmd.getDocumentId()`
- All `cmd.getTaxInvoiceNumber()` → `cmd.getDocumentNumber()`

**EventPublisher:**
- `event.getTaxInvoiceId()` → `event.getDocumentId()` for aggregateId and partitionKey

**PdfGenerationMetrics:**
- Update field references if they use `getTaxInvoiceId()`/`getTaxInvoiceNumber()` on commands

### Unchanged Files

- `TaxInvoicePdfDocument` (domain model) — keeps `taxInvoiceId`/`taxInvoiceNumber`
- `TaxInvoicePdfDocumentEntity` / persistence adapter — database layer unchanged
- `TaxInvoicePdfReplyEvent` — no invoice-specific fields
- `SagaReplyPublisher` — no invoice-specific fields
- Database schema (Flyway migrations) — column names stay the same

## Test Updates

All test files referencing `taxInvoiceId`/`taxInvoiceNumber` on command/event classes must be updated.

## Verification

1. `mvn clean compile` — all source compiles
2. `mvn test` — all tests pass
3. Manual review that no `taxInvoiceId` or `taxInvoiceNumber` remains in contract event classes
