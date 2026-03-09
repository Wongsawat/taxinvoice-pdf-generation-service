# Hexagonal Architecture Migration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Migrate taxinvoice-pdf-generation-service to full canonical Hexagonal Architecture with strict port/adapter pattern, enforcing the dependency rule end-to-end.

**Architecture:** Domain is pure Java with zero framework imports. Application owns inbound use-case ports (`application/usecase/`) and non-domain outbound ports (`application/port/out/`). Domain retains its repository port (`domain/repository/`). Infrastructure splits into `adapter/in/kafka/` (Camel routes) and `adapter/out/` sub-packages (persistence, messaging, storage, client, pdf). The existing `SagaCommandHandler` and `TaxInvoicePdfDocumentService` are significantly refactored: MinIO and HTTP logic are extracted into new adapters, and the broken long-running `@Transactional` is replaced with the short-TX pattern (TX1 → no-TX → TX2).

**Tech Stack:** Java 21, Spring Boot 3.2.5, Apache Camel 4.14.4, Apache FOP 2.9, PDFBox 3.0.1, Kafka, PostgreSQL, MinIO (AWS SDK v2), saga-commons, Resilience4j, Lombok

**Design doc:** `docs/plans/2026-03-09-hexagonal-architecture-migration-design.md`

---

## Important: Current vs. Target Transaction Design

**Current (broken):** `TaxInvoicePdfDocumentService.generatePdf()` is one large `@Transactional` method that creates DB records, runs FOP+PDFBox, AND uploads to MinIO — all holding a single DB connection. This will exhaust the Hikari pool under any load.

**Target (correct):**
- **TX 1** (~10 ms): create PENDING→GENERATING record
- **No TX** (~1–3 s): download XML, generate PDF bytes, upload to MinIO
- **TX 2** (~100 ms): mark COMPLETED + write outbox rows atomically

This is a logic change, not just a package move. It is the most important part of this migration.

---

## Phase 1 — Domain Cleanup

### Task 1: Add `TaxInvoicePdfGenerationException`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/domain/exception/TaxInvoicePdfGenerationException.java`
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/domain/exception/TaxInvoicePdfGenerationExceptionTest.java`

**Step 1: Write the failing test**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/domain/exception/TaxInvoicePdfGenerationExceptionTest.java
package com.wpanther.taxinvoice.pdf.domain.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaxInvoicePdfGenerationExceptionTest {

    @Test
    void constructor_withMessage_storesMessage() {
        var ex = new TaxInvoicePdfGenerationException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
    }

    @Test
    void constructor_withMessageAndCause_storesBoth() {
        var cause = new RuntimeException("root");
        var ex = new TaxInvoicePdfGenerationException("wrapped", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isRuntimeException() {
        assertThat(new TaxInvoicePdfGenerationException("x"))
                .isInstanceOf(RuntimeException.class);
    }
}
```

**Step 2: Run to verify it fails**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service
mvn test -Dtest=TaxInvoicePdfGenerationExceptionTest -q 2>&1 | tail -5
```
Expected: FAIL — class not found.

**Step 3: Implement**

```java
// src/main/java/com/wpanther/taxinvoice/pdf/domain/exception/TaxInvoicePdfGenerationException.java
package com.wpanther.taxinvoice.pdf.domain.exception;

public class TaxInvoicePdfGenerationException extends RuntimeException {

    public TaxInvoicePdfGenerationException(String message) {
        super(message);
    }

    public TaxInvoicePdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

**Step 4: Run to verify it passes**

```bash
mvn test -Dtest=TaxInvoicePdfGenerationExceptionTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/domain/exception/ \
        src/test/java/com/wpanther/taxinvoice/pdf/domain/exception/
git commit -m "feat: add TaxInvoicePdfGenerationException to domain/exception"
```

---

### Task 2: Replace `IllegalStateException` in `TaxInvoicePdfDocument` with `TaxInvoicePdfGenerationException`

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/domain/model/TaxInvoicePdfDocument.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/domain/model/TaxInvoicePdfDocumentTest.java`

**Step 1: Update test to expect `TaxInvoicePdfGenerationException`**

Open `TaxInvoicePdfDocumentTest.java`. Change every `assertThrows(IllegalStateException.class, ...)` to `assertThrows(TaxInvoicePdfGenerationException.class, ...)`. Add import:
```java
import com.wpanther.taxinvoice.pdf.domain.exception.TaxInvoicePdfGenerationException;
```

**Step 2: Run to verify tests now fail**

```bash
mvn test -Dtest=TaxInvoicePdfDocumentTest -q 2>&1 | tail -10
```
Expected: FAIL.

**Step 3: Update `TaxInvoicePdfDocument`**

Add import:
```java
import com.wpanther.taxinvoice.pdf.domain.exception.TaxInvoicePdfGenerationException;
```

Replace every `throw new IllegalStateException(` with `throw new TaxInvoicePdfGenerationException(` in these four methods:
- `validateInvariant()` — two throws (blank taxInvoiceId, blank taxInvoiceNumber)
- `startGeneration()` — one throw
- `markCompleted()` — one throw

**Step 4: Run to verify**

```bash
mvn test -Dtest=TaxInvoicePdfDocumentTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/domain/model/TaxInvoicePdfDocument.java \
        src/test/java/com/wpanther/taxinvoice/pdf/domain/model/TaxInvoicePdfDocumentTest.java
git commit -m "refactor: use TaxInvoicePdfGenerationException in domain aggregate"
```

---

## Phase 2 — Application Output Ports

### Task 3: Create all 4 output ports in `application/port/out/`

These interfaces are pure Java — no test needed, just compile verification.

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/PdfStoragePort.java`
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/SagaReplyPort.java`
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/PdfEventPort.java`
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/SignedXmlFetchPort.java`

**Step 1: Create `PdfStoragePort`**

```java
// src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/PdfStoragePort.java
package com.wpanther.taxinvoice.pdf.application.port.out;

public interface PdfStoragePort {
    /** Upload PDF bytes and return the S3 key. */
    String store(String taxInvoiceNumber, byte[] pdfBytes);
    /** Delete a stored PDF by S3 key (best-effort). */
    void delete(String s3Key);
    /** Resolve a full URL from an S3 key. */
    String resolveUrl(String s3Key);
}
```

**Step 2: Create `SagaReplyPort`**

```java
// src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/SagaReplyPort.java
package com.wpanther.taxinvoice.pdf.application.port.out;

import com.wpanther.saga.domain.enums.SagaStep;

public interface SagaReplyPort {
    void publishSuccess(String sagaId, SagaStep sagaStep, String correlationId,
                        String pdfUrl, long pdfSize);
    void publishFailure(String sagaId, SagaStep sagaStep, String correlationId,
                        String errorMessage);
    void publishCompensated(String sagaId, SagaStep sagaStep, String correlationId);
}
```

**Step 3: Create `PdfEventPort`**

```java
// src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/PdfEventPort.java
package com.wpanther.taxinvoice.pdf.application.port.out;

import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;

public interface PdfEventPort {
    void publishPdfGenerated(TaxInvoicePdfGeneratedEvent event);
}
```

> **Note:** `TaxInvoicePdfGeneratedEvent` is still in `domain/event/` at this point — it will move to `adapter/out/messaging/` in Phase 5. The import is temporary.

**Step 4: Create `SignedXmlFetchPort`**

```java
// src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/SignedXmlFetchPort.java
package com.wpanther.taxinvoice.pdf.application.port.out;

public interface SignedXmlFetchPort {
    /** Fetch signed XML content from the given URL. */
    String fetch(String signedXmlUrl);
}
```

**Step 5: Compile to verify**

```bash
mvn compile -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 6: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/application/port/out/
git commit -m "feat: add output port interfaces to application/port/out"
```

---

## Phase 3 — Create New Outbound Adapters

### Task 4: Create `MinioStorageAdapter` (extracts from `TaxInvoicePdfDocumentService`)

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/storage/MinioStorageAdapter.java`
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/storage/MinioStorageAdapterTest.java`

**Step 1: Write failing test**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/storage/MinioStorageAdapterTest.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.storage;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MinioStorageAdapterTest {

    @Mock
    private S3Client s3Client;

    private MinioStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        adapter = new MinioStorageAdapter(s3Client, "test-bucket", "http://localhost:9000/test-bucket", registry);
    }

    @Test
    void store_uploadsAndReturnsS3Key() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        String key = adapter.store("TINV-001", new byte[]{1, 2, 3});

        assertThat(key).matches("\\d{4}/\\d{2}/\\d{2}/taxinvoice-TINV-001-.+\\.pdf");
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void resolveUrl_prependsBaseUrl() {
        String url = adapter.resolveUrl("2024/01/15/file.pdf");
        assertThat(url).isEqualTo("http://localhost:9000/test-bucket/2024/01/15/file.pdf");
    }

    @Test
    void delete_callsS3DeleteObject() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        assertThatNoException().isThrownBy(() -> adapter.delete("2024/01/15/file.pdf"));
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void delete_s3Failure_throwsException() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 error"));

        assertThatThrownBy(() -> adapter.delete("bad-key"))
                .isInstanceOf(RuntimeException.class);
    }
}
```

**Step 2: Run to verify it fails**

```bash
mvn test -Dtest=MinioStorageAdapterTest -q 2>&1 | tail -5
```
Expected: FAIL — class not found.

**Step 3: Implement `MinioStorageAdapter`**

```java
// src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/storage/MinioStorageAdapter.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.storage;

import com.wpanther.taxinvoice.pdf.application.port.out.PdfStoragePort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.util.UUID;

@Component
@Slf4j
public class MinioStorageAdapter implements PdfStoragePort {

    private final S3Client s3Client;
    private final String bucketName;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public MinioStorageAdapter(
            S3Client s3Client,
            @Value("${app.minio.bucket-name}") String bucketName,
            @Value("${app.minio.base-url}") String baseUrl,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.baseUrl = baseUrl;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("minio");
    }

    @Override
    public String store(String taxInvoiceNumber, byte[] pdfBytes) {
        return CircuitBreaker.decorateSupplier(circuitBreaker, () -> doStore(taxInvoiceNumber, pdfBytes)).get();
    }

    @Override
    public void delete(String s3Key) {
        CircuitBreaker.decorateRunnable(circuitBreaker, () -> doDelete(s3Key)).run();
    }

    @Override
    public String resolveUrl(String s3Key) {
        return baseUrl + "/" + s3Key;
    }

    private String doStore(String taxInvoiceNumber, byte[] pdfBytes) {
        LocalDate now = LocalDate.now();
        String safeName = taxInvoiceNumber.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        String fileName = String.format("taxinvoice-%s-%s.pdf", safeName, UUID.randomUUID());
        String s3Key = String.format("%04d/%02d/%02d/%s",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), fileName);

        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType("application/pdf")
                .contentLength((long) pdfBytes.length)
                .build();

        s3Client.putObject(put, RequestBody.fromBytes(pdfBytes));
        log.debug("Uploaded PDF to MinIO: bucket={}, key={}", bucketName, s3Key);
        return s3Key;
    }

    private void doDelete(String s3Key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build());
        log.info("Deleted PDF from MinIO: bucket={}, key={}", bucketName, s3Key);
    }
}
```

**Step 4: Run to verify**

```bash
mvn test -Dtest=MinioStorageAdapterTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/storage/ \
        src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/storage/
git commit -m "feat: add MinioStorageAdapter implementing PdfStoragePort"
```

---

### Task 5: Create `RestTemplateSignedXmlFetcher` (extracts from `SagaCommandHandler`)

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/client/RestTemplateSignedXmlFetcher.java`
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/client/RestTemplateSignedXmlFetcherTest.java`

**Step 1: Write failing test**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/client/RestTemplateSignedXmlFetcherTest.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RestTemplateSignedXmlFetcherTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private RestTemplateSignedXmlFetcher fetcher;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        fetcher = new RestTemplateSignedXmlFetcher(restTemplate);
    }

    @Test
    void fetch_success_returnsXml() {
        mockServer.expect(requestTo("http://minio/xml"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("<invoice/>", MediaType.APPLICATION_XML));

        String result = fetcher.fetch("http://minio/xml");

        assertThat(result).isEqualTo("<invoice/>");
        mockServer.verify();
    }

    @Test
    void fetch_emptyResponse_throwsException() {
        mockServer.expect(requestTo("http://minio/empty"))
                .andRespond(withSuccess("", MediaType.APPLICATION_XML));

        assertThatThrownBy(() -> fetcher.fetch("http://minio/empty"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void fetch_serverError_throwsException() {
        mockServer.expect(requestTo("http://minio/error"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> fetcher.fetch("http://minio/error"))
                .isInstanceOf(Exception.class);
    }
}
```

**Step 2: Run to verify it fails**

```bash
mvn test -Dtest=RestTemplateSignedXmlFetcherTest -q 2>&1 | tail -5
```
Expected: FAIL.

**Step 3: Implement**

```java
// src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/client/RestTemplateSignedXmlFetcher.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.client;

import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class RestTemplateSignedXmlFetcher implements SignedXmlFetchPort {

    private final RestTemplate restTemplate;

    @Override
    public String fetch(String signedXmlUrl) {
        log.debug("Fetching signed XML from {}", signedXmlUrl);
        String xml = restTemplate.getForObject(signedXmlUrl, String.class);
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException(
                    "Received null or empty signed XML response from: " + signedXmlUrl);
        }
        return xml;
    }
}
```

**Step 4: Run to verify**

```bash
mvn test -Dtest=RestTemplateSignedXmlFetcherTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/client/ \
        src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/client/
git commit -m "feat: add RestTemplateSignedXmlFetcher implementing SignedXmlFetchPort"
```

---

## Phase 4 — Wire Ports into Application Services

### Task 6: Update `EventPublisher` and `SagaReplyPublisher` to implement ports

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/messaging/EventPublisher.java`
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/messaging/SagaReplyPublisher.java`

**Step 1: Update `EventPublisher` to implement `PdfEventPort`**

Change class declaration from:
```java
public class EventPublisher {
```
to:
```java
public class EventPublisher implements PdfEventPort {
```
Add import:
```java
import com.wpanther.taxinvoice.pdf.application.port.out.PdfEventPort;
```
Add `@Override` to `publishPdfGenerated()`.

**Step 2: Update `SagaReplyPublisher` to implement `SagaReplyPort`**

Change class declaration from:
```java
public class SagaReplyPublisher {
```
to:
```java
public class SagaReplyPublisher implements SagaReplyPort {
```
Add import:
```java
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
```
Add `@Override` to all three `publish*` methods.

Note: `SagaReplyPublisher.publishSuccess` currently takes `Long pdfSize` (boxed) — change to `long pdfSize` (primitive) to match the port interface.

**Step 3: Compile to verify**

```bash
mvn compile -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 4: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/messaging/EventPublisher.java \
        src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/messaging/SagaReplyPublisher.java
git commit -m "refactor: EventPublisher implements PdfEventPort, SagaReplyPublisher implements SagaReplyPort"
```

---

### Task 7: Refactor `TaxInvoicePdfDocumentService` — short-TX pattern + inject ports

**Current problem:** `generatePdf()` holds a DB transaction open while running FOP+PDFBox and uploading to MinIO. This exhausts the Hikari pool.

**Target:** Short focused `@Transactional` methods only. No S3Client injection. No PDF generation in this class.

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentService.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentServiceTest.java`

**Step 1: Write failing tests for new methods**

Open `TaxInvoicePdfDocumentServiceTest.java` and add these tests (keep all existing tests):

```java
// Add to TaxInvoicePdfDocumentServiceTest.java

@Test
void beginGeneration_savesPendingToGenerating() {
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    TaxInvoicePdfDocument result = service.beginGeneration("tx-1", "TINV-001");

    assertThat(result.getStatus()).isEqualTo(GenerationStatus.GENERATING);
    assertThat(result.getTaxInvoiceId()).isEqualTo("tx-1");
    verify(repository).save(any());
}

@Test
void completeGenerationAndPublish_marksCompletedAndPublishesBothEvents() {
    UUID docId = UUID.randomUUID();
    TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
            .id(docId).taxInvoiceId("tx-1").taxInvoiceNumber("TINV-001").build();
    doc.startGeneration();
    when(repository.findById(docId)).thenReturn(Optional.of(doc));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ProcessTaxInvoicePdfCommand cmd = new ProcessTaxInvoicePdfCommand(
            "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
            "doc-1", "tx-1", "TINV-001", "http://url", "{}");

    service.completeGenerationAndPublish(docId, "s3key", "http://url/file.pdf", 1234L, 0, cmd);

    assertThat(doc.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
    verify(pdfEventPort).publishPdfGenerated(any());
    verify(sagaReplyPort).publishSuccess(eq("saga-1"), any(), eq("corr-1"), eq("http://url/file.pdf"), eq(1234L));
}
```

Update the class fields in the test to inject the ports:
```java
@Mock private TaxInvoicePdfDocumentRepository repository;
@Mock private PdfEventPort pdfEventPort;
@Mock private SagaReplyPort sagaReplyPort;
@InjectMocks private TaxInvoicePdfDocumentService service;
```

**Step 2: Run to verify tests fail**

```bash
mvn test -Dtest=TaxInvoicePdfDocumentServiceTest -q 2>&1 | tail -10
```
Expected: FAIL — methods don't exist yet.

**Step 3: Rewrite `TaxInvoicePdfDocumentService`**

Replace the entire file content with:

```java
package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.application.port.out.PdfEventPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for TaxInvoicePdfDocument lifecycle.
 *
 * Each method is a short, focused @Transactional unit — no CPU or network I/O inside any transaction.
 * TX1: beginGeneration() / replaceAndBeginGeneration()
 * TX2: completeGenerationAndPublish() / failGenerationAndPublish()
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaxInvoicePdfDocumentService {

    private final TaxInvoicePdfDocumentRepository repository;
    private final PdfEventPort pdfEventPort;
    private final SagaReplyPort sagaReplyPort;

    @Transactional(readOnly = true)
    public Optional<TaxInvoicePdfDocument> findByTaxInvoiceId(String taxInvoiceId) {
        return repository.findByTaxInvoiceId(taxInvoiceId);
    }

    @Transactional
    public TaxInvoicePdfDocument beginGeneration(String taxInvoiceId, String taxInvoiceNumber) {
        log.info("Initiating PDF generation for tax invoice: {}", taxInvoiceNumber);
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId(taxInvoiceId)
                .taxInvoiceNumber(taxInvoiceNumber)
                .build();
        doc.startGeneration();
        return repository.save(doc);
    }

    @Transactional
    public TaxInvoicePdfDocument replaceAndBeginGeneration(
            UUID existingId, int previousRetryCount, String taxInvoiceId, String taxInvoiceNumber) {
        log.info("Replacing document {} and re-starting generation for tax invoice: {}", existingId, taxInvoiceNumber);
        repository.deleteById(existingId);
        repository.flush();
        TaxInvoicePdfDocument doc = TaxInvoicePdfDocument.builder()
                .taxInvoiceId(taxInvoiceId)
                .taxInvoiceNumber(taxInvoiceNumber)
                .build();
        doc.startGeneration();
        for (int i = 0; i < previousRetryCount + 1; i++) {
            doc.incrementRetryCount();
        }
        return repository.save(doc);
    }

    @Transactional
    public void completeGenerationAndPublish(UUID documentId, String s3Key, String fileUrl,
                                             long fileSize, int previousRetryCount,
                                             KafkaTaxInvoiceProcessCommand command) {
        TaxInvoicePdfDocument doc = requireDocument(documentId);
        doc.markCompleted(s3Key, fileUrl, fileSize);
        doc.markXmlEmbedded();
        applyRetryCount(doc, previousRetryCount);
        doc = repository.save(doc);

        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(doc, command));
        sagaReplyPort.publishSuccess(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                doc.getDocumentUrl(), doc.getFileSize());

        log.info("Completed PDF generation for saga {} tax invoice {}",
                command.getSagaId(), doc.getTaxInvoiceNumber());
    }

    @Transactional
    public void failGenerationAndPublish(UUID documentId, String errorMessage,
                                         int previousRetryCount,
                                         KafkaTaxInvoiceProcessCommand command) {
        String safeError = errorMessage != null ? errorMessage : "PDF generation failed";
        TaxInvoicePdfDocument doc = requireDocument(documentId);
        doc.markFailed(safeError);
        applyRetryCount(doc, previousRetryCount);
        repository.save(doc);

        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), safeError);

        log.warn("PDF generation failed for saga {} tax invoice {}: {}",
                command.getSagaId(), doc.getTaxInvoiceNumber(), safeError);
    }

    @Transactional
    public void deleteById(UUID documentId) {
        repository.deleteById(documentId);
        repository.flush();
    }

    @Transactional
    public void publishIdempotentSuccess(TaxInvoicePdfDocument existing,
                                         KafkaTaxInvoiceProcessCommand command) {
        pdfEventPort.publishPdfGenerated(buildGeneratedEvent(existing, command));
        sagaReplyPort.publishSuccess(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                existing.getDocumentUrl(), existing.getFileSize());
        log.warn("Tax invoice PDF already generated for saga {} — re-publishing SUCCESS reply",
                command.getSagaId());
    }

    @Transactional
    public void publishRetryExhausted(KafkaTaxInvoiceProcessCommand command) {
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(),
                "Maximum retry attempts exceeded");
        log.error("Max retries exceeded for saga {} tax invoice {}",
                command.getSagaId(), command.getTaxInvoiceNumber());
    }

    @Transactional
    public void publishGenerationFailure(KafkaTaxInvoiceProcessCommand command, String errorMessage) {
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), errorMessage);
    }

    @Transactional
    public void publishCompensated(KafkaTaxInvoiceCompensateCommand command) {
        sagaReplyPort.publishCompensated(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId());
    }

    @Transactional
    public void publishCompensationFailure(KafkaTaxInvoiceCompensateCommand command, String error) {
        sagaReplyPort.publishFailure(
                command.getSagaId(), command.getSagaStep(), command.getCorrelationId(), error);
    }

    private TaxInvoicePdfDocument requireDocument(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> {
                    log.error("TaxInvoicePdfDocument not found for id={}", documentId);
                    return new IllegalStateException("Expected tax invoice PDF document is absent");
                });
    }

    private void applyRetryCount(TaxInvoicePdfDocument doc, int previousRetryCount) {
        if (previousRetryCount < 0) return;
        int target = previousRetryCount + 1;
        while (doc.getRetryCount() < target) {
            doc.incrementRetryCount();
        }
    }

    private TaxInvoicePdfGeneratedEvent buildGeneratedEvent(TaxInvoicePdfDocument doc,
                                                             KafkaTaxInvoiceProcessCommand command) {
        return new TaxInvoicePdfGeneratedEvent(
                command.getDocumentId(),
                doc.getTaxInvoiceId(),
                doc.getTaxInvoiceNumber(),
                doc.getDocumentUrl(),
                doc.getFileSize(),
                doc.isXmlEmbedded(),
                command.getCorrelationId());
    }
}
```

> **Note:** This file imports `KafkaTaxInvoiceProcessCommand` and `KafkaTaxInvoiceCompensateCommand` from `infrastructure/adapter/in/kafka/`. Those classes are created in Task 9. For now, the file won't compile until Task 9 is done. That is fine — we will compile after Task 9.

**Step 4: Commit (compile after Task 9)**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentService.java \
        src/test/java/com/wpanther/taxinvoice/pdf/application/service/TaxInvoicePdfDocumentServiceTest.java
git commit -m "refactor: TaxInvoicePdfDocumentService uses short-TX pattern + port injection"
```

---

## Phase 5 — Application Inbound Ports & `SagaCommandHandler` refactor

### Task 8: Create `ProcessTaxInvoicePdfUseCase` and `CompensateTaxInvoicePdfUseCase`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/ProcessTaxInvoicePdfUseCase.java`
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/CompensateTaxInvoicePdfUseCase.java`

These reference `KafkaTaxInvoice*Command` from the adapter. Create as stubs for now — compilation is verified after Task 9.

```java
// src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/ProcessTaxInvoicePdfUseCase.java
package com.wpanther.taxinvoice.pdf.application.usecase;

import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;

public interface ProcessTaxInvoicePdfUseCase {
    void handle(KafkaTaxInvoiceProcessCommand command);
}
```

```java
// src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/CompensateTaxInvoicePdfUseCase.java
package com.wpanther.taxinvoice.pdf.application.usecase;

import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;

public interface CompensateTaxInvoicePdfUseCase {
    void handle(KafkaTaxInvoiceCompensateCommand command);
}
```

**Commit:**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/application/usecase/
git commit -m "feat: add ProcessTaxInvoicePdfUseCase and CompensateTaxInvoicePdfUseCase inbound ports"
```

---

## Phase 6 — Kafka Inbound Adapter

### Task 9: Create Kafka wire DTOs, `KafkaCommandMapper`, and new `SagaRouteConfig`

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java`
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceCompensateCommand.java`
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapper.java`
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/SagaRouteConfig.java`
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfig.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/domain/event/ProcessTaxInvoicePdfCommand.java`
- Delete: `src/main/java/com/wpanther/taxinvoice/pdf/domain/event/CompensateTaxInvoicePdfCommand.java`

**Step 1: Create `KafkaTaxInvoiceProcessCommand`**

Copy content of `domain/event/ProcessTaxInvoicePdfCommand.java`, change package to `com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka`, rename class to `KafkaTaxInvoiceProcessCommand`:

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class KafkaTaxInvoiceProcessCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")   private final String documentId;
    @JsonProperty("taxInvoiceId") private final String taxInvoiceId;
    @JsonProperty("taxInvoiceNumber") private final String taxInvoiceNumber;
    @JsonProperty("signedXmlUrl") private final String signedXmlUrl;
    @JsonProperty("taxInvoiceDataJson") private final String taxInvoiceDataJson;

    @JsonCreator
    public KafkaTaxInvoiceProcessCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId,
            @JsonProperty("taxInvoiceNumber") String taxInvoiceNumber,
            @JsonProperty("signedXmlUrl") String signedXmlUrl,
            @JsonProperty("taxInvoiceDataJson") String taxInvoiceDataJson) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
    }

    /** Convenience constructor for testing. */
    public KafkaTaxInvoiceProcessCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                         String documentId, String taxInvoiceId, String taxInvoiceNumber,
                                         String signedXmlUrl, String taxInvoiceDataJson) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlUrl = signedXmlUrl;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
    }
}
```

**Step 2: Create `KafkaTaxInvoiceCompensateCommand`**

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
public class KafkaTaxInvoiceCompensateCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @JsonProperty("documentId")   private final String documentId;
    @JsonProperty("taxInvoiceId") private final String taxInvoiceId;

    @JsonCreator
    public KafkaTaxInvoiceCompensateCommand(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("occurredAt") Instant occurredAt,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("version") int version,
            @JsonProperty("sagaId") String sagaId,
            @JsonProperty("sagaStep") SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId") String documentId,
            @JsonProperty("taxInvoiceId") String taxInvoiceId) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
    }

    /** Convenience constructor for testing. */
    public KafkaTaxInvoiceCompensateCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                            String documentId, String taxInvoiceId) {
        super(sagaId, sagaStep, correlationId);
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
    }
}
```

**Step 3: Write `KafkaCommandMapperTest`**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.saga.domain.enums.SagaStep;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KafkaCommandMapperTest {

    private final KafkaCommandMapper mapper = new KafkaCommandMapper();

    @Test
    void toProcess_mapsAllFields() {
        var src = new KafkaTaxInvoiceProcessCommand(
                null, null, null, 0,
                "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "doc-1", "tinv-1", "TINV-001", "http://minio/xml", "{\"k\":\"v\"}");

        var result = mapper.toProcess(src);

        assertThat(result.getSagaId()).isEqualTo("saga-1");
        assertThat(result.getCorrelationId()).isEqualTo("corr-1");
        assertThat(result.getDocumentId()).isEqualTo("doc-1");
        assertThat(result.getTaxInvoiceId()).isEqualTo("tinv-1");
        assertThat(result.getTaxInvoiceNumber()).isEqualTo("TINV-001");
        assertThat(result.getSignedXmlUrl()).isEqualTo("http://minio/xml");
        assertThat(result.getTaxInvoiceDataJson()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void toCompensate_mapsAllFields() {
        var src = new KafkaTaxInvoiceCompensateCommand(
                null, null, null, 0,
                "saga-2", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-2",
                "doc-2", "tinv-2");

        var result = mapper.toCompensate(src);

        assertThat(result.getSagaId()).isEqualTo("saga-2");
        assertThat(result.getCorrelationId()).isEqualTo("corr-2");
        assertThat(result.getDocumentId()).isEqualTo("doc-2");
        assertThat(result.getTaxInvoiceId()).isEqualTo("tinv-2");
    }
}
```

**Step 4: Create `KafkaCommandMapper`**

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import org.springframework.stereotype.Component;

@Component
public class KafkaCommandMapper {

    public KafkaTaxInvoiceProcessCommand toProcess(KafkaTaxInvoiceProcessCommand src) {
        // Wire DTO IS the command — identity mapping (same type used at use-case boundary)
        return src;
    }

    public KafkaTaxInvoiceCompensateCommand toCompensate(KafkaTaxInvoiceCompensateCommand src) {
        return src;
    }
}
```

> **Design note:** Because `TaxInvoicePdfDocumentService` and `SagaCommandHandler` are refactored to accept the `Kafka*Command` types directly (they live in `adapter/in/kafka/`), the mapper is an identity mapping. It remains for symmetry with the invoice service and to allow a future mapping step without changing the use-case interface.

**Step 5: Create new `SagaRouteConfig` in `adapter/in/kafka/`**

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.wpanther.taxinvoice.pdf.application.service.SagaCommandHandler;
import com.wpanther.taxinvoice.pdf.application.usecase.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.usecase.ProcessTaxInvoicePdfUseCase;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SagaRouteConfig extends RouteBuilder {

    private final ProcessTaxInvoicePdfUseCase processUseCase;
    private final CompensateTaxInvoicePdfUseCase compensateUseCase;
    private final SagaCommandHandler sagaCommandHandler;

    public SagaRouteConfig(ProcessTaxInvoicePdfUseCase processUseCase,
                           CompensateTaxInvoicePdfUseCase compensateUseCase,
                           SagaCommandHandler sagaCommandHandler) {
        this.processUseCase = processUseCase;
        this.compensateUseCase = compensateUseCase;
        this.sagaCommandHandler = sagaCommandHandler;
    }

    @Override
    public void configure() throws Exception {

        errorHandler(deadLetterChannel(
                        "kafka:{{app.kafka.topics.dlq}}?brokers={{app.kafka.bootstrap-servers}}")
                        .maximumRedeliveries(3)
                        .redeliveryDelay(1000)
                        .useExponentialBackOff()
                        .backOffMultiplier(2)
                        .maximumRedeliveryDelay(10000)
                        .logExhausted(true)
                        .logStackTrace(true)
                        .onPrepareFailure(exchange -> {
                            Object body = exchange.getIn().getBody();
                            Throwable cause = exchange.getProperty(
                                    org.apache.camel.Exchange.EXCEPTION_CAUGHT, Throwable.class);
                            if (body instanceof KafkaTaxInvoiceProcessCommand cmd) {
                                sagaCommandHandler.publishOrchestrationFailure(cmd, cause);
                            } else if (body instanceof KafkaTaxInvoiceCompensateCommand cmd) {
                                sagaCommandHandler.publishCompensationOrchestrationFailure(cmd, cause);
                            } else {
                                log.error("DLQ: body not deserialized ({}) — orchestrator must timeout",
                                        body == null ? "null" : body.getClass().getSimpleName());
                            }
                        }));

        from("kafka:{{app.kafka.topics.saga-command-tax-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error:true}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records:100}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count:3}}")
                .routeId("saga-command-consumer")
                .unmarshal().json(JsonLibrary.Jackson, KafkaTaxInvoiceProcessCommand.class)
                .process(exchange -> {
                    KafkaTaxInvoiceProcessCommand cmd =
                            exchange.getIn().getBody(KafkaTaxInvoiceProcessCommand.class);
                    log.info("Processing saga command for saga: {}, taxInvoice: {}",
                            cmd.getSagaId(), cmd.getTaxInvoiceNumber());
                    processUseCase.handle(cmd);
                })
                .log("Successfully processed saga command");

        from("kafka:{{app.kafka.topics.saga-compensation-tax-invoice-pdf}}"
                        + "?brokers={{app.kafka.bootstrap-servers}}"
                        + "&groupId={{app.kafka.consumer.group-id}}"
                        + "&autoOffsetReset=earliest"
                        + "&autoCommitEnable=false"
                        + "&breakOnFirstError={{app.kafka.consumer.break-on-first-error:true}}"
                        + "&maxPollRecords={{app.kafka.consumer.max-poll-records:100}}"
                        + "&consumersCount={{app.kafka.consumer.consumers-count:3}}")
                .routeId("saga-compensation-consumer")
                .unmarshal().json(JsonLibrary.Jackson, KafkaTaxInvoiceCompensateCommand.class)
                .process(exchange -> {
                    KafkaTaxInvoiceCompensateCommand cmd =
                            exchange.getIn().getBody(KafkaTaxInvoiceCompensateCommand.class);
                    log.info("Processing compensation for saga: {}, taxInvoice: {}",
                            cmd.getSagaId(), cmd.getTaxInvoiceId());
                    compensateUseCase.handle(cmd);
                })
                .log("Successfully processed compensation command");
    }
}
```

**Step 6: Add missing Camel consumer properties to `application.yml`**

Open `src/main/resources/application.yml`. Under `app.kafka.consumer:` add:
```yaml
app:
  kafka:
    consumer:
      break-on-first-error: ${KAFKA_BREAK_ON_FIRST_ERROR:true}
      max-poll-records: ${KAFKA_MAX_POLL_RECORDS:100}
      consumers-count: ${KAFKA_CONSUMERS_COUNT:3}
```

**Step 7: Delete old `CamelRouteConfig` and old domain event command files**

```bash
git rm src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfig.java
git rm src/main/java/com/wpanther/taxinvoice/pdf/domain/event/ProcessTaxInvoicePdfCommand.java
git rm src/main/java/com/wpanther/taxinvoice/pdf/domain/event/CompensateTaxInvoicePdfCommand.java
```

**Step 8: Rewrite `SagaCommandHandler` to implement use-case interfaces + short-TX pattern**

Replace entire `SagaCommandHandler.java` content:

```java
package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.application.port.out.PdfStoragePort;
import com.wpanther.taxinvoice.pdf.application.port.out.SagaReplyPort;
import com.wpanther.taxinvoice.pdf.application.port.out.SignedXmlFetchPort;
import com.wpanther.taxinvoice.pdf.application.usecase.CompensateTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.application.usecase.ProcessTaxInvoicePdfUseCase;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Orchestrates tax invoice PDF generation in response to saga commands.
 * No @Transactional here — all DB work is in short focused transactions via TaxInvoicePdfDocumentService.
 */
@Service
@Slf4j
public class SagaCommandHandler implements ProcessTaxInvoicePdfUseCase, CompensateTaxInvoicePdfUseCase {

    private static final String MDC_SAGA_ID        = "sagaId";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_TAX_INVOICE_NUM = "taxInvoiceNumber";
    private static final String MDC_TAX_INVOICE_ID  = "taxInvoiceId";

    private final TaxInvoicePdfDocumentService pdfDocumentService;
    private final TaxInvoicePdfGenerationService pdfGenerationService;
    private final PdfStoragePort pdfStoragePort;
    private final SagaReplyPort sagaReplyPort;
    private final SignedXmlFetchPort signedXmlFetchPort;
    private final int maxRetries;

    public SagaCommandHandler(TaxInvoicePdfDocumentService pdfDocumentService,
                              TaxInvoicePdfGenerationService pdfGenerationService,
                              PdfStoragePort pdfStoragePort,
                              SagaReplyPort sagaReplyPort,
                              SignedXmlFetchPort signedXmlFetchPort,
                              @Value("${app.pdf.generation.max-retries:3}") int maxRetries) {
        this.pdfDocumentService = pdfDocumentService;
        this.pdfGenerationService = pdfGenerationService;
        this.pdfStoragePort = pdfStoragePort;
        this.sagaReplyPort = sagaReplyPort;
        this.signedXmlFetchPort = signedXmlFetchPort;
        this.maxRetries = maxRetries;
    }

    @Override
    public void handle(KafkaTaxInvoiceProcessCommand command) {
        MDC.put(MDC_SAGA_ID,         command.getSagaId());
        MDC.put(MDC_CORRELATION_ID,  command.getCorrelationId());
        MDC.put(MDC_TAX_INVOICE_NUM, command.getTaxInvoiceNumber());
        MDC.put(MDC_TAX_INVOICE_ID,  command.getTaxInvoiceId());
        try {
            log.info("Handling ProcessCommand for saga {} taxInvoice {}",
                    command.getSagaId(), command.getTaxInvoiceNumber());
            try {
                String signedXmlUrl  = command.getSignedXmlUrl();
                String taxInvoiceId  = command.getTaxInvoiceId();
                String taxInvoiceNum = command.getTaxInvoiceNumber();

                if (signedXmlUrl == null || signedXmlUrl.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(command, "signedXmlUrl is null or blank");
                    return;
                }
                if (taxInvoiceId == null || taxInvoiceId.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(command, "taxInvoiceId is null or blank");
                    return;
                }
                if (taxInvoiceNum == null || taxInvoiceNum.isBlank()) {
                    pdfDocumentService.publishGenerationFailure(command, "taxInvoiceNumber is null or blank");
                    return;
                }

                Optional<TaxInvoicePdfDocument> existing =
                        pdfDocumentService.findByTaxInvoiceId(taxInvoiceId);

                if (existing.isPresent() && existing.get().isCompleted()) {
                    pdfDocumentService.publishIdempotentSuccess(existing.get(), command);
                    return;
                }

                int previousRetryCount = existing.map(TaxInvoicePdfDocument::getRetryCount).orElse(-1);

                if (existing.isPresent()) {
                    if (existing.get().isMaxRetriesExceeded(maxRetries)) {
                        pdfDocumentService.publishRetryExhausted(command);
                        return;
                    }
                }

                // TX1: create GENERATING record
                TaxInvoicePdfDocument document;
                if (existing.isPresent()) {
                    document = pdfDocumentService.replaceAndBeginGeneration(
                            existing.get().getId(), previousRetryCount, taxInvoiceId, taxInvoiceNum);
                } else {
                    document = pdfDocumentService.beginGeneration(taxInvoiceId, taxInvoiceNum);
                }

                String s3Key = null;
                try {
                    // NO TRANSACTION: download, generate, upload
                    String signedXml = signedXmlFetchPort.fetch(signedXmlUrl);
                    byte[] pdfBytes  = pdfGenerationService.generatePdf(
                            taxInvoiceNum, signedXml, command.getTaxInvoiceDataJson());
                    s3Key = pdfStoragePort.store(taxInvoiceNum, pdfBytes);
                    String fileUrl   = pdfStoragePort.resolveUrl(s3Key);

                    // TX2: mark COMPLETED + write outbox
                    pdfDocumentService.completeGenerationAndPublish(
                            document.getId(), s3Key, fileUrl, pdfBytes.length, previousRetryCount, command);

                } catch (CallNotPermittedException e) {
                    log.warn("MinIO CB OPEN for saga {} taxInvoice {}: {}",
                            command.getSagaId(), taxInvoiceNum, e.getMessage());
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), "MinIO circuit breaker open: " + e.getMessage(),
                            previousRetryCount, command);

                } catch (Exception e) {
                    if (s3Key != null) {
                        try { pdfStoragePort.delete(s3Key); }
                        catch (Exception del) {
                            log.error("[ORPHAN_PDF] s3Key={} saga={} error={}", s3Key, command.getSagaId(),
                                    describeThrowable(del));
                        }
                    }
                    log.error("PDF generation/upload failed for saga {} taxInvoice {}: {}",
                            command.getSagaId(), taxInvoiceNum, e.getMessage(), e);
                    pdfDocumentService.failGenerationAndPublish(
                            document.getId(), describeThrowable(e), previousRetryCount, command);
                }

            } catch (OptimisticLockingFailureException e) {
                log.warn("Concurrent modification for saga {}: {}", command.getSagaId(), e.getMessage());
                pdfDocumentService.publishGenerationFailure(command, "Concurrent modification: " + e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error for saga {}: {}", command.getSagaId(), e.getMessage(), e);
                pdfDocumentService.publishGenerationFailure(command, describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Override
    public void handle(KafkaTaxInvoiceCompensateCommand command) {
        MDC.put(MDC_SAGA_ID,        command.getSagaId());
        MDC.put(MDC_CORRELATION_ID, command.getCorrelationId());
        MDC.put(MDC_TAX_INVOICE_ID, command.getTaxInvoiceId());
        try {
            log.info("Handling compensation for saga {} taxInvoice {}",
                    command.getSagaId(), command.getTaxInvoiceId());
            try {
                Optional<TaxInvoicePdfDocument> existing =
                        pdfDocumentService.findByTaxInvoiceId(command.getTaxInvoiceId());

                if (existing.isPresent()) {
                    TaxInvoicePdfDocument doc = existing.get();
                    pdfDocumentService.deleteById(doc.getId());
                    if (doc.getDocumentPath() != null) {
                        try { pdfStoragePort.delete(doc.getDocumentPath()); }
                        catch (Exception e) {
                            log.warn("Failed to delete PDF from MinIO for saga {} key {}: {}",
                                    command.getSagaId(), doc.getDocumentPath(), e.getMessage());
                        }
                    }
                    log.info("Compensated TaxInvoicePdfDocument {} for saga {}",
                            doc.getId(), command.getSagaId());
                } else {
                    log.info("No document for taxInvoiceId {} — already compensated",
                            command.getTaxInvoiceId());
                }
                pdfDocumentService.publishCompensated(command);

            } catch (Exception e) {
                log.error("Failed to compensate for saga {}: {}", command.getSagaId(), e.getMessage(), e);
                pdfDocumentService.publishCompensationFailure(
                        command, "Compensation failed: " + describeThrowable(e));
            }
        } finally {
            MDC.clear();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOrchestrationFailure(KafkaTaxInvoiceProcessCommand command, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(),
                    "Message routed to DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of DLQ failure for saga {}", command.getSagaId(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishCompensationOrchestrationFailure(KafkaTaxInvoiceCompensateCommand command, Throwable cause) {
        try {
            sagaReplyPort.publishFailure(command.getSagaId(), command.getSagaStep(),
                    command.getCorrelationId(),
                    "Compensation DLQ after retry exhaustion: " + describeThrowable(cause));
        } catch (Exception e) {
            log.error("Cannot notify orchestrator of compensation DLQ failure for saga {}", command.getSagaId(), e);
        }
    }

    private String describeThrowable(Throwable t) {
        if (t == null) return "unknown error";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
```

**Step 9: Compile to verify all new code compiles together**

```bash
mvn compile -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 10: Run mapper test**

```bash
mvn test -Dtest=KafkaCommandMapperTest -q 2>&1 | tail -5
```
Expected: BUILD SUCCESS.

**Step 11: Run existing tests to catch regressions**

```bash
mvn test -q 2>&1 | tail -10
```
Fix any import failures in existing test classes (`SagaCommandHandlerTest`, `TaxInvoicePdfDocumentServiceTest`) by updating their imports from `domain.event.*Command` to `infrastructure.adapter.in.kafka.Kafka*Command`.

**Step 12: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/
git add src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/
git add src/main/resources/application.yml
git commit -m "refactor: Kafka inbound adapter, use-case interfaces, SagaCommandHandler short-TX pattern"
```

---

## Phase 7 — Outbound Adapters Restructure

### Task 10: Move `infrastructure/messaging/` → `infrastructure/adapter/out/messaging/`; move outbound Kafka DTOs from `domain/event/`

**Files to move:**
- `EventPublisher.java`, `SagaReplyPublisher.java`, `OutboxConstants.java` → `adapter/out/messaging/`
- `TaxInvoicePdfReplyEvent.java`, `TaxInvoicePdfGeneratedEvent.java` (from `domain/event/`) → `adapter/out/messaging/`

**Step 1: Create new files — update package declaration only**

For each of the 5 files: copy content, change `package com.wpanther.taxinvoice.pdf.infrastructure.messaging` → `com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging` (or `domain.event` for the DTOs).

Update imports in `EventPublisher` and `SagaReplyPublisher`:
- `TaxInvoicePdfGeneratedEvent` → `...infrastructure.adapter.out.messaging.TaxInvoicePdfGeneratedEvent`
- `TaxInvoicePdfReplyEvent` → `...infrastructure.adapter.out.messaging.TaxInvoicePdfReplyEvent`

**Step 2: Update `PdfEventPort` import**

`PdfEventPort` imports `TaxInvoicePdfGeneratedEvent` — update:
```java
// change:
import com.wpanther.taxinvoice.pdf.domain.event.TaxInvoicePdfGeneratedEvent;
// to:
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.TaxInvoicePdfGeneratedEvent;
```

Also update `TaxInvoicePdfDocumentService.buildGeneratedEvent()` import.

**Step 3: Delete old files**

```bash
git rm -r src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/messaging/
git rm src/main/java/com/wpanther/taxinvoice/pdf/domain/event/TaxInvoicePdfReplyEvent.java
git rm src/main/java/com/wpanther/taxinvoice/pdf/domain/event/TaxInvoicePdfGeneratedEvent.java
```

**Step 4: Move test files**

Move `CamelRouteConfigTest.java` → `infrastructure/adapter/in/kafka/SagaRouteConfigTest.java` — update package + class name reference.

**Step 5: Compile and test**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 6: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/
git commit -m "refactor: move messaging adapters and outbound Kafka DTOs to adapter/out/messaging"
```

---

### Task 11: Move `infrastructure/persistence/` → `infrastructure/adapter/out/persistence/`; rename `RepositoryImpl` → `RepositoryAdapter`

**Step 1: Create new files in `adapter/out/persistence/`**

For each of the 6 files (entity, JPA interface, impl, outbox × 3): copy, update package to `...infrastructure.adapter.out.persistence` (and `...outbox` sub-package).

Rename `JpaTaxInvoicePdfDocumentRepositoryImpl` → `TaxInvoicePdfDocumentRepositoryAdapter`.

**Step 2: Update `OutboxConfig`**

Update import of `SpringDataOutboxRepository` to new package.

**Step 3: Delete old `infrastructure/persistence/` tree**

```bash
git rm -r src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/persistence/
```

**Step 4: Move test file**

Move `JpaTaxInvoicePdfDocumentRepositoryImplTest.java` → `infrastructure/adapter/out/persistence/TaxInvoicePdfDocumentRepositoryAdapterTest.java`. Update package and class reference.

**Step 5: Test**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 6: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/persistence/
git add src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/persistence/
git commit -m "refactor: move persistence to adapter/out/persistence, rename to RepositoryAdapter"
```

---

### Task 12: Move `infrastructure/pdf/` → `infrastructure/adapter/out/pdf/`

**Step 1: Create new files**

Copy `FopTaxInvoicePdfGenerator.java`, `TaxInvoicePdfGenerationServiceImpl.java`, `PdfA3Converter.java` to `adapter/out/pdf/`. Update packages.

**Step 2: Delete old files**

```bash
git rm -r src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/pdf/
```

**Step 3: Move test files**

Move `FopTaxInvoicePdfGeneratorTest.java` and `TaxInvoicePdfGenerationServiceImplTest.java` to `adapter/out/pdf/`. Update packages.

**Step 4: Test**

```bash
mvn test -q 2>&1 | tail -10
```
Expected: BUILD SUCCESS.

**Step 5: Commit**

```bash
git add -u
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/
git add src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/
git commit -m "refactor: move PDF generation adapters to adapter/out/pdf"
```

---

## Phase 8 — Config Cleanup & Delete Empty Packages

### Task 13: Verify config, delete empty packages

**Step 1: Verify `infrastructure/config/` contains only bean factories**

```bash
ls src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/config/
```
Expected: `MinioConfig.java  OutboxConfig.java`

**Step 2: Delete empty `domain/event/` package**

```bash
find src/main/java/com/wpanther/taxinvoice/pdf/domain/event -name "*.java" 2>/dev/null
```
Expected: no output. If empty:
```bash
git rm -r src/main/java/com/wpanther/taxinvoice/pdf/domain/event/ 2>/dev/null || true
```

**Step 3: Verify final infrastructure tree**

```bash
find src/main/java/com/wpanther/taxinvoice/pdf/infrastructure -mindepth 1 -maxdepth 1 -type d | sort
```
Expected:
```
.../infrastructure/adapter
.../infrastructure/config
```

**Step 4: Commit if any cleanup needed**

```bash
git add -u && git commit -m "chore: remove empty packages after adapter restructure" || true
```

---

## Phase 9 — Add Missing Tests & Full Verification

### Task 14: Add missing test classes

**Step 1: Add `EventPublisherTest`**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/EventPublisherTest.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventPublisherTest {

    @Mock OutboxService outboxService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks EventPublisher publisher;

    @Test
    void publishPdfGenerated_writesToOutbox() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        TaxInvoicePdfGeneratedEvent event = new TaxInvoicePdfGeneratedEvent(
                "doc-1", "tinv-1", "TINV-001", "http://url", 1024L, true, "corr-1");

        publisher.publishPdfGenerated(event);

        verify(outboxService).saveWithRouting(
                eq(event), anyString(), eq("tinv-1"),
                eq("pdf.generated.tax-invoice"), eq("tinv-1"), anyString());
    }
}
```

**Step 2: Add `SagaReplyPublisherTest`**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/messaging/SagaReplyPublisherTest.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.infrastructure.outbox.OutboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SagaReplyPublisherTest {

    @Mock OutboxService outboxService;
    @Mock ObjectMapper objectMapper;
    @InjectMocks SagaReplyPublisher publisher;

    @Test
    void publishSuccess_writesToOutbox() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        publisher.publishSuccess("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
                "http://url/file.pdf", 2048L);

        verify(outboxService).saveWithRouting(any(), anyString(), eq("saga-1"),
                eq("saga.reply.tax-invoice-pdf"), eq("saga-1"), anyString());
    }

    @Test
    void publishFailure_writesToOutbox() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        publisher.publishFailure("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1", "error msg");

        verify(outboxService).saveWithRouting(any(), anyString(), eq("saga-1"),
                eq("saga.reply.tax-invoice-pdf"), eq("saga-1"), anyString());
    }

    @Test
    void publishCompensated_writesToOutbox() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        publisher.publishCompensated("saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1");

        verify(outboxService).saveWithRouting(any(), anyString(), eq("saga-1"),
                eq("saga.reply.tax-invoice-pdf"), eq("saga-1"), anyString());
    }
}
```

**Step 3: Add `PdfA3ConverterTest`**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/PdfA3ConverterTest.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PdfA3ConverterTest {

    @Test
    void convertToPdfA3_nullPdfBytes_throwsException() {
        PdfA3Converter converter = new PdfA3Converter();
        assertThatThrownBy(() -> converter.convertToPdfA3(null, "<xml/>", "file.xml", "TINV-001"))
                .isInstanceOf(Exception.class);
    }
}
```

**Step 4: Add `TaxInvoicePdfDocumentRepositoryAdapterTest` (unit)**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/persistence/TaxInvoicePdfDocumentRepositoryAdapterTest.java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence;

import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaxInvoicePdfDocumentRepositoryAdapterTest {

    @Mock JpaTaxInvoicePdfDocumentRepository jpaRepository;
    @InjectMocks TaxInvoicePdfDocumentRepositoryAdapter adapter;

    @Test
    void findByTaxInvoiceId_notFound_returnsEmpty() {
        when(jpaRepository.findByTaxInvoiceId("tinv-1")).thenReturn(Optional.empty());
        assertThat(adapter.findByTaxInvoiceId("tinv-1")).isEmpty();
    }

    @Test
    void deleteById_delegatesToJpa() {
        UUID id = UUID.randomUUID();
        adapter.deleteById(id);
        verify(jpaRepository).deleteById(id);
    }
}
```

**Step 5: Add `ApplicationContextLoadTest`**

```java
// src/test/java/com/wpanther/taxinvoice/pdf/ApplicationContextLoadTest.java
package com.wpanther.taxinvoice.pdf;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationContextLoadTest {

    @Test
    void contextLoads() {
        // Verifies the Spring context starts cleanly with all beans wired
    }
}
```

**Step 6: Run full test suite**

```bash
mvn test -q 2>&1 | tail -15
```
Expected: BUILD SUCCESS — all tests pass.

**Step 7: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/pdf/
git commit -m "test: add missing test classes for hexagonal adapter layer"
```

---

## Phase 10 — Full Verification

### Task 15: Coverage gate + structural dependency check

**Step 1: Run with JaCoCo**

```bash
mvn verify -q 2>&1 | tail -20
```
Expected: BUILD SUCCESS, all JaCoCo gates pass (90% per package).

**Step 2: If coverage fails, find gaps**

```bash
mvn verify 2>&1 | grep -A3 "Coverage check"
```

**Step 3: Verify dependency rule**

```bash
# No domain class imports from infrastructure or application
grep -r "import com.wpanther.taxinvoice.pdf.infrastructure\|import com.wpanther.taxinvoice.pdf.application" \
  src/main/java/com/wpanther/taxinvoice/pdf/domain/ 2>/dev/null
# Expected: no output

# No application/service class imports from infrastructure
grep -r "import com.wpanther.taxinvoice.pdf.infrastructure" \
  src/main/java/com/wpanther/taxinvoice/pdf/application/service/ 2>/dev/null
# Expected: no output (application/usecase/ may import adapter/in/kafka - that is by design)
```

**Step 4: Final structure check**

```bash
find src/main/java/com/wpanther/taxinvoice/pdf -name "*.java" | sort
```
Verify matches the design in `docs/plans/2026-03-09-hexagonal-architecture-migration-design.md`.

**Step 5: Final commit**

```bash
git commit --allow-empty -m "chore: hexagonal architecture migration complete for taxinvoice-pdf-generation-service"
```

---

## Summary of Changes

| Action | Details |
|--------|---------|
| New domain | `TaxInvoicePdfGenerationException` |
| New application ports | 4 output port interfaces + 2 use-case interfaces |
| New adapters | `MinioStorageAdapter`, `RestTemplateSignedXmlFetcher`, `KafkaCommandMapper`, `SagaRouteConfig` |
| Renamed | `CamelRouteConfig` → `SagaRouteConfig`, `JpaTaxInvoicePdfDocumentRepositoryImpl` → `TaxInvoicePdfDocumentRepositoryAdapter`, `ProcessTaxInvoicePdfCommand` → `KafkaTaxInvoiceProcessCommand`, `CompensateTaxInvoicePdfCommand` → `KafkaTaxInvoiceCompensateCommand` |
| Logic refactored | `SagaCommandHandler`: `@Transactional` removed, implements use-case interfaces, uses ports; `TaxInvoicePdfDocumentService`: broken long-TX replaced with short-TX pattern |
| Packages moved | `persistence/`, `messaging/`, `pdf/` → `adapter/out/`; outbound Kafka DTOs from `domain/event/` → `adapter/out/messaging/` |
| Deleted | `domain/event/` package, `infrastructure/config/CamelRouteConfig` |
| New tests | 10 new test classes |
| Updated tests | 4 existing tests (imports + port mocks) |
