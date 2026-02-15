# Tax Invoice PDF Generation Service

A Spring Boot microservice that generates PDF/A-3 documents from signed Thai e-Tax tax-invoice XML documents, orchestrated via the saga pattern with transactional outbox for reliable event delivery.

## Features

- **Saga Orchestration**: Receives commands from saga orchestrator, replies with SUCCESS/FAILURE/COMPENSATED
- **Compensation Support**: Deletes generated PDFs and database records on rollback
- **Transactional Outbox**: Events published via outbox table + Debezium CDC for exactly-once delivery
- **PDF/A-3 Generation**: Apache FOP with XSL-FO templates for Thai tax invoice layout
- **XML Embedding**: Embeds signed XML as PDF/A-3 attachment (AFRelationship="Source")
- **Thai Language Support**: Configurable Thai fonts (TH Sarabun New, Noto Sans Thai)
- **Idempotency**: Duplicate commands for already-completed documents re-publish events without regenerating
- **Retry Tracking**: Configurable max retries with per-document retry counting
- **State Management**: DDD aggregate with state machine (PENDING → GENERATING → COMPLETED/FAILED)
- **Persistence**: PostgreSQL with Flyway migrations

## Tech Stack

- Java 21
- Spring Boot 3.2.5
- Apache Camel 4.14.4
- Apache FOP 2.9
- Apache PDFBox 3.0
- Kafka
- PostgreSQL
- saga-commons (outbox pattern)

## Prerequisites

- Java 21+
- Maven 3.6+
- PostgreSQL 16+ with database `taxinvoicepdf_db`
- Kafka on `localhost:9092`
- teda library installed: `cd ../../teda && mvn clean install`
- saga-commons library installed: `cd ../../saga-commons && mvn clean install`

## Build and Run

```bash
# Build dependencies first
cd ../../teda && mvn clean install
cd ../../saga-commons && mvn clean install

# Build
mvn clean package

# Run locally
mvn spring-boot:run

# Run tests
mvn test

# Run database migrations
mvn flyway:migrate
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | taxinvoicepdf_db | Database name |
| `DB_USERNAME` | postgres | Database user |
| `DB_PASSWORD` | postgres | Database password |
| `KAFKA_BROKERS` | localhost:9092 | Kafka servers |
| `PDF_STORAGE_PATH` | /var/documents/taxinvoices | PDF storage directory |
| `PDF_STORAGE_BASE_URL` | http://localhost:8084 | Base URL for document access |
| `EUREKA_URL` | http://localhost:8761/eureka/ | Service registry URL |

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
       │                                           │
       └── Notification Service ←──────────────────┘ pdf.generated.tax-invoice
```

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
    └── TaxInvoicePdfDocumentService # PDF generation, filesystem storage

infrastructure/      # Framework implementations
├── config/          # Camel routes, outbox config
├── messaging/       # EventPublisher, SagaReplyPublisher (outbox-based)
├── persistence/     # JPA entities, repositories, outbox entities
└── pdf/             # FOP generator, PDF/A-3 converter
```

## Database

Flyway migrations create two tables:

- **`tax_invoice_pdf_documents`** - PDF document metadata, state, retry count
- **`outbox_events`** - Transactional outbox for Debezium CDC

## License

MIT License
