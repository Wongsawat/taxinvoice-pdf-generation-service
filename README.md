# Tax Invoice PDF Generation Service

A Spring Boot microservice that generates PDF/A-3 documents from signed Thai e-Tax tax-invoice XML documents.

## Features

- **PDF/A-3 Generation**: Apache FOP with XSL-FO templates
- **XML Embedding**: Embeds source XML as PDF attachment
- **Thai Language Support**: Configurable Thai fonts (TH Sarabun New, Noto Sans Thai)
- **Event-Driven**: Kafka-based messaging (consumes `xml.signed.tax-invoice`, produces `pdf.generated.tax-invoice`)
- **State Management**: DDD aggregate with state machine (PENDING → GENERATING → COMPLETED/FAILED)
- **Persistence**: PostgreSQL with Flyway migrations

## Tech Stack

- Java 21
- Spring Boot 3.2.5
- Apache FOP 2.9
- Apache PDFBox 3.0
- Kafka
- PostgreSQL

## Prerequisites

- Java 21+
- Maven 3.6+
- PostgreSQL 16+ with database `taxinvoicepdf_db`
- Kafka on `localhost:9092`
- eidasremotesigning service on `localhost:9000` (for upstream XML signing)

## Build and Run

```bash
# Build
mvn clean package

# Run locally
mvn spring-boot:run

# Run database migrations
mvn flyway:migrate
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_NAME` | taxinvoicepdf_db | Database name |
| `DB_USERNAME` | postgres | Database user |
| `DB_PASSWORD` | postgres | Database password |
| `KAFKA_BROKERS` | localhost:9092 | Kafka servers |
| `PDF_STORAGE_PATH` | /var/documents/taxinvoices | PDF storage directory |
| `PDF_STORAGE_BASE_URL` | http://localhost:8084 | Base URL for document access |
| `EUREKA_URL` | http://localhost:8761/eureka/ | Service registry URL |

## Integration

```
XML Signing Service → xml.signed.tax-invoice → [Tax Invoice PDF Generation] → pdf.generated.tax-invoice → PDF Signing Service
                                                                                →
                                                                          Notification Service
```

## Architecture

```
domain/           # Core business logic (framework-independent)
├── model/        # Aggregate roots, value objects, enums
├── repository/   # Repository interfaces
├── service/      # Domain service interfaces
└── event/        # Integration events (Kafka DTOs)

application/      # Use case orchestration
└── service/      # Application services

infrastructure/   # Framework implementations
├── persistence/  # JPA entities, Spring Data repositories
├── messaging/    # Kafka consumers/producers
└── pdf/          # FOP generators, PDFBox converters
```

## License

MIT License
