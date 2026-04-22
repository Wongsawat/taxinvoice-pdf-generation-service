# Tax Invoice PDF Generation — Integration Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create full end-to-end integration tests for `saga.command.tax-invoice-pdf` consumer with real PostgreSQL, Kafka, MinIO, and Debezium CDC containers.

**Architecture:** Follow xml-signing-service's `AbstractFullIntegrationTest` pattern. Tests load the full Spring Boot application context with no mocks, send saga commands via KafkaTemplate, verify DB state via JdbcTemplate, verify MinIO objects via S3Client, and verify Debezium CDC via Kafka consumers.

**Tech Stack:** Spring Boot 3.2.5, Apache Camel 4.14.4, Apache FOP 2.9, AWS SDK v2 S3, Kafka, PostgreSQL, Debezium, Awaitility, JUnit 5

---

## File Structure

```
CREATE  docker/debezium-connectors/taxinvoicepdf-connector.json
MODIFY  docker/scripts/deploy-connectors.sh  (already auto-discovers *.json)
CREATE  src/test/java/.../integration/AbstractFullIntegrationTest.java
CREATE  src/test/java/.../integration/SagaCommandFullIntegrationTest.java
CREATE  src/test/java/.../integration/config/FullIntegrationTestConfiguration.java
CREATE  src/test/java/.../integration/config/TestKafkaProducerConfig.java
CREATE  src/test/java/.../integration/support/TaxInvoicePdfTestHelper.java
CREATE  src/test/resources/application-full-integration-test.yml
CREATE  src/test/resources/samples/tax-invoice-data.json
MODIFY  pom.xml  (add integration profile, PostgreSQL scope, Awaitility)
```

---

### Task 1: Add Debezium connector for taxinvoicepdf_db

**Files:**
- Create: `docker/debezium-connectors/taxinvoicepdf-connector.json`

- [ ] **Step 1: Create the connector JSON file**

```json
{
  "name": "outbox-connector-taxinvoicepdf",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "tasks.max": "1",
    "database.hostname": "test-postgres",
    "database.port": "5432",
    "database.user": "postgres",
    "database.password": "postgres",
    "database.dbname": "taxinvoicepdf_db",
    "database.server.name": "taxinvoicepdf",
    "topic.prefix": "taxinvoicepdf",
    "schema.include.list": "public",
    "table.include.list": "public.outbox_events",
    "plugin.name": "pgoutput",
    "slot.name": "taxinvoicepdf_outbox_slot",
    "publication.name": "taxinvoicepdf_outbox_publication",
    "publication.autocreate.mode": "all_tables",
    "tombstones.on.delete": "false",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "topic",
    "transforms.outbox.route.topic.replacement": "${routedByValue}",
    "transforms.outbox.table.field.event.key": "partition_key",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.fields.additional.placement": "headers:header",
    "transforms.outbox.table.expand.json.payload": "false",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter.schemas.enable": "false"
  }
}
```

- [ ] **Step 2: Verify deploy-connectors.sh auto-discovers the new connector**

The existing `deploy-connectors.sh` iterates `*.json` in `debezium-connectors/`, so no modification needed. Verify:
```bash
ls /home/wpanther/projects/etax/invoice-microservices/docker/debezium-connectors/taxinvoicepdf-connector.json
```

- [ ] **Step 3: Commit**

```bash
git add docker/debezium-connectors/taxinvoicepdf-connector.json
git commit -m "feat: add Debezium outbox connector for taxinvoicepdf_db"
```

---

### Task 2: Update pom.xml for integration tests

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add PostgreSQL driver with compile scope and Awaitility dependency**

In `pom.xml`, change the PostgreSQL dependency scope from `runtime` to `compile` (needed for Testcontainers/Spring context), and add the integration test profile.

Change the PostgreSQL dependency:
```xml
<!-- PostgreSQL Driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>compile</scope>
</dependency>
```

Add Awaitility if not already present (check first — it may be transitive):
```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

Add the integration profile after the `</build>` section (before `</project>`):
```xml
<profiles>
    <profile>
        <id>integration</id>
        <build>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.1.2</version>
                    <configuration>
                        <groups>full-integration</groups>
                    </configuration>
                </plugin>
            </plugins>
        </build>
    </profile>
</profiles>
```

- [ ] **Step 2: Verify build still works**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service
mvn clean compile -q
```

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add integration test profile and PostgreSQL compile scope"
```

---

### Task 3: Create application-full-integration-test.yml

**Files:**
- Create: `src/test/resources/application-full-integration-test.yml`

- [ ] **Step 1: Create the Spring profile configuration**

```yaml
server:
  port: 0

spring:
  application:
    name: taxinvoice-pdf-generation-service-full-integration-test

  main:
    allow-bean-definition-overriding: true

  # PostgreSQL (Docker test container on port 5433)
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5433}/${DB_NAME:taxinvoicepdf_db}
    driver-class-name: org.postgresql.Driver
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    hikari:
      maximum-pool-size: 5
      connection-timeout: 10000

  # JPA with PostgreSQL dialect
  jpa:
    database-platform: org.hibernate.dialect.PostgreSQLDialect
    hibernate:
      ddl-auto: validate
    show-sql: false

  # Flyway enabled (runs real migrations)
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true

  # Kafka (Docker test container on port 9093)
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9093}
    consumer:
      group-id: taxinvoice-pdf-full-integration-test
      auto-offset-reset: earliest
      enable-auto-commit: false

  # Jackson
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      fail-on-unknown-properties: false

# Apache Camel — routes enabled so the service consumes from Kafka
camel:
  springboot:
    name: taxinvoice-pdf-camel-full-integration-test
    main-run-controller: false
  dataformat:
    jackson:
      auto-discover-object-mapper: true

# Disable Eureka for tests
eureka:
  client:
    enabled: false

# Application-specific configuration
app:
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9093}

  # MinIO (Docker test container on port 9100)
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9100}
    access-key: ${MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${MINIO_SECRET_KEY:minioadmin}
    bucket-name: ${MINIO_BUCKET_NAME:taxinvoices}
    region: ${MINIO_REGION:us-east-1}
    base-url: ${MINIO_ENDPOINT:http://localhost:9100}/${MINIO_BUCKET_NAME:taxinvoices}
    path-style-access: true

  # PDF generation settings
  pdf:
    max-retries: 3
    max-concurrent-renders: 3
    max-size-bytes: 52428800

  taxinvoice:
    default-vat-rate: 7
    max-json-size-bytes: 1048576

  # Disable font health check (Thai fonts not installed in test environment)
  font:
    health-check-enabled: false

# Actuator (minimal for tests)
management:
  endpoints:
    enabled-by-default: false
    web:
      exposure:
        include: health

# Logging
logging:
  level:
    root: WARN
    com.wpanther.taxinvoice.pdf: DEBUG
    org.apache.camel: INFO
    org.apache.camel.component.kafka: DEBUG
    org.springframework.kafka: INFO
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/application-full-integration-test.yml
git commit -m "test: add application-full-integration-test.yml profile"
```

---

### Task 4: Create sample test data fixture

**Files:**
- Create: `src/test/resources/samples/tax-invoice-data.json`

- [ ] **Step 1: Create the taxInvoiceDataJson fixture**

```json
{
  "taxInvoiceNumber": "TINV-TEST-001",
  "taxInvoiceDate": "2025-01-15",
  "seller": {
    "name": "Thai Seller Company Ltd.",
    "taxId": "1234567890123",
    "taxIdScheme": "VAT",
    "address": "123 Silom Road, Bangrak",
    "city": "Bangkok",
    "postcode": "10500",
    "country": "TH"
  },
  "buyer": {
    "name": "Thai Buyer Corporation Ltd.",
    "taxId": "9876543210987",
    "taxIdScheme": "VAT",
    "address": "456 Suhumvit Road, Watthana",
    "city": "Bangkok",
    "postcode": "10110",
    "country": "TH"
  },
  "lineItems": [
    {
      "description": "Consulting Services",
      "quantity": "10",
      "unitCode": "HUR",
      "unitPrice": "5000.00",
      "amount": "50000.00",
      "taxRate": "7.00"
    },
    {
      "description": "Software License",
      "quantity": "1",
      "unitCode": "C62",
      "unitPrice": "10000.00",
      "amount": "10000.00",
      "taxRate": "7.00"
    }
  ],
  "subtotal": "60000.00",
  "vatAmount": "4200.00",
  "grandTotal": "64200.00",
  "currency": "THB",
  "paymentTerms": "Net 30 days"
}
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/samples/tax-invoice-data.json
git commit -m "test: add tax-invoice-data.json fixture for integration tests"
```

---

### Task 5: Create TestKafkaProducerConfig

**Files:**
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/integration/config/TestKafkaProducerConfig.java`

- [ ] **Step 1: Create the Kafka producer configuration**

```java
package com.wpanther.taxinvoice.pdf.integration.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@TestConfiguration
public class TestKafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> testProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> testKafkaTemplate() {
        return new KafkaTemplate<>(testProducerFactory());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/pdf/integration/config/TestKafkaProducerConfig.java
git commit -m "test: add TestKafkaProducerConfig for integration tests"
```

---

### Task 6: Create FullIntegrationTestConfiguration

**Files:**
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/integration/config/FullIntegrationTestConfiguration.java`

- [ ] **Step 1: Create the test configuration with JdbcTemplate and MinIO S3Client beans**

```java
package com.wpanther.taxinvoice.pdf.integration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import javax.sql.DataSource;
import java.net.URI;

@TestConfiguration
@Import(TestKafkaProducerConfig.class)
public class FullIntegrationTestConfiguration {

    @Bean("fullIntegrationJdbcTemplate")
    public JdbcTemplate fullIntegrationJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean("minioVerificationS3Client")
    public S3Client minioVerificationS3Client(
            @Value("${app.minio.endpoint}") String endpoint,
            @Value("${app.minio.access-key}") String accessKey,
            @Value("${app.minio.secret-key}") String secretKey,
            @Value("${app.minio.region:us-east-1}") String region) {

        return S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .build();
    }

    public static boolean objectExistsInBucket(S3Client s3Client, String bucketName, String s3Key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/pdf/integration/config/FullIntegrationTestConfiguration.java
git commit -m "test: add FullIntegrationTestConfiguration with MinIO verification S3Client"
```

---

### Task 7: Create TaxInvoicePdfTestHelper

**Files:**
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/integration/support/TaxInvoicePdfTestHelper.java`

- [ ] **Step 1: Create the test helper with fixture loading and command factories**

```java
package com.wpanther.taxinvoice.pdf.integration.support;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TaxInvoicePdfTestHelper {

    private TaxInvoicePdfTestHelper() {}

    public static String loadTaxInvoiceDataJson() throws IOException {
        return Files.readString(Path.of("src/test/resources/samples/tax-invoice-data.json"));
    }

    public static String getMinimalSignedXml() {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <rsm:TaxInvoice_CrossIndustryInvoice
                xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
                xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100">
              <rsm:ExchangedDocumentContext>
                <ram:GuidelineSpecifiedDocumentContextParameter>
                  <ram:ID>urn:etda:uncefact:data:standard:TaxInvoice:2</ram:ID>
                </ram:GuidelineSpecifiedDocumentContextParameter>
              </rsm:ExchangedDocumentContext>
              <rsm:ExchangedDocument>
                <ram:ID>TINV-TEST-001</ram:ID>
                <ram:Name>Tax Invoice</ram:Name>
                <ram:TypeCode>388</ram:TypeCode>
              </rsm:ExchangedDocument>
            </rsm:TaxInvoice_CrossIndustryInvoice>
            """;
    }

    public static KafkaTaxInvoiceProcessCommand createProcessCommand(
            String documentId, String documentNumber,
            String signedXmlUrl, String taxInvoiceDataJson,
            String correlationId) {
        String sagaId = "saga-" + correlationId;
        return new KafkaTaxInvoiceProcessCommand(
                sagaId, SagaStep.GENERATE_TAX_INVOICE_PDF, correlationId,
                documentId, documentNumber, signedXmlUrl, taxInvoiceDataJson);
    }

    public static KafkaTaxInvoiceCompensateCommand createCompensateCommand(
            String documentId, String correlationId) {
        String sagaId = "saga-" + correlationId;
        return new KafkaTaxInvoiceCompensateCommand(
                sagaId, SagaStep.GENERATE_TAX_INVOICE_PDF, correlationId,
                documentId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/pdf/integration/support/TaxInvoicePdfTestHelper.java
git commit -m "test: add TaxInvoicePdfTestHelper with fixture loading and command factories"
```

---

### Task 8: Create AbstractFullIntegrationTest

**Files:**
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/integration/AbstractFullIntegrationTest.java`

- [ ] **Step 1: Create the abstract base test class**

```java
package com.wpanther.taxinvoice.pdf.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;
import com.wpanther.taxinvoice.pdf.integration.config.FullIntegrationTestConfiguration;
import com.wpanther.taxinvoice.pdf.integration.config.TestKafkaProducerConfig;
import com.wpanther.taxinvoice.pdf.integration.support.TaxInvoicePdfTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.wpanther.taxinvoice.pdf.integration.config.FullIntegrationTestConfiguration.objectExistsInBucket;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.bootstrap-servers=${KAFKA_BROKERS:localhost:9093}",
                "KAFKA_BROKERS=localhost:9093"
        }
)
@ActiveProfiles("full-integration-test")
@Import({TestKafkaProducerConfig.class, FullIntegrationTestConfiguration.class})
@Tag("full-integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractFullIntegrationTest {

    @Autowired
    protected KafkaTemplate<String, String> testKafkaTemplate;

    @Autowired
    @Qualifier("fullIntegrationJdbcTemplate")
    protected JdbcTemplate testJdbcTemplate;

    @Autowired
    @Qualifier("minioVerificationS3Client")
    protected S3Client minioVerificationS3Client;

    @Value("${app.minio.bucket-name}")
    protected String minioBucketName;

    protected ObjectMapper objectMapper;

    @BeforeAll
    void setUpObjectMapper() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @BeforeEach
    void cleanDatabase() {
        testJdbcTemplate.execute("DELETE FROM outbox_events");
        testJdbcTemplate.execute("DELETE FROM tax_invoice_pdf_documents");
    }

    // ----- Command factories -----

    protected KafkaTaxInvoiceProcessCommand createProcessCommand(
            String documentId, String documentNumber,
            String signedXmlUrl, String taxInvoiceDataJson,
            String correlationId) {
        return TaxInvoicePdfTestHelper.createProcessCommand(
                documentId, documentNumber, signedXmlUrl, taxInvoiceDataJson, correlationId);
    }

    protected KafkaTaxInvoiceCompensateCommand createCompensateCommand(
            String documentId, String correlationId) {
        return TaxInvoicePdfTestHelper.createCompensateCommand(documentId, correlationId);
    }

    // ----- Kafka helpers -----

    protected void sendEvent(String topic, String key, Object event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            testKafkaTemplate.send(topic, key, json).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send event to topic: " + topic, e);
        }
    }

    // ----- DB query helpers -----

    protected Map<String, Object> getDocumentByTaxInvoiceId(String taxInvoiceId) {
        List<Map<String, Object>> results = testJdbcTemplate.queryForList(
                "SELECT * FROM tax_invoice_pdf_documents WHERE tax_invoice_id = ?", taxInvoiceId);
        return results.isEmpty() ? null : results.get(0);
    }

    protected List<Map<String, Object>> getOutboxEventsByAggregateId(String aggregateId) {
        return testJdbcTemplate.queryForList(
                "SELECT * FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                aggregateId);
    }

    protected List<Map<String, Object>> getAllOutboxEvents() {
        return testJdbcTemplate.queryForList(
                "SELECT * FROM outbox_events ORDER BY created_at");
    }

    // ----- Await helpers -----

    protected Map<String, Object> awaitDocumentStatus(String taxInvoiceId, String expectedStatus) {
        await().atMost(120, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> {
                    Map<String, Object> doc = getDocumentByTaxInvoiceId(taxInvoiceId);
                    return doc != null && expectedStatus.equals(doc.get("status"));
                });
        return getDocumentByTaxInvoiceId(taxInvoiceId);
    }

    protected void awaitOutboxEventCount(String aggregateId, int expectedCount) {
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> getOutboxEventsByAggregateId(aggregateId).size() >= expectedCount);
    }

    protected void awaitDocumentDeleted(String taxInvoiceId) {
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> getDocumentByTaxInvoiceId(taxInvoiceId) == null);
    }

    // ----- MinIO helpers -----

    protected boolean objectExistsInMinIO(String s3Key) {
        return objectExistsInBucket(minioVerificationS3Client, minioBucketName, s3Key);
    }

    protected byte[] downloadFromMinIO(String s3Key) {
        return minioVerificationS3Client.getObjectAsBytes(
                GetObjectRequest.builder()
                        .bucket(minioBucketName)
                        .key(s3Key)
                        .build()
        ).asByteArray();
    }

    protected void awaitObjectInMinIO(String s3Key) {
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> objectExistsInMinIO(s3Key));
    }

    // ----- XML fixture helpers -----

    protected String getMinimalSignedXml() {
        return TaxInvoicePdfTestHelper.getMinimalSignedXml();
    }

    protected String loadTaxInvoiceDataJson() throws java.io.IOException {
        return TaxInvoicePdfTestHelper.loadTaxInvoiceDataJson();
    }

    // ----- ID generators -----

    protected String newDocumentId() {
        return "DOC-" + UUID.randomUUID();
    }

    protected String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    protected String sagaIdFor(String correlationId) {
        return "saga-" + correlationId;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service
mvn compile test-compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/pdf/integration/AbstractFullIntegrationTest.java
git commit -m "test: add AbstractFullIntegrationTest base class"
```

---

### Task 9: Create SagaCommandFullIntegrationTest

**Files:**
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/integration/SagaCommandFullIntegrationTest.java`

- [ ] **Step 1: Create the full integration test class with all 13 test cases**

```java
package com.wpanther.taxinvoice.pdf.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Full Integration: saga.command.tax-invoice-pdf → FOP → PDF/A-3 → MinIO → outbox")
@Tag("full-integration")
@EnabledIfSystemProperty(named = "integration.tests.enabled", matches = "true")
class SagaCommandFullIntegrationTest extends AbstractFullIntegrationTest {

    private static final String COMMAND_TOPIC = "saga.command.tax-invoice-pdf";
    private static final String COMPENSATION_TOPIC = "saga.compensation.tax-invoice-pdf";

    // =========================================================================
    // Happy path
    // =========================================================================

    @Nested
    @DisplayName("PDF generation happy-path")
    class HappyPath {

        @Test
        @DisplayName("Should generate PDF end-to-end, persist to DB and store in MinIO")
        void shouldGeneratePdfEndToEnd() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String documentNumber = "TINV-" + UUID.randomUUID().toString().substring(0, 8);
            String correlationId = newCorrelationId();
            String taxInvoiceDataJson = loadTaxInvoiceDataJson().replace("TINV-TEST-001", documentNumber);
            String signedXmlUrl = "http://localhost:9100/signed-xml-documents/test-signed.xml";

            var command = createProcessCommand(
                    documentId, documentNumber, signedXmlUrl, taxInvoiceDataJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);

            // Assert — DB record reaches COMPLETED
            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");

            assertThat(doc.get("tax_invoice_id")).isEqualTo(documentId);
            assertThat(doc.get("tax_invoice_number")).isEqualTo(documentNumber);
            assertThat(doc.get("status")).isEqualTo("COMPLETED");
            assertThat(doc.get("document_path")).asString().isNotBlank();
            assertThat(doc.get("document_url")).asString().isNotBlank();
            assertThat(doc.get("file_size")).isNotNull();
            assertThat((Long) doc.get("file_size")).isGreaterThan(0L);
            assertThat(doc.get("xml_embedded")).isEqualTo(true);
            assertThat(doc.get("error_message")).isNull();

            // Assert — PDF stored in MinIO
            String s3Key = (String) doc.get("document_path");
            awaitObjectInMinIO(s3Key);
            assertThat(objectExistsInMinIO(s3Key)).isTrue();
        }

        @Test
        @DisplayName("Should store valid PDF bytes in MinIO")
        void shouldVerifyPdfBytesInMinIO() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String documentNumber = "TINV-PDF-001";
            String correlationId = newCorrelationId();
            String taxInvoiceDataJson = loadTaxInvoiceDataJson().replace("TINV-TEST-001", documentNumber);

            var command = createProcessCommand(
                    documentId, documentNumber, null, taxInvoiceDataJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);
            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");

            // Assert — download and verify PDF header
            String s3Key = (String) doc.get("document_path");
            awaitObjectInMinIO(s3Key);

            byte[] pdfBytes = downloadFromMinIO(s3Key);
            String pdfHeader = new String(pdfBytes, 0, Math.min(5, pdfBytes.length), StandardCharsets.US_ASCII);
            assertThat(pdfHeader).as("MinIO object should start with %PDF-").startsWith("%PDF-");
        }

        @Test
        @DisplayName("Should have file size in DB matching actual MinIO object size")
        void shouldVerifyPdfSizeInDbMatchesMinIO() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String documentNumber = "TINV-SIZE-001";
            String correlationId = newCorrelationId();
            String taxInvoiceDataJson = loadTaxInvoiceDataJson().replace("TINV-TEST-001", documentNumber);

            var command = createProcessCommand(
                    documentId, documentNumber, null, taxInvoiceDataJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);
            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");

            // Assert
            long dbSize = (Long) doc.get("file_size");
            String s3Key = (String) doc.get("document_path");
            awaitObjectInMinIO(s3Key);

            byte[] pdfBytes = downloadFromMinIO(s3Key);
            assertThat(dbSize).isEqualTo(pdfBytes.length);
        }

        @Test
        @DisplayName("Should have correct S3 key format: YYYY/MM/DD/taxinvoice-*.pdf")
        void shouldVerifyPdfUrlAndS3KeyFormat() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String documentNumber = "TINV-URL-001";
            String correlationId = newCorrelationId();
            String taxInvoiceDataJson = loadTaxInvoiceDataJson().replace("TINV-TEST-001", documentNumber);

            var command = createProcessCommand(
                    documentId, documentNumber, null, taxInvoiceDataJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);
            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");

            // Assert — S3 key format
            String s3Key = (String) doc.get("document_path");
            assertThat(s3Key).matches("\\d{4}/\\d{2}/\\d{2}/taxinvoice-.*\\.pdf");

            // Assert — URL contains bucket name and s3 key
            String url = (String) doc.get("document_url");
            assertThat(url).contains("taxinvoices");
            assertThat(url).endsWith(s3Key);
        }
    }

    // =========================================================================
    // Outbox events
    // =========================================================================

    @Nested
    @DisplayName("Outbox event correctness")
    class OutboxEventVerification {

        @Test
        @DisplayName("Should write pdf.generated.tax-invoice outbox event with correct fields")
        void shouldWritePdfGeneratedOutboxEvent() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String documentNumber = "TINV-OBX-001";
            String correlationId = newCorrelationId();
            String taxInvoiceDataJson = loadTaxInvoiceDataJson().replace("TINV-TEST-001", documentNumber);

            var command = createProcessCommand(
                    documentId, documentNumber, null, taxInvoiceDataJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);
            awaitDocumentStatus(documentId, "COMPLETED");
            awaitOutboxEventCount(documentId, 1);

            // Assert — pdf.generated.tax-invoice outbox event
            List<Map<String, Object>> events = getOutboxEventsByAggregateId(documentId);
            assertThat(events).hasSize(1);

            Map<String, Object> event = events.get(0);
            assertThat(event.get("topic")).isEqualTo("pdf.generated.tax-invoice");
            assertThat(event.get("aggregate_type")).isEqualTo("TaxInvoicePdfDocument");
            assertThat(event.get("aggregate_id")).isEqualTo(documentId);
            assertThat(event.get("partition_key")).isEqualTo(documentId);

            // Verify payload JSON
            JsonNode payload = objectMapper.readTree((String) event.get("payload"));
            assertThat(payload.get("documentId").asText()).isEqualTo(documentId);
            assertThat(payload.get("documentNumber").asText()).isEqualTo(documentNumber);
            assertThat(payload.get("documentUrl").asText()).isNotBlank();
            assertThat(payload.get("fileSize").asLong()).isGreaterThan(0);
            assertThat(payload.get("xmlEmbedded").asBoolean()).isTrue();
            assertThat(payload.get("correlationId").asText()).isEqualTo(correlationId);
        }

        @Test
        @DisplayName("Should write saga.reply.tax-invoice-pdf outbox event with SUCCESS status")
        void shouldWriteSagaReplySuccess() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);
            String taxInvoiceDataJson = loadTaxInvoiceDataJson();

            var command = createProcessCommand(
                    documentId, "TINV-REPLY-001", null, taxInvoiceDataJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);
            awaitDocumentStatus(documentId, "COMPLETED");
            awaitOutboxEventCount(sagaId, 1);

            // Assert — saga reply
            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(sagaId);
            assertThat(replyEvents).isNotEmpty();

            Map<String, Object> reply = replyEvents.stream()
                    .filter(e -> "saga.reply.tax-invoice-pdf".equals(e.get("topic")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice-pdf event found"));

            assertThat(reply.get("aggregate_id")).isEqualTo(sagaId);

            String replyPayload = (String) reply.get("payload");
            assertThat(replyPayload).contains("SUCCESS");
            assertThat(replyPayload).contains(correlationId);

            // Verify saga reply headers
            String headersJson = (String) reply.get("headers");
            if (headersJson != null) {
                JsonNode headers = objectMapper.readTree(headersJson);
                assertThat(headers.get("sagaId").asText()).isEqualTo(sagaId);
                assertThat(headers.get("correlationId").asText()).isEqualTo(correlationId);
                assertThat(headers.get("status").asText()).isEqualTo("SUCCESS");
            }
        }

        @Test
        @DisplayName("Should write both pdf.generated and saga.reply outbox events atomically")
        void shouldWriteBothOutboxEventsAtomically() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);
            String taxInvoiceDataJson = loadTaxInvoiceDataJson();

            var command = createProcessCommand(
                    documentId, "TINV-ATOMIC-001", null, taxInvoiceDataJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);
            awaitDocumentStatus(documentId, "COMPLETED");

            // Assert — BOTH outbox events exist
            awaitOutboxEventCount(documentId, 1);  // pdf.generated event
            awaitOutboxEventCount(sagaId, 1);      // saga reply event

            List<Map<String, Object>> allEvents = getAllOutboxEvents();
            long pdfGeneratedCount = allEvents.stream()
                    .filter(e -> "pdf.generated.tax-invoice".equals(e.get("topic"))).count();
            long sagaReplyCount = allEvents.stream()
                    .filter(e -> "saga.reply.tax-invoice-pdf".equals(e.get("topic"))).count();

            assertThat(pdfGeneratedCount).isEqualTo(1);
            assertThat(sagaReplyCount).isEqualTo(1);
        }
    }

    // =========================================================================
    // Idempotency
    // =========================================================================

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Should not regenerate PDF when a duplicate command is received for an already-COMPLETED document")
        void shouldNotRegenerateForDuplicateCommand() throws Exception {
            // Arrange — generate PDF first
            String documentId = newDocumentId();
            String correlationId1 = newCorrelationId();
            String taxInvoiceDataJson = loadTaxInvoiceDataJson();

            var firstCommand = createProcessCommand(
                    documentId, "TINV-IDEM-001", null, taxInvoiceDataJson, correlationId1);
            sendEvent(COMMAND_TOPIC, documentId, firstCommand);
            awaitDocumentStatus(documentId, "COMPLETED");

            String firstS3Key = (String) getDocumentByTaxInvoiceId(documentId).get("document_path");

            // Act — send duplicate with different correlationId
            String correlationId2 = newCorrelationId();
            var duplicateCommand = createProcessCommand(
                    documentId, "TINV-IDEM-001", null, taxInvoiceDataJson, correlationId2);
            sendEvent(COMMAND_TOPIC, documentId, duplicateCommand);

            // Allow time for the duplicate to be processed
            Thread.sleep(5_000);

            // Assert — only one DB record exists
            Integer count = testJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tax_invoice_pdf_documents WHERE tax_invoice_id = ?",
                    Integer.class, documentId);
            assertThat(count).isEqualTo(1);

            // S3 key has not changed (no regeneration)
            String currentS3Key = (String) getDocumentByTaxInvoiceId(documentId).get("document_path");
            assertThat(currentS3Key).isEqualTo(firstS3Key);

            // A SUCCESS reply is still sent for the duplicate saga
            String sagaId2 = sagaIdFor(correlationId2);
            awaitOutboxEventCount(sagaId2, 1);
            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(sagaId2);
            assertThat(replyEvents).isNotEmpty();
            assertThat((String) replyEvents.get(0).get("payload")).contains("SUCCESS");
        }
    }

    // =========================================================================
    // Compensation
    // =========================================================================

    @Nested
    @DisplayName("Compensation (saga rollback)")
    class Compensation {

        @Test
        @DisplayName("Should delete PDF from MinIO and DB record on compensation")
        void shouldCompensateByDeletingPdfAndDbRecord() throws Exception {
            // Arrange — generate a PDF first
            String documentId = newDocumentId();
            String processCorrelationId = newCorrelationId();
            String taxInvoiceDataJson = loadTaxInvoiceDataJson();

            var processCommand = createProcessCommand(
                    documentId, "TINV-COMP-001", null, taxInvoiceDataJson, processCorrelationId);
            sendEvent(COMMAND_TOPIC, documentId, processCommand);
            Map<String, Object> doc = awaitDocumentStatus(documentId, "COMPLETED");

            String s3Key = (String) doc.get("document_path");
            awaitObjectInMinIO(s3Key);
            assertThat(objectExistsInMinIO(s3Key)).isTrue();

            // Act — compensate
            String compensateCorrelationId = newCorrelationId();
            var compensateCommand = createCompensateCommand(documentId, compensateCorrelationId);
            sendEvent(COMPENSATION_TOPIC, documentId, compensateCommand);

            // Assert — DB record deleted
            awaitDocumentDeleted(documentId);

            // Assert — COMPENSATED outbox reply written
            String compensateSagaId = sagaIdFor(compensateCorrelationId);
            awaitOutboxEventCount(compensateSagaId, 1);
            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(compensateSagaId);
            assertThat(replyEvents).isNotEmpty();

            Map<String, Object> reply = replyEvents.stream()
                    .filter(e -> "saga.reply.tax-invoice-pdf".equals(e.get("topic")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice-pdf event after compensation"));
            assertThat((String) reply.get("payload")).contains("COMPENSATED");
            assertThat((String) reply.get("payload")).contains(compensateCorrelationId);

            // Assert — MinIO object deleted
            await().atMost(15, TimeUnit.SECONDS)
                    .pollInterval(2, TimeUnit.SECONDS)
                    .until(() -> !objectExistsInMinIO(s3Key));
        }

        @Test
        @DisplayName("Should send COMPENSATED reply even when document was never generated")
        void shouldSendCompensatedReplyForNonExistentDocument() throws Exception {
            // Arrange — no document was ever processed
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String sagaId = sagaIdFor(correlationId);

            var compensateCommand = createCompensateCommand(documentId, correlationId);

            // Act
            sendEvent(COMPENSATION_TOPIC, documentId, compensateCommand);

            // Assert — COMPENSATED reply written despite document not existing
            awaitOutboxEventCount(sagaId, 1);
            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(sagaId);
            assertThat(replyEvents).isNotEmpty();

            Map<String, Object> reply = replyEvents.stream()
                    .filter(e -> "saga.reply.tax-invoice-pdf".equals(e.get("topic")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No saga.reply.tax-invoice-pdf event"));
            assertThat((String) reply.get("payload")).contains("COMPENSATED");
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Should handle invalid taxInvoiceDataJson gracefully")
        void shouldHandleInvalidTaxInvoiceDataJson() throws Exception {
            // Arrange
            String documentId = newDocumentId();
            String correlationId = newCorrelationId();
            String invalidJson = "{ this is not valid JSON }";

            var command = createProcessCommand(
                    documentId, "TINV-INVALID-001", null, invalidJson, correlationId);

            // Act
            sendEvent(COMMAND_TOPIC, documentId, command);

            // Assert — should reach FAILED status
            Map<String, Object> doc = awaitDocumentStatus(documentId, "FAILED");
            assertThat(doc.get("tax_invoice_id")).isEqualTo(documentId);
            assertThat(doc.get("error_message")).asString().isNotBlank();

            // Assert — FAILURE saga reply
            String sagaId = sagaIdFor(correlationId);
            awaitOutboxEventCount(sagaId, 1);
            List<Map<String, Object>> replyEvents = getOutboxEventsByAggregateId(sagaId);
            assertThat(replyEvents).isNotEmpty();
            assertThat((String) replyEvents.get(0).get("payload")).contains("FAILURE");
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service
mvn test-compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/pdf/integration/SagaCommandFullIntegrationTest.java
git commit -m "test: add SagaCommandFullIntegrationTest with 13 integration test cases"
```

---

### Task 10: Verify clean build and run integration tests

**Files:** None (verification only)

- [ ] **Step 1: Clean build**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service
mvn clean package -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Start test containers (if not running)**

```bash
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-start.sh --with-debezium --auto-deploy-connectors
```

- [ ] **Step 3: Clean database and Kafka topics**

```bash
cd /home/wpanther/projects/etax/invoice-microservices
./scripts/test-containers-clean.sh
```

- [ ] **Step 4: Run integration tests**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service
mvn test -Pintegration -Dintegration.tests.enabled=true \
    -Dtest="SagaCommandFullIntegrationTest"
```

Expected: All 13 tests pass. If the Debezium CDC tests (8-9 from spec) fail because the new connector wasn't deployed, deploy it manually:

```bash
curl -X POST http://localhost:8083/connectors \
    -H "Content-Type: application/json" \
    -d @/home/wpanther/projects/etax/invoice-microservices/docker/debezium-connectors/taxinvoicepdf-connector.json
```

Then re-run the tests.

- [ ] **Step 5: Commit all remaining changes if any**

```bash
git add -A
git commit -m "test: complete taxinvoice-pdf integration test suite"
```
