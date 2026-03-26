# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Tax Invoice PDF Generation Service** (port 8089) — generates PDF/A-3 documents for Thai e-Tax tax-invoices via saga orchestration.

**Tech Stack**: Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4, Apache FOP 2.9, Apache PDFBox 3.0.1, AWS SDK v2 S3, Kafka, PostgreSQL, saga-commons (outbox pattern)

## Build and Run Commands

```bash
# Build dependencies first
cd ../../teda && mvn clean install
cd ../../saga-commons && mvn clean install

# Build this service
mvn clean package

# Run locally (requires MinIO running on localhost:9000)
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export MINIO_BUCKET_NAME=taxinvoices
mvn spring-boot:run

# Run tests
mvn test

# Run single test
mvn test -Dtest=SagaCommandHandlerTest#testHandleProcessCommand_Success

# Run with coverage verification (90% JaCoCo requirement)
mvn verify

# Database migrations
mvn flyway:migrate
mvn flyway:info
```

## Architecture

### Layer Structure (DDD)

```
com.wpanther.taxinvoice.pdf/
├── domain/
│   ├── model/
│   │   ├── TaxInvoicePdfDocument.java         # Aggregate root with state machine + retry tracking
│   │   └── GenerationStatus.java              # PENDING → GENERATING → COMPLETED/FAILED
│   ├── repository/
│   │   └── TaxInvoicePdfDocumentRepository.java
│   ├── service/
│   │   └── TaxInvoicePdfGenerationService.java  # Interface (PDF generation contract)
│   └── event/
│       ├── ProcessTaxInvoicePdfCommand.java       # Saga command from orchestrator
│       ├── CompensateTaxInvoicePdfCommand.java    # Saga compensation command
│       ├── TaxInvoicePdfGeneratedEvent.java       # Output event (via outbox)
│       ├── TaxInvoicePdfReplyEvent.java           # Saga reply (SUCCESS/FAILURE/COMPENSATED)
│       └── XmlSignedTaxInvoiceEvent.java          # LEGACY — no longer consumed, do not use
│
├── application/
│   └── service/
│       ├── TaxInvoicePdfDocumentService.java    # Orchestration, MinIO S3 storage, DB persistence
│       └── SagaCommandHandler.java              # Handles saga commands and compensation
│
└── infrastructure/
    ├── config/
    │   ├── CamelRouteConfig.java                # Apache Camel routes (saga command/compensation consumers)
    │   ├── MinioConfig.java                     # @Bean S3Client for MinIO with forcePathStyle(true)
    │   └── OutboxConfig.java                    # OutboxEventRepository bean registration
    ├── messaging/
    │   ├── EventPublisher.java                  # Outbox-based event publishing
    │   └── SagaReplyPublisher.java              # Outbox-based saga reply publishing
    ├── persistence/
    │   ├── TaxInvoicePdfDocumentEntity.java
    │   ├── JpaTaxInvoicePdfDocumentRepository.java
    │   └── outbox/
    │       ├── OutboxEventEntity.java
    │       ├── SpringDataOutboxRepository.java
    │       └── JpaOutboxEventRepository.java    # saga-commons OutboxEventRepository impl
    └── storage/
        └── MinioStorageAdapter.java             # S3 storage with circuit breaker + Timer metrics
    ├── messaging/
    │   ├── EventPublisher.java                  # Outbox-based event publishing
    │   └── SagaReplyPublisher.java              # Outbox-based saga reply publishing
    ├── persistence/
    │   ├── TaxInvoicePdfDocumentEntity.java
    │   ├── JpaTaxInvoicePdfDocumentRepository.java
    │   └── outbox/
    │       ├── OutboxEventEntity.java
    │       ├── SpringDataOutboxRepository.java
    │       └── JpaOutboxEventRepository.java    # saga-commons OutboxEventRepository impl
    └── pdf/
        ├── FopTaxInvoicePdfGenerator.java       # Apache FOP: XML + XSL-FO → PDF (with Timer metrics)
        ├── PdfA3Converter.java                  # PDFBox: PDF → PDF/A-3 + XML embedding (with Timer metrics)
        └── TaxInvoicePdfGenerationServiceImpl.java  # Orchestrates FOP + PDFBox
```

### Saga-Driven Pipeline (Apache Camel + Outbox)

```
ProcessTaxInvoicePdfCommand (Kafka: saga.command.tax-invoice-pdf)
          ↓
   CamelRouteConfig → SagaCommandHandler.handleProcessCommand()
          ├── Idempotency check (already COMPLETED? re-publish events and return SUCCESS)
          ├── Retry limit check (retryCount >= 3? send FAILURE)
          ├── TaxInvoicePdfDocumentService.generatePdf()
          │   ├── Create domain aggregate (PENDING → GENERATING)
          │   ├── TaxInvoicePdfGenerationServiceImpl.generatePdf()
          │   │   ├── convertJsonToXml() → XML for FOP
          │   │   ├── FopTaxInvoicePdfGenerator → base PDF
          │   │   └── PdfA3Converter → PDF/A-3 with embedded XML
          │   ├── Upload to MinIO (S3Client.putObject, key: YYYY/MM/DD/taxinvoice-{number}-{uuid}.pdf)
          │   └── markCompleted() → COMPLETED
          ├── EventPublisher → outbox_events (pdf.generated.tax-invoice)
          └── SagaReplyPublisher → outbox_events (saga.reply.tax-invoice-pdf)
          ↓
   Debezium CDC reads outbox_events → publishes to Kafka topics
```

### Compensation Flow

```
CompensateTaxInvoicePdfCommand (Kafka: saga.compensation.tax-invoice-pdf)
          ↓
   SagaCommandHandler.handleCompensation()
          ├── Delete PDF from MinIO (S3Client.deleteObject using S3 key stored in documentPath)
          ├── Delete database record
          └── SagaReplyPublisher → COMPENSATED reply via outbox (idempotent if no record found)
```

### Domain Model State Machine

`TaxInvoicePdfDocument` state transitions:
- `PENDING` → `startGeneration()` → `GENERATING`
- `GENERATING` → `markCompleted(path, url, size)` → `COMPLETED`
- Any state → `markFailed(message)` → `FAILED`

State transition methods throw `IllegalStateException` on invalid transitions. `incrementRetryCount()` and `isMaxRetriesExceeded(maxRetries)` support the 3-attempt retry limit.

## Key Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | taxinvoicepdf_db | Database name |
| `DB_USERNAME` | postgres | Database user |
| `DB_PASSWORD` | postgres | Database password |
| `KAFKA_BROKERS` | localhost:9092 | Kafka bootstrap servers |
| `MINIO_ENDPOINT` | http://localhost:9000 | MinIO endpoint URL |
| `MINIO_ACCESS_KEY` | minioadmin | MinIO access key |
| `MINIO_SECRET_KEY` | minioadmin | MinIO secret key |
| `MINIO_BUCKET_NAME` | taxinvoices | MinIO bucket name |
| `MINIO_REGION` | us-east-1 | MinIO region (any value works for local MinIO) |
| `MINIO_BASE_URL` | http://localhost:9000/taxinvoices | Base URL for public PDF URLs (`{base-url}/{s3Key}`) |
| `MINIO_PATH_STYLE_ACCESS` | true | Force path-style access (required for MinIO) |
| `EUREKA_URL` | http://localhost:8761/eureka/ | Service registry URL |
| `PDF_GENERATION_MAX_RETRIES` | 3 | Maximum retry attempts |
| `PDF_MAX_CONCURRENT_RENDERS` | 3 | Max concurrent FOP render jobs (Semaphore permits) |
| `PDF_MAX_SIZE_BYTES` | 52428800 | Max allowed PDF size in bytes (default: 50 MB) |
| `TAXINVOICE_MAX_JSON_SIZE_BYTES` | 1048576 | Max JSON size in bytes (default: 1 MB) |
| `TAXINVOICE_DEFAULT_VAT_RATE` | 7 | Default VAT rate |
| `REST_CLIENT_CONNECT_TIMEOUT` | 5000 | RestTemplate connect timeout (ms) |
| `REST_CLIENT_READ_TIMEOUT` | 10000 | RestTemplate read timeout (ms) |
| `REST_CLIENT_ALLOWED_HOSTS` | localhost | Allowed REST client hosts |
| `FONT_HEALTH_CHECK_ENABLED` | true | Enable Thai font validation at startup |
| `FONT_HEALTH_CHECK_FAIL_ON_ERROR` | true | Fail startup if fonts missing |
| `TRACING_SAMPLING_PROBABILITY` | 1.0 | OpenTelemetry tracing sampling rate |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | http://localhost:4318/v1/traces | OTLP tracing endpoint |

**Storage columns** (repurposed, no migration needed):
- `document_path` — S3 key (e.g., `2024/01/15/taxinvoice-TXINV-001-abc123.pdf`)
- `document_url` — full MinIO URL (`{MINIO_BASE_URL}/{s3Key}`)

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `saga.command.tax-invoice-pdf` | Consume | Process command from Saga Orchestrator |
| `saga.compensation.tax-invoice-pdf` | Consume | Compensation command from Saga Orchestrator |
| `pdf.generated.tax-invoice` | Produce (via outbox) | For Notification Service (header: `documentType=TAX_INVOICE`) |
| `saga.reply.tax-invoice-pdf` | Produce (via outbox) | Reply to Saga Orchestrator |
| `pdf.generation.tax-invoice.dlq` | Produce | Dead letter queue for failed messages |

## Transactional Outbox Pattern

Business logic and event inserts happen in the **same database transaction**. Debezium CDC reads the `outbox_events` table via PostgreSQL logical replication and routes events to Kafka topics based on the `topic` column.

Key: `OutboxService` (from saga-commons) uses `@Transactional(propagation = MANDATORY)` — callers must provide a transaction.

## Event Schemas

### Input: ProcessTaxInvoicePdfCommand
```json
{
  "eventId": "uuid", "occurredAt": "...", "version": 1,
  "sagaId": "uuid", "sagaStep": "GENERATE_TAX_INVOICE_PDF",
  "correlationId": "uuid", "documentId": "uuid",
  "taxInvoiceId": "uuid", "taxInvoiceNumber": "TXINV-2024-001",
  "signedXmlContent": "<TaxInvoice>...</TaxInvoice>",
  "taxInvoiceDataJson": "{...}"
}
```

### Input: taxInvoiceDataJson (JSON-to-XML structure)
```json
{
  "taxInvoiceNumber": "TXINV-001", "taxInvoiceDate": "2024-01-15",
  "seller": { "name": "...", "taxId": "...", "address": "..." },
  "buyer": { "name": "...", "taxId": "...", "address": "..." },
  "lineItems": [{ "description": "...", "quantity": "1", "unitPrice": "100", "amount": "100" }],
  "subtotal": "100", "vatAmount": "7", "grandTotal": "107"
}
```

### Output: TaxInvoicePdfGeneratedEvent (pdf.generated.tax-invoice)
```json
{
  "eventId": "uuid", "eventType": "pdf.generated.tax-invoice", "version": 1,
  "documentId": "uuid", "taxInvoiceId": "uuid", "taxInvoiceNumber": "TXINV-2024-001",
  "documentUrl": "http://localhost:8084/documents/...", "fileSize": 12345,
  "xmlEmbedded": true, "correlationId": "uuid"
}
```

### Output: TaxInvoicePdfReplyEvent (saga.reply.tax-invoice-pdf)
```json
{
  "sagaId": "uuid", "sagaStep": "GENERATE_TAX_INVOICE_PDF",
  "correlationId": "uuid", "status": "SUCCESS|FAILURE|COMPENSATED",
  "errorMessage": null,
  "pdfUrl": "http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-001-abc.pdf",
  "pdfSize": 12345
}
```

`pdfUrl` and `pdfSize` are included only in SUCCESS replies. The orchestrator captures them via `ConcreteSagaReply.getAdditionalData()` and stores them in `DocumentMetadata.metadata` for the subsequent `PDF_STORAGE` step.

## Resources

### Thai Font Setup (CRITICAL — PDF generation fails without fonts)

Fonts are **not included** in the repository. Add to `src/main/resources/fonts/`:
- **TH Sarabun New** (recommended, Thai government standard): https://www.f0nt.com/release/th-sarabun-new/
- **Noto Sans Thai** (alternative): https://fonts.google.com/noto/specimen/Noto+Sans+Thai

The XSL template (`taxinvoice.xsl`) prioritizes THSarabunNew for proper Thai text rendering.

After adding fonts, update `src/main/resources/fop/fop.xconf` if font filenames differ.

### Other Resources
- `resources/xsl/taxinvoice.xsl` — XSL-FO A4 layout: header, seller/buyer, line items, totals, payment info, notes
- `resources/fop/fop.xconf` — FOP font config, PDF/A-3b mode, A4 (210×297mm), 96 dpi. **Note:** No encryption params (PDF/A-3b prohibits encryption)
- `resources/icc/sRGB.icc` — ICC color profile (required for PDF/A-3 compliance)
- `src/test/resources/fop/fop.xconf` — Test-specific config: simplified, no PDF/A mode, auto-detect fonts only

### PDF/A-3 Compliance Requirements (enforced by PdfA3Converter)
1. sRGB ICC color profile embedded
2. XMP metadata with PDF/A-3B Part/Conformance identification
3. Source XML embedded as PDF attachment with `AFRelationship="Source"`

## Database

Two tables managed by Flyway (3 migrations: V1, V2, V3):

**`tax_invoice_pdf_documents`** (V1 + V3 adds `retry_count`):
- `tax_invoice_id` — unique constraint (idempotency key)
- `status` — maps to `GenerationStatus` enum
- `retry_count` — tracks saga retry attempts

**`outbox_events`** (V2):
- Columns: `topic`, `partition_key`, `headers` — used by Debezium EventRouter
- Partial index on `status='PENDING'` for efficient CDC polling

## Metrics

Micrometer metrics for observability:

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `pdf.fop.render` | Timer | — | FOP PDF generation duration (seconds) |
| `pdf.fop.size.bytes` | Distribution Summary | — | Size of generated PDFs in bytes |
| `pdf.fop.render.available_permits` | Gauge | — | Available FOP concurrent render permits |
| `pdf.conversion.pdfa3` | Timer | — | PDF/A-3 conversion duration (seconds) |
| `pdf.minio.store` | Timer | `bucket` | MinIO upload duration (seconds) |
| `pdf.minio.delete` | Timer | `bucket` | MinIO delete duration (seconds) |

Access via `/actuator/metrics` and `/actuator/prometheus`.

## Camel Route Error Handling

Dead Letter Channel: 3 retries with exponential backoff (1s → 2s → 4s, max 10s delay).
Consumer: `autoOffsetReset=earliest`, `autoCommitEnable=false`, `breakOnFirstError=true`.

## Testing

Tests use H2 in-memory database (`application-test.yml`): Flyway disabled, Camel `main-run-controller=false`, Eureka disabled. MinIO S3Client is mocked — `application-test.yml` defines `app.minio.*` test values; `@MockBean S3Client` is used in tests that touch storage.

**114 tests total** (90%+ JaCoCo requirement):
- **SagaCommandHandlerTest** (7 tests): success, idempotency, max retries exceeded, generation failure, compensation success, idempotent compensation, compensation failure
- **CamelRouteConfigTest** (6 tests): JSON serialization/deserialization of all event types
- **FopTaxInvoicePdfGeneratorTest** (13 tests): constructor validation, semaphore behavior, PDF generation (valid/malformed XML), size limits, thread interruption, URI resolution, font availability
- **PdfA3ConverterTest** (5 tests): constructor, null/empty PDF handling, exception constructors
- **MinioStorageAdapterTest** (6 tests): upload, delete, URL resolution, Thai character preservation, filename sanitization
- **TaxInvoicePdfDocumentTest** (9 tests): state machine transitions, invariants, retry counting
- **TaxInvoicePdfDocumentServiceTest** (8 tests): transactional service methods
- **EventPublisherTest**, **SagaReplyPublisherTest** (7 tests): outbox event publishing
- **RestTemplateSignedXmlFetcherTest** (3 tests): REST client with circuit breaker

Tests use a simplified FOP configuration (`src/test/resources/fop/fop.xconf`) that:
- Disables strict validation for faster test execution
- Omits PDF/A mode (production uses PDF/A-3b)
- Uses auto-detected system fonts (no Thai font files required for tests)

No integration tests with embedded Kafka. No REST API — only Spring Actuator (`/actuator/health`, `/actuator/metrics`, `/actuator/camelroutes`).

## Differences from invoice-pdf-generation-service

| Aspect | invoice-pdf-generation-service | taxinvoice-pdf-generation-service |
|--------|--------------------------------|-----------------------------------|
| Port | 8090 | 8089 |
| Package | `com.wpanther.invoice.pdf` | `com.wpanther.taxinvoice.pdf` |
| Database | `invoicepdf_db` | `taxinvoicepdf_db` + `outbox_events` |
| Kafka Consume | `xml.signed.invoice` | `saga.command.tax-invoice-pdf`, `saga.compensation.tax-invoice-pdf` |
| Kafka Produce | `pdf.generated`, `pdf.signing.requested` | via outbox: `pdf.generated.tax-invoice`, `saga.reply.tax-invoice-pdf` |
| Storage Backend | Local filesystem | MinIO (S3-compatible) via AWS SDK v2 `S3Client` |
| XSL Template | `invoice.xsl` (`<invoice>` root) | `taxinvoice.xsl` (`<taxInvoice>` root) |
| Saga Support | No | Yes (commands, compensation, outbox replies) |
