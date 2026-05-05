# Tax Invoice PDF Generation Service

Generates PDF/A-3 documents from signed Thai e-Tax tax-invoice XML. Runs as a saga step — receives commands from the orchestrator, uploads PDFs to MinIO, and replies with SUCCESS/FAILURE/COMPENSATED.

## Quick Start

```bash
# Install dependencies
cd ../../teda && mvn clean install
cd ../../saga-commons && mvn clean install

# Build
mvn clean package

# Run (MinIO must be running on localhost:9000)
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export MINIO_BUCKET_NAME=taxinvoices
mvn spring-boot:run

# Test
mvn clean test
mvn verify   # with JaCoCo coverage (90% requirement)
```

## What This Service Does

```
Saga Orchestrator
       │
       ├── saga.command.tax-invoice-pdf ──→ [Tax Invoice PDF Generation]
       │                                           │
       ├── saga.compensation.tax-invoice-pdf ──→   │
       │                                           │
       │   saga.reply.tax-invoice-pdf ←────────────┤ (via outbox + Debezium CDC)
       │     (pdfUrl + pdfSize on SUCCESS)         │
       │                                           │
       └── Notification Service ←──────────────────┘ pdf.generated.tax-invoice
```

The orchestrator sends `signedXmlUrl`. The service fetches the XML, generates PDF/A-3 with the XML embedded as a PDF attachment, uploads to MinIO, and replies with the MinIO URL and file size. On compensation, it deletes the PDF from MinIO and the record from the database.

## Architecture

```
com.wpanther.taxinvoice.pdf/
├── domain/                      # Framework-independent core
│   ├── model/TaxInvoicePdfDocument   # Aggregate root with state machine
│   ├── model/GenerationStatus       # PENDING → GENERATING → COMPLETED/FAILED
│   ├── repository/                   # Repository interface
│   └── service/                     # TaxInvoicePdfGenerationService interface
│
├── application/
│   ├── port/
│   │   ├── in/                     # Use case interfaces (plain parameters)
│   │   │   ├── ProcessTaxInvoicePdfUseCase.java
│   │   │   └── CompensateTaxInvoicePdfUseCase.java
│   │   └── out/                    # Outbound port interfaces
│   │       ├── PdfEventPort.java        # Publishes TaxInvoicePdfGeneratedEvent
│   │       ├── PdfStoragePort.java      # MinIO operations
│   │       ├── SagaReplyPort.java       # Saga replies (SUCCESS/FAILURE/COMPENSATED)
│   │       └── SignedXmlFetchPort.java  # Fetches signed XML from URL
│   ├── service/
│   │   └── TaxInvoicePdfDocumentService.java  # Orchestration logic
│   └── dto/event/
│       └── TaxInvoicePdfGeneratedEvent.java   # Output event
│
└── infrastructure/
    ├── config/                    # Camel, MinIO, Outbox beans
    └── adapter/
        ├── in/kafka/              # Kafka consumers (Camel routes)
        │   ├── SagaCommandHandler.java          # Implements port interfaces
        │   ├── SagaRouteConfig.java
        │   └── dto/                            # Jackson DTOs for Kafka
        │       ├── ProcessTaxInvoicePdfCommand.java
        │       └── CompensateTaxInvoicePdfCommand.java
        └── out/                   # Outbound adapters
            ├── messaging/          # EventPublisher, SagaReplyPublisher
            ├── persistence/       # JPA entities, outbox entities
            ├── storage/           # MinioStorageAdapter
            └── pdf/               # FopTaxInvoicePdfGenerator, PdfA3Converter
```

**Key design points:**
- `SagaCommandHandler` lives in `infrastructure/adapter/in/kafka/` — it's the inbound Kafka adapter
- Port interfaces in `application/port/in/` use plain parameters (no command objects)
- DTOs in `infrastructure/adapter/in/kafka/dto/` are for Kafka deserialization only
- `TaxInvoicePdfDocumentService` is the orchestration service in `application/service/`

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `saga.command.tax-invoice-pdf` | Consume | Process command from orchestrator |
| `saga.compensation.tax-invoice-pdf` | Consume | Rollback command from orchestrator |
| `saga.reply.tax-invoice-pdf` | Produce (outbox) | SUCCESS/FAILURE/COMPENSATED reply |
| `pdf.generated.tax-invoice` | Produce (outbox) | For Notification Service |
| `pdf.generation.tax-invoice.dlq` | Produce | Dead letter queue |
| `document.archive` | Produce (outbox) | Fire-and-forget archival (UNSIGNED_PDF stored by document-storage-service) |

## Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | taxinvoicepdf_db | Database name |
| `KAFKA_BROKERS` | localhost:9092 | Kafka bootstrap servers |
| `MINIO_ENDPOINT` | http://localhost:9000 | MinIO endpoint |
| `MINIO_ACCESS_KEY` | minioadmin | MinIO access key |
| `MINIO_SECRET_KEY` | minioadmin | MinIO secret key |
| `MINIO_BUCKET_NAME` | taxinvoices | MinIO bucket name |
| `PDF_GENERATION_MAX_RETRIES` | 3 | Max retry attempts per document |
| `PDF_MAX_CONCURRENT_RENDERS` | 3 | Max concurrent FOP render jobs |

Full env var list is in [CLAUDE.md](CLAUDE.md) (gitignored — local settings).

## Domain State Machine

`TaxInvoicePdfDocument` transitions:
- `PENDING` → `startGeneration()` → `GENERATING`
- `GENERATING` → `markCompleted(path, url, size)` → `COMPLETED`
- Any state → `markFailed(message)` → `FAILED`

Invalid transitions throw `IllegalStateException`. Retry count is tracked per document.

## Transactional Outbox

Business logic and event inserts happen in the **same transaction**. Debezium CDC reads `outbox_events` table and publishes to Kafka topics. `OutboxService` (saga-commons) requires an existing transaction — callers must wrap with `@Transactional`.

## PDF Generation Flow

1. **Receive command** — `SagaRouteConfig` unmarshals `ProcessTaxInvoicePdfCommand` from Kafka
2. **Call handler** — `SagaCommandHandler.handle(docId, docNumber, signedXmlUrl, sagaId, sagaStep, correlationId)` with plain parameters
3. **Idempotency check** — if document already COMPLETED, re-publish events and return SUCCESS
4. **Retry check** — if `retryCount >= 3`, publish FAILURE
5. **Fetch XML** — `SignedXmlFetchPort.fetch(signedXmlUrl)` retrieves XML content
6. **Generate PDF** — XPath extract data → Thai amount words → FOP render → PDF/A-3 convert
7. **Store PDF** — `PdfStoragePort.store()` uploads to MinIO (`YYYY/MM/DD/taxinvoice-{number}-{uuid}.pdf`)
8. **Publish events** — `PdfEventPort` and `SagaReplyPort` write to outbox table
9. **Debezium CDC** reads outbox → publishes to `pdf.generated.tax-invoice` and `saga.reply.tax-invoice-pdf`

## Compensation Flow

1. **Receive command** — `SagaRouteConfig` unmarshals `CompensateTaxInvoicePdfCommand`
2. **Call handler** — `SagaCommandHandler.handle(docId, sagaId, sagaStep, correlationId)`
3. **Delete from MinIO** — `PdfStoragePort.delete(s3Key)` (idempotent — no error if not found)
4. **Delete from DB** — `TaxInvoicePdfDocumentService.deleteById()`
5. **Publish COMPENSATED** — `SagaReplyPort.publishCompensated()` via outbox

## Thai Fonts (Required)

Fonts must be in `src/main/resources/fonts/`. Without them, PDF generation fails at runtime.

**TH Sarabun New** (Thai government standard):
- `THSarabunNew.ttf`, `THSarabunNew Bold.ttf`, `THSarabunNew Italic.ttf`, `THSarabunNew BoldItalic.ttf`

**Noto Sans Thai Looped** (fallback):
- `NotoSansThaiLooped-Regular.ttf`, `NotoSansThaiLooped-Bold.ttf`

Font validation runs at startup (`app.fonts.health-check.enabled=true`). Set `app.fonts.health-check.fail-on-error=false` to warn instead of failing.

Update `src/main/resources/fop/fop.xconf` if font filenames differ, and `src/main/resources/xsl/taxinvoice-direct.xsl` font-family stack.

## PDF/A-3 Compliance

The `PdfA3Converter` enforces:
- sRGB ICC color profile embedded
- XMP metadata with PDF/A-3B Part/Conformance identification
- Signed XML embedded as PDF attachment with `AFRelationship="Source"`

## Concurrency Control

`PDF_MAX_CONCURRENT_RENDERS` semaphore (default: 3) prevents OOM. Each render job consumes ~50-200 MB heap. Gauge metric `pdf.fop.render.available_permits` shows available capacity.

## Orphaned PDF Cleanup

Scheduled job deletes MinIO objects with no corresponding database record (service crash recovery).

- **Enable**: `app.minio.cleanup.enabled=true`
- **Schedule**: `app.minio.cleanup.cron` (default: daily 2 AM)
- Uses direct S3Client calls (bypasses circuit breaker)

## Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `pdf.fop.render` | Timer | FOP PDF generation duration |
| `pdf.fop.size.bytes` | Distribution Summary | Generated PDF sizes |
| `pdf.fop.render.available_permits` | Gauge | Available render slots |
| `pdf.conversion.pdfa3` | Timer | PDF/A-3 conversion duration |
| `pdf.minio.store` | Timer | MinIO upload duration |
| `pdf.minio.delete` | Timer | MinIO delete duration |

Access at `/actuator/metrics` and `/actuator/prometheus`.

## Testing

**116 tests** (90%+ JaCoCo coverage requirement):

```bash
mvn clean test
mvn verify   # JaCoCo coverage
mvn test -Dtest=SagaCommandHandlerTest  # specific class
```

Tests use H2 in-memory (`application-test.yml`), mocked S3Client, simplified FOP config (no Thai fonts required).

## Tech Stack

Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4, Apache FOP 2.9, Apache PDFBox 3.0.1, AWS SDK v2 S3, Kafka, PostgreSQL, saga-commons (outbox + Debezium CDC).

## Prerequisites

- Java 21+, Maven 3.6+
- PostgreSQL 16+ with `taxinvoicepdf_db`
- Kafka on `localhost:9092`
- MinIO on `localhost:9000` with bucket `taxinvoices`
- `teda` and `saga-commons` libraries installed

## License

MIT