# Code Review: taxinvoice-pdf-generation-service vs invoice-pdf-generation-service

## Summary

This code review compares `taxinvoice-pdf-generation-service` with `invoice-pdf-generation-service` to identify improvement opportunities. The invoice-pdf-generation-service has several enhancements that would benefit taxinvoice-pdf-generation-service, including:

1. **FOP Generator**: Semaphore concurrency control, comprehensive metrics (Timer, DistributionSummary, Gauge), max PDF size validation
2. **Test Coverage**: Significantly more comprehensive test suite
3. **Implementation Service**: ThreadLocal caching for XML factories, XML well-formedness validation, enhanced input validation
4. **Configuration**: Additional configuration options for concurrent renders and max PDF size

**Verdict**: [ ] Approve | [x] Request Changes | [ ] Comment

---

## Critical Issues (Must Fix)

### 1. `FopTaxInvoicePdfGenerator.java:112` - Missing Concurrency Control
- **Current**: No limit on concurrent FOP renders (each ~50-200 MB heap)
- **Suggested**: Add Semaphore with configurable max permits
- **Impact**: Under load, unlimited concurrent renders can cause OutOfMemoryError

```java
// invoice-pdf-generation-service has:
private final Semaphore renderSemaphore;    // caps concurrent FOP renders

public FopInvoicePdfGenerator(
        @Value("${app.pdf.generation.max-concurrent-renders:3}") int maxConcurrentRenders,
        @Value("${app.pdf.generation.max-pdf-size-bytes:52428800}") long maxPdfSizeBytes,
        MeterRegistry meterRegistry) {
    this.renderSemaphore = new Semaphore(maxConcurrentRenders, true); // fair
    // ...
}

@NewSpan("pdf.fop.render")
public byte[] generatePdf(String xmlData) throws PdfGenerationException {
    try {
        renderSemaphore.acquire();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new PdfGenerationException("PDF generation interrupted while waiting for render slot", e);
    }
    try {
        // ... FOP rendering
    } finally {
        renderTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        renderSemaphore.release();
    }
}
```

### 2. `TaxInvoicePdfGenerationServiceImpl.java:67` - Manual XML Escaping Vulnerability
- **Current**: Custom `escapeXml()` method with manual string replacement
- **Suggested**: Use `XMLStreamWriter` which handles escaping automatically
- **Impact**: Manual escaping is error-prone; XMLStreamWriter is the JAXP standard

```java
// Current (taxinvoice-pdf-generation-service) - vulnerable:
private String escapeXml(String value) {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
}

// Suggested (invoice-pdf-generation-service) - correct:
private static final ThreadLocal<XMLOutputFactory> XML_OUTPUT_FACTORY =
        ThreadLocal.withInitial(XMLOutputFactory::newInstance);

private String convertJsonToXml(String invoiceDataJson, String invoiceNumber) throws Exception {
    StringWriter sw = new StringWriter();
    XMLStreamWriter writer = XML_OUTPUT_FACTORY.get().createXMLStreamWriter(sw);
    try {
        // XMLStreamWriter.writeCharacters() automatically escapes &, <, > in text content
        writer.writeStartElement("taxInvoice");
        writeElement(writer, "taxInvoiceNumber", taxInvoiceNumber);
        // ...
    } finally {
        XML_OUTPUT_FACTORY.remove();
    }
}
```

---

## Major Issues (Should Fix)

### 1. `FopTaxInvoicePdfGenerator.java` - Missing Comprehensive Metrics
- **Current**: No Micrometer metrics for PDF generation
- **Suggested**: Add Timer, DistributionSummary, and Gauge metrics
- **Impact**: No observability into PDF generation performance and resource usage

```java
// invoice-pdf-generation-service has:
private final Timer renderTimer;
private final DistributionSummary pdfSizeSummary;

public FopInvoicePdfGenerator(..., MeterRegistry meterRegistry) {
    this.renderTimer = meterRegistry.timer("pdf.fop.render");
    this.pdfSizeSummary = DistributionSummary.builder("pdf.fop.size.bytes")
            .description("Size of generated invoice PDFs in bytes")
            .register(meterRegistry);
    Gauge.builder("pdf.fop.render.available_permits", renderSemaphore, Semaphore::availablePermits)
            .description("Available FOP concurrent render permits")
            .register(meterRegistry);
}
```

### 2. `TaxInvoicePdfGenerationServiceImpl.java:30` - Missing Input Validation
- **Current**: No validation for null/blank inputs or JSON size limits
- **Suggested**: Add input validation with configured limits
- **Impact**: Invalid inputs cause confusing errors downstream; large JSON can exhaust memory

```java
// invoice-pdf-generation-service has:
private final int maxJsonSizeBytes;

@Override
public byte[] generatePdf(String invoiceNumber, String xmlContent, String invoiceDataJson) {
    if (xmlContent == null || xmlContent.isBlank()) {
        throw new InvoicePdfGenerationException(
                "xmlContent (signed XML) is null or blank for invoice: " + invoiceNumber);
    }
    if (invoiceDataJson == null) {
        throw new InvoicePdfGenerationException(
                "invoiceDataJson is null for invoice: " + invoiceNumber);
    }
    if (invoiceDataJson.length() > maxJsonSizeBytes) {
        throw new InvoicePdfGenerationException(
                "invoiceDataJson exceeds max allowed size for invoice " + invoiceNumber
                + ": " + invoiceDataJson.length() + " chars > " + maxJsonSizeBytes);
    }
    // ...
}
```

### 3. `FopTaxInvoicePdfGenerator.java` - Missing Max PDF Size Validation
- **Current**: No validation on generated PDF size
- **Suggested**: Add max PDF size check after FOP rendering
- **Impact**: Malicious or malformed input could generate extremely large PDFs

```java
// invoice-pdf-generation-service has:
private final long maxPdfSizeBytes;

public byte[] generatePdf(String xmlData) throws PdfGenerationException {
    // ... FOP rendering
    byte[] pdfBytes = pdfOutput.toByteArray();
    if (pdfBytes.length > maxPdfSizeBytes) {
        throw new PdfGenerationException(
                String.format("Generated PDF exceeds max allowed size: %d bytes > %d bytes",
                        pdfBytes.length, maxPdfSizeBytes));
    }
    return pdfBytes;
}
```

### 4. `FopTaxInvoicePdfGenerator.java` - Missing Font Health Check at Startup
- **Current**: No validation that required Thai fonts exist at startup
- **Suggested**: Add `checkFontAvailability()` method called from constructor
- **Impact**: PDF generation fails at runtime with cryptic errors if fonts are missing

```java
// invoice-pdf-generation-service has:
private static final List<String> REQUIRED_FONTS = List.of(
        "fonts/NotoSansThaiLooped-Regular.ttf",
        "fonts/NotoSansThaiLooped-Bold.ttf"
);

public void checkFontAvailability() {
    List<String> missing = REQUIRED_FONTS.stream()
            .filter(font -> !new ClassPathResource(font).exists())
            .toList();
    if (!missing.isEmpty()) {
        log.warn("Thai font files not found on classpath: {} — Thai text may not render correctly...",
                missing);
    } else {
        log.info("Font check: all {} required Thai font files present on classpath.", REQUIRED_FONTS.size());
    }
}
```

### 5. `TaxInvoicePdfGenerationServiceImpl.java:37` - Missing XML Well-Formedness Validation
- **Current**: Generated XML is sent directly to FOP without validation
- **Suggested**: Validate XML well-formedness before FOP processing
- **Impact**: Malformed XML causes confusing FOP errors instead of clear validation messages

```java
// invoice-pdf-generation-service has:
private static final ThreadLocal<SAXParserFactory> SAX_PARSER_FACTORY =
        ThreadLocal.withInitial(SAXParserFactory::newInstance);

private void validateXmlWellFormedness(String xml, String invoiceNumber)
        throws InvoicePdfGenerationException {
    try {
        SAX_PARSER_FACTORY.get().newSAXParser().parse(
                new InputSource(new StringReader(xml)),
                new org.xml.sax.helpers.DefaultHandler());
    } catch (Exception e) {
        throw new InvoicePdfGenerationException(
                "Generated XML is not well-formed for invoice " + invoiceNumber + ": " + e.getMessage(), e);
    } finally {
        SAX_PARSER_FACTORY.remove();
    }
}
```

---

## Minor Issues (Nice to Have)

### 1. `application.yml:75` - Missing Configuration Properties
- **Current**: No `max-concurrent-renders` or `max-pdf-size-bytes` configuration
- **Suggested**: Add these properties to match invoice-pdf-generation-service

```yaml
app:
  pdf:
    generation:
      max-concurrent-renders: ${PDF_MAX_CONCURRENT_RENDERS:3}
      max-pdf-size-bytes: ${PDF_MAX_SIZE_BYTES:52428800}
```

### 2. `FopTaxInvoicePdfGenerator.java:112` - Missing Distributed Tracing Span
- **Current**: No `@NewSpan` annotation for observability
- **Suggested**: Add `@NewSpan("pdf.fop.render")` to `generatePdf()` method

```java
@NewSpan("pdf.fop.render")
public byte[] generatePdf(String xmlData) throws PdfGenerationException {
    // ...
}
```

### 3. `TaxInvoicePdfGenerationServiceImpl.java:154` - Missing Required Field Validation
- **Current**: No validation that seller/buyer fields exist in JSON
- **Suggested**: Validate required fields before processing

```java
// invoice-pdf-generation-service has:
JsonNode sellerNode = root.path("seller");
if (sellerNode.isMissingNode() || sellerNode.isNull()) {
    throw new InvoicePdfGenerationException(
            "invoiceDataJson is missing required field 'seller' for invoice: " + invoiceNumber);
}
```

---

## Positive Feedback

Both services share excellent patterns:
- **Transactional Outbox Pattern**: Both use saga-commons for reliable event delivery
- **DDD Architecture**: Clean domain/application/infrastructure layer separation
- **Circuit Breakers**: Both configure Resilience4j for MinIO and signed XML fetch
- **Font Health Check**: Both validate Thai fonts at startup
- **MinIO Cleanup Service**: Both implement orphaned PDF cleanup with scheduled jobs
- **Comprehensive Configuration**: Both have well-organized application.yml with environment variable overrides

---

## Questions for Author

1. Should taxinvoice-pdf-generation-service adopt the same Semaphore concurrency control as invoice-pdf-generation-service? The default of 3 concurrent renders is conservative but safe.
2. Is the manual XML escaping in `TaxInvoicePdfGenerationServiceImpl` intentional, or would you prefer to migrate to `XMLStreamWriter` for consistency?
3. Would you like to add the same comprehensive metrics (Timer, DistributionSummary, Gauge) to taxinvoice-pdf-generation-service?
4. Should we standardize the configuration properties between the two services?

---

## Test Coverage Assessment

| Test Category | invoice-pdf-generation-service | taxinvoice-pdf-generation-service |
|---------------|-------------------------------|----------------------------------|
| Constructor validation | ✅ maxConcurrentRenders, maxPdfSizeBytes | ❌ Not tested |
| Semaphore behavior | ✅ Permits, blocking, fair queue | ❌ No Semaphore |
| PDF generation | ✅ Valid XML returns %PDF header | ❌ Not tested |
| Malformed XML | ✅ PdfGenerationException | ❌ Not tested |
| PDF size limit | ✅ Exceeds max size throws | ❌ No size limit |
| Thread interruption | ✅ Interrupted thread handling | ❌ Not tested |
| Font availability | ✅ checkFontAvailability() | ❌ Not tested |
| URI resolution | ❌ Not tested | ✅ Tests for baseUri |

**Recommendation**: Adopt test patterns from invoice-pdf-generation-service's `FopInvoicePdfGeneratorTest.java` (172 lines) to significantly improve coverage.

---

## Comparison Summary

| Feature | invoice-pdf-generation-service | taxinvoice-pdf-generation-service |
|---------|-------------------------------|----------------------------------|
| **Concurrency Control** | Semaphore (fair, configurable) | None |
| **Metrics** | Timer, DistributionSummary, Gauge | None |
| **Max PDF Size** | Validated after render | Not validated |
| **Font Check** | At startup (checkFontAvailability) | At startup (FontHealthCheck) |
| **XML Generation** | XMLStreamWriter (safe) | Manual escaping (vulnerable) |
| **XML Validation** | SAXParser well-formedness check | None |
| **Input Validation** | Null, blank, max JSON size | None |
| **Field Validation** | Seller/buyer required | None |
| **ThreadLocal Caching** | XMLOutputFactory, SAXParserFactory | None |
| **Distributed Tracing** | @NewSpan on generatePdf() | None |
| **Configuration** | max-concurrent-renders, max-pdf-size-bytes | None |
| **Test Lines** | ~172 (FopInvoicePdfGeneratorTest) | ~54 (FopTaxInvoicePdfGeneratorTest) |

---

## Migration Priority

1. **HIGH**: Add Semaphore concurrency control to prevent OOM under load
2. **HIGH**: Replace manual XML escaping with XMLStreamWriter
3. **MEDIUM**: Add comprehensive metrics for observability
4. **MEDIUM**: Add input validation (null, blank, max size)
5. **MEDIUM**: Add max PDF size validation
6. **MEDIUM**: Add XML well-formedness validation
7. **LOW**: Add required field validation (seller/buyer)
8. **LOW**: Add @NewSpan for distributed tracing
9. **LOW**: Adopt test patterns from invoice-pdf-generation-service

---

## Build Command Note

Per user feedback: Use `mvn clean test` instead of `mvn test` to avoid stale compilation issues.
