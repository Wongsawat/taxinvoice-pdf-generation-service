# Tax Invoice PDF Generation Service

A Spring Boot microservice that generates PDF/A-3 documents from signed Thai e-Tax tax-invoice XML documents, orchestrated via the saga pattern with transactional outbox for reliable event delivery. Generated PDFs are stored in MinIO (S3-compatible object storage).

## Features

- **Saga Orchestration**: Receives commands from saga orchestrator, replies with SUCCESS/FAILURE/COMPENSATED
- **Compensation Support**: Deletes generated PDFs from MinIO and database records on rollback
- **Transactional Outbox**: Events published via outbox table + Debezium CDC for exactly-once delivery
- **PDF/A-3 Generation**: Apache FOP with XSL-FO templates for Thai tax invoice layout
- **XML Embedding**: Embeds signed XML as PDF/A-3 attachment (AFRelationship="Source")
- **Thai Language Support**: TH Sarabun New font family (Regular, Bold, Italic, BoldItalic) included
- **Font Health Check**: Startup validation verifies required Thai fonts are present on classpath
- **Concurrency Control**: Semaphore-based throttling prevents OOM under load (configurable permits)
- **Input Validation**: Null/blank checks, JSON size limits, XML well-formedness validation before FOP
- **PDF Size Protection**: Enforces max PDF size limit to prevent memory exhaustion
- **Comprehensive Metrics**: Micrometer timers, distribution summaries, and gauges for observability
- **Distributed Tracing**: OpenTelemetry span annotation for PDF generation operations
- **MinIO Storage**: Uploads PDFs to MinIO with date-partitioned S3 keys (`YYYY/MM/DD/taxinvoice-{number}-{uuid}.pdf`)
- **Idempotency**: Duplicate commands for already-completed documents re-publish events without regenerating
- **Retry Tracking**: Configurable max retries with per-document retry counting
- **State Management**: DDD aggregate with state machine (PENDING → GENERATING → COMPLETED/FAILED)
- **Persistence**: PostgreSQL with Flyway migrations

## Tech Stack

- Java 21
- Spring Boot 3.2.5
- Apache Camel 4.14.4
- Apache FOP 2.9
- Apache PDFBox 3.0.1
- AWS SDK v2 (S3Client for MinIO)
- Micrometer (Prometheus metrics + OpenTelemetry tracing)
- Kafka
- PostgreSQL
- saga-commons (outbox pattern)

## Prerequisites

- Java 21+
- Maven 3.6+
- PostgreSQL 16+ with database `taxinvoicepdf_db`
- Kafka on `localhost:9092`
- MinIO on `localhost:9000` with bucket `taxinvoices` created
- teda library installed: `cd ../../teda && mvn clean install`
- saga-commons library installed: `cd ../../saga-commons && mvn clean install`

## Build and Run

```bash
# Build dependencies first
cd ../../teda && mvn clean install
cd ../../saga-commons && mvn clean install

# Build
mvn clean package

# Run locally (MinIO required)
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export MINIO_BUCKET_NAME=taxinvoices
mvn spring-boot:run

# Run tests
mvn clean test

# Run with coverage verification (90% JaCoCo requirement)
mvn verify

# Run database migrations
mvn flyway:migrate
```

## Environment Variables

### Database
| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | taxinvoicepdf_db | Database name |
| `DB_USERNAME` | postgres | Database user |
| `DB_PASSWORD` | postgres | Database password |

### Kafka & Service Discovery
| Variable | Default | Description |
|----------|---------|-------------|
| `KAFKA_BROKERS` | localhost:9092 | Kafka bootstrap servers |
| `EUREKA_URL` | http://localhost:8761/eureka/ | Service registry URL |

### MinIO Storage
| Variable | Default | Description |
|----------|---------|-------------|
| `MINIO_ENDPOINT` | http://localhost:9000 | MinIO endpoint URL |
| `MINIO_ACCESS_KEY` | minioadmin | MinIO access key |
| `MINIO_SECRET_KEY` | minioadmin | MinIO secret key |
| `MINIO_BUCKET_NAME` | taxinvoices | MinIO bucket name |
| `MINIO_REGION` | us-east-1 | MinIO region (any value works for local MinIO) |
| `MINIO_BASE_URL` | http://localhost:9000/taxinvoices | Base URL prepended to S3 key to form public PDF URL |
| `MINIO_PATH_STYLE_ACCESS` | true | Force path-style access (required for MinIO) |

### PDF Generation
| Variable | Default | Description |
|----------|---------|-------------|
| `PDF_MAX_CONCURRENT_RENDERS` | 3 | Max concurrent FOP render jobs (each ~50-200 MB heap) |
| `PDF_MAX_SIZE_BYTES` | 52428800 | Max allowed PDF size in bytes (default: 50 MB) |
| `PDF_GENERATION_MAX_RETRIES` | 3 | Maximum saga retry attempts per document |

### Tax Invoice Data
| Variable | Default | Description |
|----------|---------|-------------|
| `TAXINVOICE_MAX_JSON_SIZE_BYTES` | 1048576 | Max size of `taxInvoiceDataJson` in bytes (default: 1 MB) |
| `TAXINVOICE_DEFAULT_VAT_RATE` | 7 | Default VAT rate when not specified in JSON |

### REST Client
| Variable | Default | Description |
|----------|---------|-------------|
| `REST_CLIENT_CONNECT_TIMEOUT` | 5000 | RestTemplate connect timeout (ms) |
| `REST_CLIENT_READ_TIMEOUT` | 30000 | RestTemplate read timeout (ms) |

### Font Health Check
| Variable | Default | Description |
|----------|---------|-------------|
| `FONT_HEALTH_CHECK_ENABLED` | true | Enable Thai font validation at startup |
| `FONT_HEALTH_CHECK_FAIL_ON_ERROR` | true | Fail startup if fonts missing (warn if false) |

## Kafka Topics

| Topic | Direction | Description |
|-------|-----------|-------------|
| `saga.command.tax-invoice-pdf` | Consume | Process command from Saga Orchestrator |
| `saga.compensation.tax-invoice-pdf` | Consume | Compensation command from Saga Orchestrator |
| `pdf.generated.tax-invoice` | Produce (via outbox) | Notification Service |
| `saga.reply.tax-invoice-pdf` | Produce (via outbox) | Reply to Saga Orchestrator |
| `pdf.generation.tax-invoice.dlq` | Produce | Dead letter queue |

## Integration

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

The SUCCESS reply carries `pdfUrl` and `pdfSize` (the MinIO URL and byte count). The orchestrator stores these in `DocumentMetadata` for the subsequent `PDF_STORAGE` step, which downloads the unsigned PDF from MinIO and stores it as `UNSIGNED_PDF`.

## Architecture

```
domain/              # Core business logic (framework-independent)
├── model/           # Aggregate root (TaxInvoicePdfDocument), enums
├── repository/      # Repository interfaces
├── service/         # Domain service interfaces
└── event/           # Saga commands, replies, integration events

application/         # Use case orchestration
└── service/
    ├── SagaCommandHandler          # Handles saga process + compensation commands
    └── TaxInvoicePdfDocumentService # PDF generation, MinIO S3 storage

infrastructure/      # Framework implementations
├── config/          # Camel routes, MinIO S3Client config, outbox config
├── messaging/       # EventPublisher, SagaReplyPublisher (outbox-based)
├── persistence/     # JPA entities, repositories, outbox entities
└── pdf/             # FOP generator, PDF/A-3 converter, XML validation
```

## Database

Flyway migrations create two tables:

- **`tax_invoice_pdf_documents`** — PDF document metadata, state, retry count; `document_path` stores the S3 key; `document_url` stores the full MinIO URL
- **`outbox_events`** — Transactional outbox for Debezium CDC

## Metrics

The service exposes Micrometer metrics for observability:

| Metric | Type | Description |
|--------|------|-------------|
| `pdf.fop.render` | Timer | FOP PDF generation duration (seconds) |
| `pdf.fop.size.bytes` | Distribution Summary | Size of generated PDFs in bytes |
| `pdf.fop.render.available_permits` | Gauge | Available FOP concurrent render permits |

Access via `/actuator/metrics` and `/actuator/prometheus`.

## Distributed Tracing

PDF generation operations are instrumented with OpenTelemetry:

- **Span name**: `pdf.fop.render`
- **Bridge**: Micrometer Tracing → OpenTelemetry
- **Exporter**: OTLP (configure via OpenTelemetry environment variables)

Configure OTLP endpoint:
```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
export OTEL_SERVICE_NAME=taxinvoice-pdf-generation-service
```

## Input Validation

The service validates inputs before PDF generation:

1. **XML Content**: Must not be null or blank
2. **JSON Data**: Must not be null; size limited by `TAXINVOICE_MAX_JSON_SIZE_BYTES`
3. **XML Well-Formedness**: Generated XML is validated via SAXParser before FOP
4. **PDF Size**: Generated PDF size limited by `PDF_MAX_SIZE_BYTES`

Violations throw `TaxInvoicePdfGenerationException` with descriptive messages.

## Thai Fonts

The service includes the **TH Sarabun New** font family:

- `THSarabunNew.ttf` (Regular)
- `THSarabunNew Bold.ttf` (Bold)
- `THSarabunNew Italic.ttf` (Italic)
- `THSarabunNew BoldItalic.ttf` (Bold Italic)

Fonts are validated at startup via the font health check. Add alternative fonts to `src/main/resources/fonts/` and update `src/main/resources/fop/fop.xconf` if needed.

## Concurrency Control

FOP PDF generation is throttled via a fair Semaphore to prevent OOM under load:

- **Permits**: Configurable via `PDF_MAX_CONCURRENT_RENDERS` (default: 3)
- **Fairness**: First-come-first-served queue prevents starvation
- **Metrics**: `pdf.fop.render.available_permits` gauge shows available capacity

Each FOP render job consumes approximately 50-200 MB of heap.

## License

MIT License
