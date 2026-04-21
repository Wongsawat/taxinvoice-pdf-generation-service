---
title: Generate Tax Invoice PDF Directly from Signed XML
date: 2026-04-21
status: approved
---

# Generate Tax Invoice PDF Directly from Signed XML

## Problem

The `taxinvoice-pdf-generation-service` currently generates the visual PDF layout from `taxInvoiceDataJson` — a redundant JSON payload carried alongside `signedXmlUrl` in the saga command. All business data needed for the PDF already exists in the signed XML. This dual-source design creates unnecessary payload size, a JSON-to-XML conversion step, and a separate data model to maintain.

## Goal

Remove `taxInvoiceDataJson` entirely. Generate the PDF layout by passing the signed XML directly to Apache FOP with a new XSL-FO stylesheet that navigates the Thai e-Tax namespace format (`rsm:` / `ram:`). Compute `amountInWords` (Thai baht in words) in Java and inject it as an XSLT parameter.

---

## Data Flow

**Before:**
```
signedXmlUrl ──fetch──► signedXml ─────────────────────────────────────────► embed in PDF/A-3
taxInvoiceDataJson ──convertJsonToXml()──► flat XML ──FOP(taxinvoice.xsl)──► base PDF
```

**After:**
```
signedXmlUrl ──fetch──► signedXml ──XPath──► GrandTotalAmount ──► ThaiAmountWordsConverter ──► XSLT param "amountInWords"
                               └──────────────────────────────────FOP(taxinvoice-direct.xsl)──► base PDF ──► embed in PDF/A-3
```

The signed XML reaches FOP unmodified. No intermediate flat XML is produced.

---

## XML Structure Reference

Based on `Example_TaxInvoice_2p1_v1.xml` (ETDA v2.1):

| PDF Field | XPath in signed XML |
|-----------|---------------------|
| Invoice number | `rsm:ExchangedDocument/ram:ID` |
| Issue date | `rsm:ExchangedDocument/ram:IssueDateTime` |
| Document name | `rsm:ExchangedDocument/ram:Name` |
| Notes | `rsm:ExchangedDocument/ram:IncludedNote/ram:Subject` |
| Seller name | `ram:SellerTradeParty/ram:Name` |
| Seller tax ID | `ram:SellerTradeParty/ram:SpecifiedTaxRegistration/ram:ID` |
| Seller address | `ram:SellerTradeParty/ram:PostalTradeAddress/*` |
| Buyer name | `ram:BuyerTradeParty/ram:Name` |
| Buyer tax ID | `ram:BuyerTradeParty/ram:SpecifiedTaxRegistration/ram:ID` |
| Buyer address | `ram:BuyerTradeParty/ram:PostalTradeAddress/*` |
| Due date | `ram:SpecifiedTradePaymentTerms/ram:DueDateDateTime` |
| Currency | `ram:InvoiceCurrencyCode` |
| VAT rate | `ram:ApplicableTradeTax/ram:CalculatedRate` |
| Line total (before discount) | `ram:LineTotalAmount` |
| Total discount | `ram:AllowanceTotalAmount` |
| Amount before VAT | `ram:TaxBasisTotalAmount` |
| VAT amount | `ram:TaxTotalAmount` |
| Grand total | `ram:GrandTotalAmount` |
| Line item product code | `ram:SpecifiedTradeProduct/ram:ID` |
| Line item description | `ram:SpecifiedTradeProduct/ram:Name` |
| Line item quantity | `ram:BilledQuantity` (unitCode attribute = unit) |
| Line item unit price | `ram:GrossPriceProductTradePrice/ram:ChargeAmount` |
| Line item net total | `ram:SpecifiedTradeSettlementLineMonetarySummation/ram:NetLineTotalAmount` |

Namespaces:
- `rsm` → `urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2`
- `ram` → `urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2`

`ds:Signature` is present in the signed XML and is silently ignored by the XSL template (no template match).

---

## Components

### 1. `ThaiAmountWordsConverter` (new)
**Location:** `infrastructure/adapter/out/pdf/ThaiAmountWordsConverter.java`

Static utility that converts a `BigDecimal` amount to Thai baht words in Thai script.

- `toWords(BigDecimal amount): String`
- Examples:
  - `64788.50` → `"หกหมื่นสี่พันเจ็ดร้อยแปดสิบแปดบาทห้าสิบสตางค์"`
  - `100.00` → `"หนึ่งร้อยบาทถ้วน"`
  - `0.00` → `"ศูนย์บาทถ้วน"`
- Throws `IllegalArgumentException` for negative or null input
- No Spring dependency — pure static utility, easy to unit test

### 2. `taxinvoice-direct.xsl` (new XSL-FO template)
**Location:** `src/main/resources/xsl/taxinvoice-direct.xsl`

Replaces `taxinvoice.xsl`. Transforms `rsm:TaxInvoice_CrossIndustryInvoice` XML directly to XSL-FO (A4 layout). Key changes from the old template:

- Declares `rsm:` and `ram:` namespace bindings at `<xsl:stylesheet>`
- Root template matches `/rsm:TaxInvoice_CrossIndustryInvoice`
- Declares `<xsl:param name="amountInWords"/>` at top level
- All data paths navigate namespace-qualified elements directly (e.g., `rsm:SupplyChainTradeTransaction/ram:ApplicableHeaderTradeSettlement/ram:SpecifiedTradeSettlementHeaderMonetarySummation/ram:GrandTotalAmount`)
- Iterates `ram:IncludedSupplyChainTradeLineItem` for line items
- Renders `$amountInWords` parameter in the totals section

### 3. `TaxInvoicePdfGenerationService` interface (updated)
**Location:** `domain/service/TaxInvoicePdfGenerationService.java`

Remove `taxInvoiceDataJson` parameter:

```java
// Before
byte[] generatePdf(String taxInvoiceNumber, String xmlContent, String taxInvoiceDataJson)

// After
byte[] generatePdf(String taxInvoiceNumber, String signedXml)
```

### 4. `TaxInvoicePdfGenerationServiceImpl` (updated)
**Location:** `infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImpl.java`

- Remove: `convertJsonToXml()`, `validateXmlWellFormedness()`, `ObjectMapper`, `SAXParserFactory` ThreadLocal, `XML_OUTPUT_FACTORY` ThreadLocal, `maxJsonSizeBytes` field
- Add: namespace-aware XPath extraction of `GrandTotalAmount` from `signedXml`
- Add: call `ThaiAmountWordsConverter.toWords(grandTotal)` → build `Map<String, Object> params`
- Call `fopPdfGenerator.generatePdf(signedXml, params)` directly
- XPath uses a thread-local `XPath` instance (same pattern as current ThreadLocals)

XPath expression for grand total:
```
/rsm:TaxInvoice_CrossIndustryInvoice
  /rsm:SupplyChainTradeTransaction
  /ram:ApplicableHeaderTradeSettlement
  /ram:SpecifiedTradeSettlementHeaderMonetarySummation
  /ram:GrandTotalAmount
```

### 5. `FopTaxInvoicePdfGenerator` (updated)
**Location:** `infrastructure/adapter/out/pdf/FopTaxInvoicePdfGenerator.java`

Add `Map<String, Object> params` argument to `generatePdf()`:

```java
// Before
public byte[] generatePdf(String xml)

// After
public byte[] generatePdf(String xml, Map<String, Object> params)
```

Before calling `transformer.transform()`, iterate `params` and call `transformer.setParameter(key, value)` for each entry.

### 6. `KafkaTaxInvoiceProcessCommand` (updated)
Remove `taxInvoiceDataJson` field, `@JsonProperty("taxInvoiceDataJson")`, and its getter. Keep `signedXmlUrl`, `documentId`, `documentNumber`.

### 7. `SagaCommandHandler` (updated)
Remove `command.getTaxInvoiceDataJson()` argument from the `generatePdf()` call.

### 8. Configuration (updated)
Remove property `app.taxinvoice.max-json-size-bytes` from `application.yml` and `CLAUDE.md`.

---

## Error Handling

| Scenario | Behaviour |
|----------|-----------|
| Signed XML missing `GrandTotalAmount` | `TaxInvoicePdfGenerationException` thrown before FOP is invoked |
| Malformed signed XML (XPath fails) | `TaxInvoicePdfGenerationException` wrapping the XPath exception |
| Negative/null grand total | `IllegalArgumentException` from converter, wrapped by service |
| `ds:Signature` elements in XML | Silently ignored by XSL (no template match) |
| FOP render failure | Unchanged — `PdfGenerationException` from `FopTaxInvoicePdfGenerator` |
| MinIO upload failure | Unchanged — existing orphan cleanup and retry paths |
| Circuit breaker open (XML fetch) | Unchanged — `CallNotPermittedException` path in `SagaCommandHandler` |

---

## Testing

### New tests
| Test class | What it covers |
|------------|----------------|
| `ThaiAmountWordsConverterTest` | Whole baht, satang, zero satang, large amounts, zero, negative/null guard |
| `FopTaxInvoicePdfGeneratorTest` (updated) | XSLT parameter is set on Transformer; existing semaphore/size/threading tests unchanged |

### Updated tests
| Test class | Change |
|------------|--------|
| `TaxInvoicePdfGenerationServiceImplTest` | Remove JSON test cases; add XPath extraction tests using `Example_TaxInvoice_2p1_v1.xml` |
| `SagaCommandHandlerTest` | Remove `taxInvoiceDataJson` from all command fixtures |
| `CamelRouteConfigTest` | Remove `taxInvoiceDataJson` from JSON serialization test payloads |
| `KafkaCommandMapperTest` | Remove `taxInvoiceDataJson` from deserialization test payloads |

### End-to-end XSL test
`FopTaxInvoicePdfGeneratorTest` uses `Example_TaxInvoice_2p1_v1.xml` as input with `amountInWords` XSLT parameter set. Runs under the test-mode FOP config (no Thai fonts required, no PDF/A mode).

---

## What Is Not Changed

- `RestTemplateSignedXmlFetcher` — unchanged; signed XML fetch logic is unaffected
- `MinioStorageAdapter` — unchanged
- `PdfA3Converter` — unchanged; receives `(basePdf, signedXml, filename, docNumber)` as before
- `SagaReplyPublisher`, `EventPublisher` — unchanged
- `TaxInvoicePdfDocument` domain model and state machine — unchanged
- `KafkaTaxInvoiceCompensateCommand` — unchanged
- Compensation flow — unchanged
- Outbox pattern — unchanged
- Metrics — unchanged
- Database schema — unchanged (no migration needed)
- `teda` library — NOT added as dependency; grand total extracted via XPath, not JAXB

---

## File Change Summary

| File | Action |
|------|--------|
| `KafkaTaxInvoiceProcessCommand.java` | Remove `taxInvoiceDataJson` |
| `TaxInvoicePdfGenerationService.java` | Remove `taxInvoiceDataJson` param |
| `TaxInvoicePdfGenerationServiceImpl.java` | Remove JSON path; add XPath + `ThaiAmountWordsConverter` |
| `FopTaxInvoicePdfGenerator.java` | Add `Map<String, Object> params` to `generatePdf()`; update `TAXINVOICE_XSL_PATH` constant to `"xsl/taxinvoice-direct.xsl"` |
| `SagaCommandHandler.java` | Remove `getTaxInvoiceDataJson()` call |
| `ThaiAmountWordsConverter.java` | **New** |
| `src/main/resources/xsl/taxinvoice-direct.xsl` | **New** (replaces `taxinvoice.xsl`) |
| `src/main/resources/xsl/taxinvoice.xsl` | **Delete** |
| `application.yml` | Remove `app.taxinvoice.max-json-size-bytes` |
| All affected test classes | Update fixtures (remove `taxInvoiceDataJson`) |
| `ThaiAmountWordsConverterTest.java` | **New** |
