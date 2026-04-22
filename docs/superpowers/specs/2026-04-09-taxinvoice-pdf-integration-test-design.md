# Integration Tests: saga.command.tax-invoice-pdf Consumer

**Date**: 2026-04-09
**Service**: taxinvoice-pdf-generation-service (port 8089)
**Status**: Approved

## Goal

Create full end-to-end integration tests that exercise the `saga.command.tax-invoice-pdf` Kafka consumer with no mocks — real PostgreSQL, Kafka, MinIO, and Debezium CDC containers.

## Required Containers

Start with:
```bash
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
```

| Container | Port | Purpose |
|-----------|------|---------|
| PostgreSQL | 5433 | `taxinvoicepdf_db` — Flyway migrations, domain tables, outbox_events |
| Kafka | 9093 | Produce commands, consume CDC replies |
| MinIO | 9100 | Verify PDF upload/download (`taxinvoices` bucket) |
| Debezium | 8083 | CDC: outbox_events → Kafka topics |

**No eidasremotesigning needed** — PDF generation does not call the CSC API (that is pdf-signing-service's responsibility).

## New Debezium Connector Required

No connector exists for `taxinvoicepdf_db`. A new connector file is needed:

- `docker/debezium-connectors/outbox-connector-taxinvoicepdf.json`
- `docker/scripts/deploy-connectors.sh` updated to include it

Connector follows the existing pattern:
- Slot name: `taxinvoicepdf_outbox_slot`
- Publication: `taxinvoicepdf_outbox_publication`
- Database: `taxinvoicepdf_db`
- Server name / topic prefix: `taxinvoicepdf`

## File Structure

```
src/test/java/com/wpanther/taxinvoice/pdf/integration/
├── AbstractFullIntegrationTest.java           # Base class: Kafka, JdbcTemplate, MinIO S3Client, ObjectMapper
├── SagaCommandFullIntegrationTest.java        # Test cases (nested @Nested classes)
├── config/
│   ├── FullIntegrationTestConfiguration.java  # @TestConfiguration: JdbcTemplate, MinIO S3Client beans
│   └── TestKafkaProducerConfig.java           # KafkaTemplate<String, String> bean
└── support/
    └── TaxInvoicePdfTestHelper.java           # JSON fixture builder for taxInvoiceDataJson

src/test/resources/
├── application-full-integration-test.yml      # Spring profile: real containers, Camel enabled, Flyway enabled
└── samples/
    └── tax-invoice-data.json                  # Sample taxInvoiceDataJson fixture

docker/debezium-connectors/
└── outbox-connector-taxinvoicepdf.json        # NEW Debezium connector
```

## Configuration Profile

`application-full-integration-test.yml` mirrors the xml-signing-service pattern:

- PostgreSQL on `localhost:5433`, database `taxinvoicepdf_db`
- Kafka on `localhost:9093`
- MinIO on `localhost:9100`, bucket `taxinvoices`
- Camel routes **enabled** (`main-run-controller: false`)
- Flyway **enabled** with `baseline-on-migrate: true`
- Eureka **disabled**
- Thai font health check disabled (fonts not required for integration tests)
- Port 0 (random)

## Test Cases

### Happy Path (4 tests)

| # | Test | Verification |
|---|------|-------------|
| 1 | `shouldGeneratePdfEndToEnd` | Command → FOP → PDF/A-3 → MinIO upload → DB COMPLETED status, non-null documentPath/documentUrl/fileSize |
| 2 | `shouldVerifyPdfBytesInMinIO` | Download PDF from MinIO, verify starts with `%PDF-` |
| 3 | `shouldVerifyPdfSizeInDbMatchesMinIO` | DB file_size matches actual MinIO object byte count |
| 4 | `shouldVerifyPdfUrlAndS3KeyFormat` | URL = `{base-url}/{s3Key}`, S3 key matches `YYYY/MM/DD/taxinvoice-*.pdf` |

### Outbox Events (3 tests)

| # | Test | Verification |
|---|------|-------------|
| 5 | `shouldWritePdfGeneratedOutboxEvent` | `pdf.generated.tax-invoice` event with documentId, documentNumber, documentUrl, xmlEmbedded=true |
| 6 | `shouldWriteSagaReplySuccessWithPdfUrl` | `saga.reply.tax-invoice-pdf` with SUCCESS, pdfUrl, pdfSize |
| 7 | `shouldWriteBothOutboxEventsAtomically` | Both pdf.generated and saga.reply events exist after COMPLETED |

### Debezium CDC Verification (2 tests)

| # | Test | Verification |
|---|------|-------------|
| 8 | `shouldPublishPdfGeneratedEventViaDebezium` | Consume `pdf.generated.tax-invoice` from Kafka (Debezium published it) |
| 9 | `shouldPublishSagaReplyViaDebezium` | Consume `saga.reply.tax-invoice-pdf` from Kafka |

### Idempotency (1 test)

| # | Test | Verification |
|---|------|-------------|
| 10 | `shouldNotRegenerateForDuplicateCommand` | Second command for same documentId → one DB record, one MinIO object, SUCCESS reply |

### Compensation (2 tests)

| # | Test | Verification |
|---|------|-------------|
| 11 | `shouldCompensateByDeletingPdfAndDbRecord` | MinIO object deleted, DB record deleted, COMPENSATED reply |
| 12 | `shouldSendCompensatedReplyForNonExistentDocument` | Idempotent: COMPENSATED reply even when no record exists |

### Error Handling (1 test)

| # | Test | Verification |
|---|------|-------------|
| 13 | `shouldHandleInvalidTaxInvoiceDataJson` | Malformed JSON → FAILED status, FAILURE saga reply |

## Test Data

Tests use a `taxInvoiceDataJson` fixture (JSON, not XML) with:
- Thai seller/buyer with tax IDs
- 2 line items with quantities and prices
- Subtotal, VAT 7%, grand total

The `signedXmlContent` field contains a minimal valid TaxInvoice XML (for PDF/A-3 embedding).

## Execution

```bash
# 1. Start containers
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors

# 2. Clean state
./scripts/test-containers-clean.sh

# 3. Clean build (required before integration tests)
cd services/taxinvoice-pdf-generation-service
mvn clean package -DskipTests

# 4. Run integration tests
mvn test -Pintegration -Dtest="SagaCommandFullIntegrationTest" \
    -Dintegration.tests.enabled=true
```

## Design Decisions

1. **Follow xml-signing-service pattern** — `AbstractFullIntegrationTest` with `@SpringBootTest` loading full application context, real containers, no mocks. Consistent with existing integration test architecture.

2. **No eidasremotesigning** — PDF generation does not perform digital signatures. Only PostgreSQL + Kafka + MinIO + Debezium required.

3. **New Debezium connector** — `outbox-connector-taxinvoicepdf` must be created and added to `deploy-connectors.sh`. Without it, Debezium CDC tests (8-9) won't work.

4. **Font health check disabled** — Integration tests don't need Thai fonts installed. FOP will use fallback fonts; the generated PDF won't have perfect Thai rendering but will be structurally valid for testing.

5. **POM integration profile** — Add `<profile><id>integration</id>` to surefire config, gated by `integration.tests.enabled=true` system property. Same pattern as invoice-processing-service and xml-signing-service.

## Dependencies to Add

In `pom.xml`, ensure PostgreSQL driver has `compile` scope (not `runtime`) for Testcontainers detection, and add dependencies for:
- `software.amazon.awssdk:s3` (already present)
- `org.awaitility:awaitility` (already present in test scope)
