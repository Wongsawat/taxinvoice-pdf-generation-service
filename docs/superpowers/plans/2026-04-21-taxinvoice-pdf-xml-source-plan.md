# Tax Invoice PDF from Signed XML Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `taxInvoiceDataJson` entirely and generate the PDF layout by passing the signed XML directly to Apache FOP with a new XSL-FO stylesheet that navigates Thai e-Tax namespaces (`rsm:`/`ram:`), computing `amountInWords` in Java and injecting it as an XSLT parameter.

**Architecture:** The signed XML (already fetched from MinIO via `signedXmlUrl`) is passed unmodified to FOP. A new XSL-FO template (`taxinvoice-direct.xsl`) matches `/rsm:TaxInvoice_CrossIndustryInvoice` and navigates RAM namespace elements directly. `GrandTotalAmount` is extracted via XPath before FOP runs; `ThaiAmountWordsConverter` converts it to Thai script and it is injected as an XSLT parameter named `amountInWords`.

**Tech Stack:** Java 21, Spring Boot 3.2.5, Apache FOP 2.9, XSLT 1.0, javax.xml.xpath, JUnit 5, Mockito, AssertJ

---

## File Map

| Action | File |
|--------|------|
| **Create** | `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverter.java` |
| **Create** | `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverterTest.java` |
| **Create** | `src/main/resources/xsl/taxinvoice-direct.xsl` |
| **Delete** | `src/main/resources/xsl/taxinvoice.xsl` |
| **Modify** | `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGenerator.java` |
| **Modify** | `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGeneratorTest.java` |
| **Modify** | `src/main/java/com/wpanther/taxinvoice/pdf/domain/service/TaxInvoicePdfGenerationService.java` |
| **Modify** | `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImpl.java` |
| **Modify** | `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImplTest.java` |
| **Modify** | `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java` |
| **Modify** | `src/main/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandler.java` |
| **Modify** | `src/test/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandlerTest.java` |
| **Modify** | `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfigTest.java` |
| **Modify** | `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java` |
| **Modify** | `src/main/resources/application.yml` |

---

## Task 1: ThaiAmountWordsConverter

**Files:**
- Create: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverter.java`
- Create: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverterTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverterTest.java`:

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ThaiAmountWordsConverter Unit Tests")
class ThaiAmountWordsConverterTest {

    @Test
    @DisplayName("Whole baht with zero satang appends 'บาทถ้วน'")
    void toWords_wholeBaht_appendsThuean() {
        assertThat(ThaiAmountWordsConverter.toWords(new BigDecimal("100.00")))
                .isEqualTo("หนึ่งร้อยบาทถ้วน");
    }

    @Test
    @DisplayName("Amount with satang appends 'สตางค์'")
    void toWords_withSatang_appendsSatang() {
        assertThat(ThaiAmountWordsConverter.toWords(new BigDecimal("64788.50")))
                .isEqualTo("หกหมื่นสี่พันเจ็ดร้อยแปดสิบแปดบาทห้าสิบสตางค์");
    }

    @Test
    @DisplayName("Zero amount → 'ศูนย์บาทถ้วน'")
    void toWords_zero_returnsZeroBaht() {
        assertThat(ThaiAmountWordsConverter.toWords(BigDecimal.ZERO))
                .isEqualTo("ศูนย์บาทถ้วน");
    }

    @Test
    @DisplayName("11 baht uses เอ็ด for ones digit")
    void toWords_elevenBaht_usesEd() {
        assertThat(ThaiAmountWordsConverter.toWords(new BigDecimal("11.00")))
                .isEqualTo("สิบเอ็ดบาทถ้วน");
    }

    @Test
    @DisplayName("21 baht uses ยี่สิบ for the tens")
    void toWords_twentyOne_usesYiSib() {
        assertThat(ThaiAmountWordsConverter.toWords(new BigDecimal("21.00")))
                .isEqualTo("ยี่สิบเอ็ดบาทถ้วน");
    }

    @Test
    @DisplayName("101 baht — ones digit 1 without tens uses หนึ่ง (not เอ็ด)")
    void toWords_oneHundredOne_onesIsNueng() {
        assertThat(ThaiAmountWordsConverter.toWords(new BigDecimal("101.00")))
                .isEqualTo("หนึ่งร้อยหนึ่งบาทถ้วน");
    }

    @Test
    @DisplayName("1,000,000 baht → หนึ่งล้านบาทถ้วน")
    void toWords_oneMillion_correct() {
        assertThat(ThaiAmountWordsConverter.toWords(new BigDecimal("1000000.00")))
                .isEqualTo("หนึ่งล้านบาทถ้วน");
    }

    @Test
    @DisplayName("10 satang only → digit word + สตางค์")
    void toWords_tenSatang_correct() {
        assertThat(ThaiAmountWordsConverter.toWords(new BigDecimal("1.10")))
                .isEqualTo("หนึ่งบาทสิบสตางค์");
    }

    @Test
    @DisplayName("Null amount throws IllegalArgumentException")
    void toWords_null_throwsIllegalArgument() {
        assertThatThrownBy(() -> ThaiAmountWordsConverter.toWords(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Negative amount throws IllegalArgumentException")
    void toWords_negative_throwsIllegalArgument() {
        assertThatThrownBy(() -> ThaiAmountWordsConverter.toWords(new BigDecimal("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/wpanther/projects/etax/invoice-microservices/services/taxinvoice-pdf-generation-service
mvn test -Dtest=ThaiAmountWordsConverterTest -pl . 2>&1 | tail -20
```
Expected: FAIL — `ThaiAmountWordsConverter` does not exist.

- [ ] **Step 3: Implement ThaiAmountWordsConverter**

Create `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverter.java`:

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ThaiAmountWordsConverter {

    private static final String[] DIGITS =
        {"ศูนย์", "หนึ่ง", "สอง", "สาม", "สี่", "ห้า", "หก", "เจ็ด", "แปด", "เก้า"};

    private ThaiAmountWordsConverter() {}

    public static String toWords(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be non-negative");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        long totalSatang = amount.movePointRight(2).longValue();
        long baht   = totalSatang / 100;
        long satang = totalSatang % 100;

        StringBuilder result = new StringBuilder(numberToThai(baht));
        if (satang == 0) {
            result.append("บาทถ้วน");
        } else {
            result.append("บาท");
            result.append(numberToThai(satang));
            result.append("สตางค์");
        }
        return result.toString();
    }

    private static String numberToThai(long n) {
        if (n == 0) return "ศูนย์";

        StringBuilder sb = new StringBuilder();

        if (n >= 1_000_000) {
            sb.append(numberToThai(n / 1_000_000));
            sb.append("ล้าน");
            n %= 1_000_000;
            if (n == 0) return sb.toString();
        }

        // hasTens: true when the sub-million portion has a non-zero tens digit.
        // Controls whether the ones digit 1 is rendered as เอ็ด instead of หนึ่ง.
        boolean hasTens = (n % 100) >= 10;

        int[] place   = {100_000, 10_000, 1_000, 100, 10, 1};
        String[] unit = {"แสน",   "หมื่น", "พัน", "ร้อย", "สิบ", ""};

        for (int i = 0; i < place.length; i++) {
            int digit = (int) (n / place[i]);
            n %= place[i];
            if (digit == 0) continue;

            if (i == 4) {                     // tens position
                if (digit == 1)      sb.append("สิบ");
                else if (digit == 2) sb.append("ยี่สิบ");
                else                 sb.append(DIGITS[digit]).append("สิบ");
            } else if (i == 5) {              // ones position
                if (digit == 1 && hasTens) sb.append("เอ็ด");
                else                        sb.append(DIGITS[digit]);
            } else {
                sb.append(DIGITS[digit]).append(unit[i]);
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=ThaiAmountWordsConverterTest -pl . 2>&1 | tail -10
```
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverter.java \
        src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/ThaiAmountWordsConverterTest.java
git commit -m "feat: add ThaiAmountWordsConverter utility"
```

---

## Task 2: New XSL Template + FopTaxInvoicePdfGenerator Parameters

**Files:**
- Create: `src/main/resources/xsl/taxinvoice-direct.xsl`
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGenerator.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGeneratorTest.java`

- [ ] **Step 1: Create the new XSL template**

Create `src/main/resources/xsl/taxinvoice-direct.xsl`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:fo="http://www.w3.org/1999/XSL/Format"
    xmlns:rsm="urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2"
    xmlns:ram="urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2">

    <xsl:output method="xml" indent="yes"/>

    <!-- Injected by Java: ThaiAmountWordsConverter output -->
    <xsl:param name="amountInWords"/>

    <!-- Page dimensions -->
    <xsl:variable name="page-width">210mm</xsl:variable>
    <xsl:variable name="page-height">297mm</xsl:variable>
    <xsl:variable name="margin">15mm</xsl:variable>
    <xsl:variable name="font-family">THSarabunNew, NotoSansThai, Helvetica, sans-serif</xsl:variable>
    <xsl:variable name="font-size">11pt</xsl:variable>
    <xsl:variable name="font-size-small">9pt</xsl:variable>
    <xsl:variable name="font-size-large">14pt</xsl:variable>
    <xsl:variable name="font-size-title">18pt</xsl:variable>

    <!-- Root template: match signed XML root element -->
    <xsl:template match="/rsm:TaxInvoice_CrossIndustryInvoice">
        <!-- Shorthand variables for deeply nested paths -->
        <xsl:variable name="doc"        select="rsm:ExchangedDocument"/>
        <xsl:variable name="txn"        select="rsm:SupplyChainTradeTransaction"/>
        <xsl:variable name="agreement"  select="$txn/ram:ApplicableHeaderTradeAgreement"/>
        <xsl:variable name="settlement" select="$txn/ram:ApplicableHeaderTradeSettlement"/>
        <xsl:variable name="summation"  select="$settlement/ram:SpecifiedTradeSettlementHeaderMonetarySummation"/>
        <xsl:variable name="seller"     select="$agreement/ram:SellerTradeParty"/>
        <xsl:variable name="buyer"      select="$agreement/ram:BuyerTradeParty"/>

        <fo:root>
            <fo:layout-master-set>
                <fo:simple-page-master master-name="taxinvoice-page"
                    page-width="{$page-width}" page-height="{$page-height}"
                    margin-top="{$margin}" margin-bottom="{$margin}"
                    margin-left="{$margin}" margin-right="{$margin}">
                    <fo:region-body margin-top="20mm" margin-bottom="20mm"/>
                    <fo:region-before extent="20mm"/>
                    <fo:region-after extent="20mm"/>
                </fo:simple-page-master>
            </fo:layout-master-set>

            <fo:page-sequence master-reference="taxinvoice-page">
                <!-- Header -->
                <fo:static-content flow-name="xsl-region-before">
                    <fo:block font-family="{$font-family}" font-size="{$font-size-small}"
                        color="#666666" border-bottom="0.5pt solid #cccccc" padding-bottom="2mm">
                        <fo:table width="100%" table-layout="fixed">
                            <fo:table-column column-width="50%"/>
                            <fo:table-column column-width="50%"/>
                            <fo:table-body>
                                <fo:table-row>
                                    <fo:table-cell>
                                        <fo:block text-align="left">
                                            <xsl:value-of select="$seller/ram:Name"/>
                                        </fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell>
                                        <fo:block text-align="right">
                                            e-Tax Tax Invoice / ใบเสร็จรับเงิน/ใบกำกับภาษีอิเล็กทรอนิกส์
                                        </fo:block>
                                    </fo:table-cell>
                                </fo:table-row>
                            </fo:table-body>
                        </fo:table>
                    </fo:block>
                </fo:static-content>

                <!-- Footer -->
                <fo:static-content flow-name="xsl-region-after">
                    <fo:block font-family="{$font-family}" font-size="{$font-size-small}"
                        color="#666666" border-top="0.5pt solid #cccccc" padding-top="2mm">
                        <fo:table width="100%" table-layout="fixed">
                            <fo:table-column column-width="33%"/>
                            <fo:table-column column-width="34%"/>
                            <fo:table-column column-width="33%"/>
                            <fo:table-body>
                                <fo:table-row>
                                    <fo:table-cell>
                                        <fo:block text-align="left">เอกสารนี้จัดทำด้วยระบบคอมพิวเตอร์</fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell>
                                        <fo:block text-align="center">
                                            หน้า <fo:page-number/> / <fo:page-number-citation ref-id="last-page"/>
                                        </fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell>
                                        <fo:block text-align="right">
                                            <xsl:value-of select="$doc/ram:ID"/>
                                        </fo:block>
                                    </fo:table-cell>
                                </fo:table-row>
                            </fo:table-body>
                        </fo:table>
                    </fo:block>
                </fo:static-content>

                <!-- Body -->
                <fo:flow flow-name="xsl-region-body">
                    <!-- Title -->
                    <fo:block font-family="{$font-family}" font-size="{$font-size-title}"
                        font-weight="bold" text-align="center" space-after="5mm" color="#333333">
                        ใบเสร็จรับเงิน / ใบกำกับภาษี / RECEIPT / TAX INVOICE
                    </fo:block>
                    <fo:block font-family="{$font-family}" font-size="{$font-size}"
                        text-align="center" space-after="10mm" color="#666666">
                        (ต้นฉบับ / Original)
                    </fo:block>

                    <!-- Parties -->
                    <fo:table width="100%" table-layout="fixed" space-after="8mm">
                        <fo:table-column column-width="50%"/>
                        <fo:table-column column-width="50%"/>
                        <fo:table-body>
                            <fo:table-row>
                                <!-- Seller -->
                                <fo:table-cell padding-right="5mm">
                                    <fo:block font-family="{$font-family}" font-size="{$font-size}"
                                        background-color="#f5f5f5" padding="3mm" border="0.5pt solid #dddddd">
                                        <fo:block font-weight="bold" space-after="2mm">ผู้ขาย / Seller</fo:block>
                                        <fo:block><xsl:value-of select="$seller/ram:Name"/></fo:block>
                                        <fo:block>
                                            เลขประจำตัวผู้เสียภาษี: <xsl:value-of select="$seller/ram:SpecifiedTaxRegistration/ram:ID"/>
                                        </fo:block>
                                        <xsl:if test="$seller/ram:PostalTradeAddress">
                                            <fo:block>
                                                <xsl:value-of select="$seller/ram:PostalTradeAddress/ram:BuildingNumber"/>
                                                <xsl:if test="$seller/ram:PostalTradeAddress/ram:StreetName">
                                                    <xsl:text> </xsl:text>
                                                    <xsl:value-of select="$seller/ram:PostalTradeAddress/ram:StreetName"/>
                                                </xsl:if>
                                                <xsl:if test="$seller/ram:PostalTradeAddress/ram:PostcodeCode">
                                                    <xsl:text> </xsl:text>
                                                    <xsl:value-of select="$seller/ram:PostalTradeAddress/ram:PostcodeCode"/>
                                                </xsl:if>
                                            </fo:block>
                                        </xsl:if>
                                    </fo:block>
                                </fo:table-cell>
                                <!-- Buyer -->
                                <fo:table-cell padding-left="5mm">
                                    <fo:block font-family="{$font-family}" font-size="{$font-size}"
                                        background-color="#f5f5f5" padding="3mm" border="0.5pt solid #dddddd">
                                        <fo:block font-weight="bold" space-after="2mm">ผู้ซื้อ / Buyer</fo:block>
                                        <fo:block><xsl:value-of select="$buyer/ram:Name"/></fo:block>
                                        <fo:block>
                                            เลขประจำตัวผู้เสียภาษี: <xsl:value-of select="$buyer/ram:SpecifiedTaxRegistration/ram:ID"/>
                                        </fo:block>
                                        <xsl:if test="$buyer/ram:PostalTradeAddress">
                                            <fo:block>
                                                <xsl:value-of select="$buyer/ram:PostalTradeAddress/ram:BuildingNumber"/>
                                                <xsl:if test="$buyer/ram:PostalTradeAddress/ram:StreetName">
                                                    <xsl:text> </xsl:text>
                                                    <xsl:value-of select="$buyer/ram:PostalTradeAddress/ram:StreetName"/>
                                                </xsl:if>
                                                <xsl:if test="$buyer/ram:PostalTradeAddress/ram:PostcodeCode">
                                                    <xsl:text> </xsl:text>
                                                    <xsl:value-of select="$buyer/ram:PostalTradeAddress/ram:PostcodeCode"/>
                                                </xsl:if>
                                            </fo:block>
                                        </xsl:if>
                                    </fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                        </fo:table-body>
                    </fo:table>

                    <!-- Invoice details -->
                    <fo:table width="100%" table-layout="fixed" space-after="8mm"
                        font-family="{$font-family}" font-size="{$font-size}">
                        <fo:table-column column-width="25%"/>
                        <fo:table-column column-width="25%"/>
                        <fo:table-column column-width="25%"/>
                        <fo:table-column column-width="25%"/>
                        <fo:table-body>
                            <fo:table-row>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#e8e8e8">
                                    <fo:block font-weight="bold">เลขที่เอกสาร</fo:block>
                                    <fo:block font-size="{$font-size-small}">Tax Invoice No.</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                    <fo:block><xsl:value-of select="$doc/ram:ID"/></fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#e8e8e8">
                                    <fo:block font-weight="bold">วันที่</fo:block>
                                    <fo:block font-size="{$font-size-small}">Date</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                    <fo:block>
                                        <xsl:value-of select="substring($doc/ram:IssueDateTime, 1, 10)"/>
                                    </fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                            <xsl:if test="$settlement/ram:SpecifiedTradePaymentTerms/ram:DueDateDateTime">
                                <fo:table-row>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#e8e8e8">
                                        <fo:block font-weight="bold">วันครบกำหนดชำระ</fo:block>
                                        <fo:block font-size="{$font-size-small}">Due Date</fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" number-columns-spanned="3">
                                        <fo:block>
                                            <xsl:value-of select="substring($settlement/ram:SpecifiedTradePaymentTerms/ram:DueDateDateTime, 1, 10)"/>
                                        </fo:block>
                                    </fo:table-cell>
                                </fo:table-row>
                            </xsl:if>
                        </fo:table-body>
                    </fo:table>

                    <!-- Line items -->
                    <fo:table width="100%" table-layout="fixed" space-after="5mm"
                        font-family="{$font-family}" font-size="{$font-size}">
                        <fo:table-column column-width="8%"/>
                        <fo:table-column column-width="37%"/>
                        <fo:table-column column-width="12%"/>
                        <fo:table-column column-width="10%"/>
                        <fo:table-column column-width="15%"/>
                        <fo:table-column column-width="18%"/>
                        <fo:table-header>
                            <fo:table-row background-color="#4a4a4a" color="white" font-weight="bold">
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                                    <fo:block text-align="center">ลำดับ</fo:block>
                                    <fo:block text-align="center" font-size="{$font-size-small}">No.</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                                    <fo:block>รายการ</fo:block>
                                    <fo:block font-size="{$font-size-small}">Description</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                                    <fo:block text-align="right">จำนวน</fo:block>
                                    <fo:block text-align="right" font-size="{$font-size-small}">Qty</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                                    <fo:block text-align="center">หน่วย</fo:block>
                                    <fo:block text-align="center" font-size="{$font-size-small}">Unit</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                                    <fo:block text-align="right">ราคา/หน่วย</fo:block>
                                    <fo:block text-align="right" font-size="{$font-size-small}">Unit Price</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333">
                                    <fo:block text-align="right">จำนวนเงิน</fo:block>
                                    <fo:block text-align="right" font-size="{$font-size-small}">Amount</fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                        </fo:table-header>
                        <fo:table-body>
                            <xsl:for-each select="$txn/ram:IncludedSupplyChainTradeLineItem">
                                <fo:table-row>
                                    <xsl:attribute name="background-color">
                                        <xsl:choose>
                                            <xsl:when test="position() mod 2 = 0">#f9f9f9</xsl:when>
                                            <xsl:otherwise>white</xsl:otherwise>
                                        </xsl:choose>
                                    </xsl:attribute>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                        <fo:block text-align="center">
                                            <xsl:value-of select="ram:AssociatedDocumentLineDocument/ram:LineID"/>
                                        </fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                        <fo:block><xsl:value-of select="ram:SpecifiedTradeProduct/ram:Name"/></fo:block>
                                        <xsl:if test="ram:SpecifiedTradeProduct/ram:ID">
                                            <fo:block font-size="{$font-size-small}" color="#666666">
                                                รหัส: <xsl:value-of select="ram:SpecifiedTradeProduct/ram:ID"/>
                                            </fo:block>
                                        </xsl:if>
                                    </fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                        <fo:block text-align="right">
                                            <xsl:value-of select="format-number(number(ram:SpecifiedLineTradeDelivery/ram:BilledQuantity), '#,##0.##')"/>
                                        </fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                        <fo:block text-align="center">
                                            <xsl:value-of select="ram:SpecifiedLineTradeDelivery/ram:BilledQuantity/@unitCode"/>
                                        </fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                        <fo:block text-align="right">
                                            <xsl:value-of select="format-number(number(ram:SpecifiedLineTradeAgreement/ram:GrossPriceProductTradePrice/ram:ChargeAmount), '#,##0.00')"/>
                                        </fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                        <fo:block text-align="right">
                                            <xsl:value-of select="format-number(number(ram:SpecifiedLineTradeSettlement/ram:SpecifiedTradeSettlementLineMonetarySummation/ram:NetLineTotalAmount), '#,##0.00')"/>
                                        </fo:block>
                                    </fo:table-cell>
                                </fo:table-row>
                            </xsl:for-each>
                        </fo:table-body>
                    </fo:table>

                    <!-- Totals -->
                    <fo:table width="100%" table-layout="fixed" space-after="8mm"
                        font-family="{$font-family}" font-size="{$font-size}">
                        <fo:table-column column-width="60%"/>
                        <fo:table-column column-width="22%"/>
                        <fo:table-column column-width="18%"/>
                        <fo:table-body>
                            <fo:table-row>
                                <fo:table-cell><fo:block/></fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                                    <fo:block text-align="right">รวมเงิน / Subtotal</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                    <fo:block text-align="right">
                                        <xsl:value-of select="format-number(number($summation/ram:LineTotalAmount), '#,##0.00')"/>
                                    </fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                            <xsl:if test="number($summation/ram:AllowanceTotalAmount) != 0">
                                <fo:table-row>
                                    <fo:table-cell><fo:block/></fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                                        <fo:block text-align="right">ส่วนลด / Discount</fo:block>
                                    </fo:table-cell>
                                    <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                        <fo:block text-align="right" color="red">
                                            -<xsl:value-of select="format-number(number($summation/ram:AllowanceTotalAmount), '#,##0.00')"/>
                                        </fo:block>
                                    </fo:table-cell>
                                </fo:table-row>
                            </xsl:if>
                            <fo:table-row>
                                <fo:table-cell><fo:block/></fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                                    <fo:block text-align="right">มูลค่าก่อนภาษี / Amount before VAT</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                    <fo:block text-align="right">
                                        <xsl:value-of select="format-number(number($summation/ram:TaxBasisTotalAmount), '#,##0.00')"/>
                                    </fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                            <fo:table-row>
                                <fo:table-cell><fo:block/></fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd" background-color="#f5f5f5">
                                    <fo:block text-align="right">
                                        ภาษีมูลค่าเพิ่ม <xsl:value-of select="$settlement/ram:ApplicableTradeTax/ram:CalculatedRate"/>% / VAT
                                    </fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="2mm" border="0.5pt solid #dddddd">
                                    <fo:block text-align="right">
                                        <xsl:value-of select="format-number(number($summation/ram:TaxTotalAmount), '#,##0.00')"/>
                                    </fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                            <fo:table-row font-weight="bold" font-size="{$font-size-large}">
                                <fo:table-cell><fo:block/></fo:table-cell>
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333"
                                    background-color="#4a4a4a" color="white">
                                    <fo:block text-align="right">ยอดรวมทั้งสิ้น / Grand Total</fo:block>
                                </fo:table-cell>
                                <fo:table-cell padding="3mm" border="0.5pt solid #333333"
                                    background-color="#f0f0f0">
                                    <fo:block text-align="right">
                                        <xsl:value-of select="format-number(number($summation/ram:GrandTotalAmount), '#,##0.00')"/>
                                    </fo:block>
                                </fo:table-cell>
                            </fo:table-row>
                        </fo:table-body>
                    </fo:table>

                    <!-- Amount in words (XSLT parameter) -->
                    <xsl:if test="$amountInWords != ''">
                        <fo:block font-family="{$font-family}" font-size="{$font-size}" space-after="5mm"
                            padding="3mm" background-color="#fffde7" border="0.5pt solid #ffc107">
                            <fo:inline font-weight="bold">จำนวนเงินเป็นตัวอักษร: </fo:inline>
                            <xsl:value-of select="$amountInWords"/>
                        </fo:block>
                    </xsl:if>

                    <!-- Notes -->
                    <xsl:if test="$doc/ram:IncludedNote/ram:Subject">
                        <fo:block font-family="{$font-family}" font-size="{$font-size-small}"
                            space-after="5mm" padding="3mm"
                            background-color="#f5f5f5" border="0.5pt solid #dddddd">
                            <fo:block font-weight="bold" space-after="2mm">หมายเหตุ / Notes</fo:block>
                            <fo:block><xsl:value-of select="$doc/ram:IncludedNote/ram:Subject"/></fo:block>
                        </fo:block>
                    </xsl:if>

                    <!-- End marker for page counting -->
                    <fo:block id="last-page"/>
                </fo:flow>
            </fo:page-sequence>
        </fo:root>
    </xsl:template>

</xsl:stylesheet>
```

- [ ] **Step 2: Update FopTaxInvoicePdfGenerator — change XSL path and add params overload**

In `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGenerator.java`:

Change the constant (line 50):
```java
// Before
private static final String TAXINVOICE_XSL_PATH = "xsl/taxinvoice.xsl";

// After
private static final String TAXINVOICE_XSL_PATH = "xsl/taxinvoice-direct.xsl";
```

Add the import at the top of the file (after existing imports):
```java
import java.util.Map;
```

Replace the existing `generatePdf(String xmlData)` method and add the new overload. The full replacement for both methods (lines 172–232):

```java
/**
 * Generate PDF from signed XML data using the tax invoice XSL-FO template.
 * Sets each entry in {@code params} as an XSLT parameter on the transformer
 * before rendering. Use {@code Map.of("amountInWords", "...")} to supply the
 * Thai baht words value.
 *
 * @param xmlData The signed XML (rsm:TaxInvoice_CrossIndustryInvoice)
 * @param params  XSLT parameters to set on the transformer (may be null)
 * @return PDF bytes
 * @throws PdfGenerationException if generation fails
 */
@NewSpan("pdf.fop.render")
public byte[] generatePdf(String xmlData, Map<String, Object> params) throws PdfGenerationException {
    log.debug("Awaiting render permit (available={})", renderSemaphore.availablePermits());
    try {
        renderSemaphore.acquire();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new PdfGenerationException("PDF generation interrupted while waiting for render slot", e);
    }
    long t0 = System.nanoTime();
    try {
        log.debug("Generating PDF with cached template: {}", TAXINVOICE_XSL_PATH);
        Transformer transformer = cachedTemplates.newTransformer();
        if (params != null) {
            params.forEach(transformer::setParameter);
        }
        return renderPdf(xmlData, transformer);
    } catch (javax.xml.transform.TransformerConfigurationException e) {
        throw new PdfGenerationException("Failed to create transformer from cached templates: " + e.getMessage(), e);
    } finally {
        try {
            renderTimer.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
        } finally {
            renderSemaphore.release();
        }
    }
}

/**
 * Convenience overload — delegates to {@link #generatePdf(String, Map)} with no params.
 */
public byte[] generatePdf(String xmlData) throws PdfGenerationException {
    return generatePdf(xmlData, null);
}
```

- [ ] **Step 3: Write updated FopTaxInvoicePdfGeneratorTest**

Replace the entire content of `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGeneratorTest.java`:

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FopTaxInvoicePdfGenerator Unit Tests")
class FopTaxInvoicePdfGeneratorTest {

    // Minimal signed XML accepted by taxinvoice-direct.xsl
    private static final String MINIMAL_SIGNED_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<rsm:TaxInvoice_CrossIndustryInvoice " +
        "    xmlns:ram=\"urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2\"" +
        "    xmlns:rsm=\"urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2\">" +
        "  <rsm:ExchangedDocument>" +
        "    <ram:ID>TINV-TEST-001</ram:ID>" +
        "    <ram:Name>ใบกำกับภาษี</ram:Name>" +
        "    <ram:IssueDateTime>2024-01-15T00:00:00.0</ram:IssueDateTime>" +
        "  </rsm:ExchangedDocument>" +
        "  <rsm:SupplyChainTradeTransaction>" +
        "    <ram:ApplicableHeaderTradeAgreement>" +
        "      <ram:SellerTradeParty>" +
        "        <ram:Name>บริษัท ทดสอบ จำกัด</ram:Name>" +
        "        <ram:SpecifiedTaxRegistration><ram:ID>1234567890123</ram:ID></ram:SpecifiedTaxRegistration>" +
        "      </ram:SellerTradeParty>" +
        "      <ram:BuyerTradeParty>" +
        "        <ram:Name>ผู้ซื้อ</ram:Name>" +
        "        <ram:SpecifiedTaxRegistration><ram:ID>9876543210987</ram:ID></ram:SpecifiedTaxRegistration>" +
        "      </ram:BuyerTradeParty>" +
        "    </ram:ApplicableHeaderTradeAgreement>" +
        "    <ram:ApplicableHeaderTradeDelivery/>" +
        "    <ram:ApplicableHeaderTradeSettlement>" +
        "      <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>" +
        "      <ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CalculatedRate>7</ram:CalculatedRate></ram:ApplicableTradeTax>" +
        "      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
        "        <ram:LineTotalAmount>1000</ram:LineTotalAmount>" +
        "        <ram:AllowanceTotalAmount>0</ram:AllowanceTotalAmount>" +
        "        <ram:TaxBasisTotalAmount>1000</ram:TaxBasisTotalAmount>" +
        "        <ram:TaxTotalAmount>70</ram:TaxTotalAmount>" +
        "        <ram:GrandTotalAmount>1070</ram:GrandTotalAmount>" +
        "      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
        "    </ram:ApplicableHeaderTradeSettlement>" +
        "    <ram:IncludedSupplyChainTradeLineItem>" +
        "      <ram:AssociatedDocumentLineDocument><ram:LineID>1</ram:LineID></ram:AssociatedDocumentLineDocument>" +
        "      <ram:SpecifiedTradeProduct><ram:ID>P001</ram:ID><ram:Name>สินค้าทดสอบ</ram:Name></ram:SpecifiedTradeProduct>" +
        "      <ram:SpecifiedLineTradeAgreement>" +
        "        <ram:GrossPriceProductTradePrice><ram:ChargeAmount>1000</ram:ChargeAmount></ram:GrossPriceProductTradePrice>" +
        "      </ram:SpecifiedLineTradeAgreement>" +
        "      <ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode=\"PIECE\">1</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>" +
        "      <ram:SpecifiedLineTradeSettlement>" +
        "        <ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CalculatedRate>7</ram:CalculatedRate></ram:ApplicableTradeTax>" +
        "        <ram:SpecifiedTradeSettlementLineMonetarySummation><ram:NetLineTotalAmount>1000</ram:NetLineTotalAmount></ram:SpecifiedTradeSettlementLineMonetarySummation>" +
        "      </ram:SpecifiedLineTradeSettlement>" +
        "    </ram:IncludedSupplyChainTradeLineItem>" +
        "  </rsm:SupplyChainTradeTransaction>" +
        "</rsm:TaxInvoice_CrossIndustryInvoice>";

    @Test
    @DisplayName("Constructor succeeds and compiles XSL template")
    void constructor_compilesTemplateSuccessfully() {
        assertThatCode(() -> new FopTaxInvoicePdfGenerator(2, 52428800L, new SimpleMeterRegistry()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Constructor rejects maxConcurrentRenders < 1")
    void constructor_invalidMaxConcurrentRenders_throwsIllegalStateException() {
        assertThatThrownBy(() -> new FopTaxInvoicePdfGenerator(0, 52428800L, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-concurrent-renders");
    }

    @Test
    @DisplayName("Constructor rejects maxPdfSizeBytes < 1")
    void constructor_invalidMaxPdfSizeBytes_throwsIllegalStateException() {
        assertThatThrownBy(() -> new FopTaxInvoicePdfGenerator(1, 0L, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-pdf-size-bytes");
    }

    @Test
    @DisplayName("Semaphore is initialised with the configured permit count")
    void constructor_semaphorePermitsMatchConfiguration() throws Exception {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(5, 52428800L, new SimpleMeterRegistry());
        Field f = FopTaxInvoicePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore s = (Semaphore) f.get(gen);
        assertThat(s.availablePermits()).isEqualTo(5);
        assertThat(s.isFair()).isTrue();
    }

    @Test
    @DisplayName("checkFontAvailability() does not throw")
    void checkFontAvailability_doesNotThrow() {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        assertThatCode(() -> gen.checkFontAvailability()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("PdfGenerationException 1-arg constructor carries message")
    void pdfGenerationException_messageOnlyConstructor_hasMessage() {
        var ex = new FopTaxInvoicePdfGenerator.PdfGenerationException("FOP failed");
        assertThat(ex.getMessage()).isEqualTo("FOP failed");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("generatePdf(xml) on interrupted thread throws PdfGenerationException")
    void generatePdf_threadAlreadyInterrupted_throwsPdfGenerationException() {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> gen.generatePdf(MINIMAL_SIGNED_XML))
                    .isInstanceOf(FopTaxInvoicePdfGenerator.PdfGenerationException.class)
                    .hasMessageContaining("interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("Semaphore blocks callers when all permits are held")
    void generatePdf_semaphoreBlocksWhenAtCapacity() throws Exception {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        Field f = FopTaxInvoicePdfGenerator.class.getDeclaredField("renderSemaphore");
        f.setAccessible(true);
        Semaphore sem = (Semaphore) f.get(gen);
        sem.acquire();

        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = exec.submit(() -> {
                try { gen.generatePdf(MINIMAL_SIGNED_XML); }
                catch (FopTaxInvoicePdfGenerator.PdfGenerationException ignored) {}
            });
            assertThatThrownBy(() -> future.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            sem.release();
            future.get(5, TimeUnit.SECONDS);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    @DisplayName("resolveBaseUri() returns a non-null absolute URI ending with '/'")
    void resolveBaseUri_returnsValidUri() throws Exception {
        FopTaxInvoicePdfGenerator generator = new FopTaxInvoicePdfGenerator(2, 52428800L, new SimpleMeterRegistry());
        Method method = FopTaxInvoicePdfGenerator.class.getDeclaredMethod("resolveBaseUri");
        method.setAccessible(true);
        URI uri = (URI) method.invoke(generator);
        assertThat(uri).isNotNull();
        assertThat(uri.isAbsolute()).isTrue();
        assertThat(uri.toString()).endsWith("/");
    }

    @Test
    @DisplayName("Valid signed XML → returns non-empty PDF bytes starting with %PDF")
    void generatePdf_validSignedXml_returnsPdfBytes() throws Exception {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());

        byte[] result = gen.generatePdf(MINIMAL_SIGNED_XML, Map.of("amountInWords", "หนึ่งพันเจ็ดสิบบาทถ้วน"));

        assertThat(result).isNotEmpty();
        assertThat(new String(result, 0, 4, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
    }

    @Test
    @DisplayName("generatePdf(xml) no-arg overload delegates successfully")
    void generatePdf_noParams_delegatesToParamsOverload() throws Exception {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        byte[] result = gen.generatePdf(MINIMAL_SIGNED_XML);
        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("Malformed XML → PdfGenerationException")
    void generatePdf_malformedXml_throwsPdfGenerationException() {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 52428800L, new SimpleMeterRegistry());
        assertThatThrownBy(() -> gen.generatePdf("this is not xml <<<"))
                .isInstanceOf(FopTaxInvoicePdfGenerator.PdfGenerationException.class);
    }

    @Test
    @DisplayName("PDF exceeding max size → PdfGenerationException")
    void generatePdf_pdfExceedsMaxSize_throwsPdfGenerationException() {
        FopTaxInvoicePdfGenerator gen = new FopTaxInvoicePdfGenerator(1, 1L, new SimpleMeterRegistry());
        assertThatThrownBy(() -> gen.generatePdf(MINIMAL_SIGNED_XML, null))
                .isInstanceOf(FopTaxInvoicePdfGenerator.PdfGenerationException.class)
                .hasMessageContaining("exceeds max allowed size");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest=FopTaxInvoicePdfGeneratorTest -pl . 2>&1 | tail -15
```
Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/xsl/taxinvoice-direct.xsl \
        src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGenerator.java \
        src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/FopTaxInvoicePdfGeneratorTest.java
git commit -m "feat: add taxinvoice-direct.xsl and params support in FopTaxInvoicePdfGenerator"
```

---

## Task 3: Service Interface + Impl Rewrite

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/domain/service/TaxInvoicePdfGenerationService.java`
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImpl.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImplTest.java`

- [ ] **Step 1: Update the domain service interface**

Replace the entire content of `src/main/java/com/wpanther/taxinvoice/pdf/domain/service/TaxInvoicePdfGenerationService.java`:

```java
package com.wpanther.taxinvoice.pdf.domain.service;

public interface TaxInvoicePdfGenerationService {

    /**
     * Generate PDF/A-3 from the signed XML document.
     *
     * @param taxInvoiceNumber document number (used for logging and file naming)
     * @param signedXml        full Thai e-Tax signed XML (rsm:TaxInvoice_CrossIndustryInvoice)
     * @return PDF/A-3 bytes with the signed XML embedded as an attachment
     * @throws TaxInvoicePdfGenerationException if generation fails
     */
    byte[] generatePdf(String taxInvoiceNumber, String signedXml)
        throws TaxInvoicePdfGenerationException;

    class TaxInvoicePdfGenerationException extends Exception {
        public TaxInvoicePdfGenerationException(String message) { super(message); }
        public TaxInvoicePdfGenerationException(String message, Throwable cause) { super(message, cause); }
    }
}
```

- [ ] **Step 2: Write the updated failing tests**

Replace the entire content of `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImplTest.java`:

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaxInvoicePdfGenerationServiceImpl Unit Tests")
class TaxInvoicePdfGenerationServiceImplTest {

    @Mock private FopTaxInvoicePdfGenerator fopPdfGenerator;
    @Mock private PdfA3Converter pdfA3Converter;

    private TaxInvoicePdfGenerationServiceImpl service;

    private static final String DOC_NUMBER = "TXINV-2024-001";

    // Minimal signed XML with a GrandTotalAmount of 1070
    private static final String SIGNED_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<rsm:TaxInvoice_CrossIndustryInvoice " +
        "    xmlns:ram=\"urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2\"" +
        "    xmlns:rsm=\"urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2\">" +
        "  <rsm:ExchangedDocument><ram:ID>TXINV-2024-001</ram:ID><ram:IssueDateTime>2024-01-15T00:00:00.0</ram:IssueDateTime></rsm:ExchangedDocument>" +
        "  <rsm:SupplyChainTradeTransaction>" +
        "    <ram:ApplicableHeaderTradeAgreement>" +
        "      <ram:SellerTradeParty><ram:Name>Seller</ram:Name><ram:SpecifiedTaxRegistration><ram:ID>1111111111111</ram:ID></ram:SpecifiedTaxRegistration></ram:SellerTradeParty>" +
        "      <ram:BuyerTradeParty><ram:Name>Buyer</ram:Name><ram:SpecifiedTaxRegistration><ram:ID>2222222222222</ram:ID></ram:SpecifiedTaxRegistration></ram:BuyerTradeParty>" +
        "    </ram:ApplicableHeaderTradeAgreement>" +
        "    <ram:ApplicableHeaderTradeDelivery/>" +
        "    <ram:ApplicableHeaderTradeSettlement>" +
        "      <ram:InvoiceCurrencyCode>THB</ram:InvoiceCurrencyCode>" +
        "      <ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode><ram:CalculatedRate>7</ram:CalculatedRate></ram:ApplicableTradeTax>" +
        "      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
        "        <ram:LineTotalAmount>1000</ram:LineTotalAmount>" +
        "        <ram:AllowanceTotalAmount>0</ram:AllowanceTotalAmount>" +
        "        <ram:TaxBasisTotalAmount>1000</ram:TaxBasisTotalAmount>" +
        "        <ram:TaxTotalAmount>70</ram:TaxTotalAmount>" +
        "        <ram:GrandTotalAmount>1070</ram:GrandTotalAmount>" +
        "      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>" +
        "    </ram:ApplicableHeaderTradeSettlement>" +
        "    <ram:IncludedSupplyChainTradeLineItem>" +
        "      <ram:AssociatedDocumentLineDocument><ram:LineID>1</ram:LineID></ram:AssociatedDocumentLineDocument>" +
        "      <ram:SpecifiedTradeProduct><ram:Name>Item</ram:Name></ram:SpecifiedTradeProduct>" +
        "      <ram:SpecifiedLineTradeAgreement><ram:GrossPriceProductTradePrice><ram:ChargeAmount>1000</ram:ChargeAmount></ram:GrossPriceProductTradePrice></ram:SpecifiedLineTradeAgreement>" +
        "      <ram:SpecifiedLineTradeDelivery><ram:BilledQuantity unitCode=\"EA\">1</ram:BilledQuantity></ram:SpecifiedLineTradeDelivery>" +
        "      <ram:SpecifiedLineTradeSettlement><ram:ApplicableTradeTax><ram:TypeCode>VAT</ram:TypeCode></ram:ApplicableTradeTax>" +
        "        <ram:SpecifiedTradeSettlementLineMonetarySummation><ram:NetLineTotalAmount>1000</ram:NetLineTotalAmount></ram:SpecifiedTradeSettlementLineMonetarySummation>" +
        "      </ram:SpecifiedLineTradeSettlement>" +
        "    </ram:IncludedSupplyChainTradeLineItem>" +
        "  </rsm:SupplyChainTradeTransaction>" +
        "</rsm:TaxInvoice_CrossIndustryInvoice>";

    private static final String SIGNED_XML_NO_GRAND_TOTAL =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<rsm:TaxInvoice_CrossIndustryInvoice " +
        "    xmlns:ram=\"urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2\"" +
        "    xmlns:rsm=\"urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2\">" +
        "  <rsm:ExchangedDocument><ram:ID>X</ram:ID></rsm:ExchangedDocument>" +
        "  <rsm:SupplyChainTradeTransaction>" +
        "    <ram:ApplicableHeaderTradeSettlement>" +
        "      <ram:SpecifiedTradeSettlementHeaderMonetarySummation/>" +
        "    </ram:ApplicableHeaderTradeSettlement>" +
        "  </rsm:SupplyChainTradeTransaction>" +
        "</rsm:TaxInvoice_CrossIndustryInvoice>";

    @BeforeEach
    void setUp() {
        service = new TaxInvoicePdfGenerationServiceImpl(fopPdfGenerator, pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() throws when signedXml is null")
    void generatePdf_nullSignedXml_throws() {
        assertThatThrownBy(() -> service.generatePdf(DOC_NUMBER, null))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("signedXml is null or blank");
        verifyNoInteractions(fopPdfGenerator, pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() throws when signedXml is blank")
    void generatePdf_blankSignedXml_throws() {
        assertThatThrownBy(() -> service.generatePdf(DOC_NUMBER, "   "))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("signedXml is null or blank");
        verifyNoInteractions(fopPdfGenerator, pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() throws when GrandTotalAmount is missing")
    void generatePdf_missingGrandTotal_throws() {
        assertThatThrownBy(() -> service.generatePdf(DOC_NUMBER, SIGNED_XML_NO_GRAND_TOTAL))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("GrandTotalAmount");
        verifyNoInteractions(fopPdfGenerator, pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() extracts grand total and passes amountInWords param to FOP")
    void generatePdf_success_passesAmountInWordsToFop() throws Exception {
        byte[] basePdf = new byte[2000];
        byte[] pdfA3   = new byte[3000];
        when(fopPdfGenerator.generatePdf(eq(SIGNED_XML), any())).thenReturn(basePdf);
        when(pdfA3Converter.convertToPdfA3(eq(basePdf), eq(SIGNED_XML), anyString(), eq(DOC_NUMBER)))
                .thenReturn(pdfA3);

        byte[] result = service.generatePdf(DOC_NUMBER, SIGNED_XML);

        assertThat(result).isSameAs(pdfA3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fopPdfGenerator).generatePdf(eq(SIGNED_XML), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsKey("amountInWords");
        // 1070.00 = หนึ่งพันเจ็ดสิบบาทถ้วน
        assertThat(paramsCaptor.getValue().get("amountInWords"))
                .isEqualTo("หนึ่งพันเจ็ดสิบบาทถ้วน");
    }

    @Test
    @DisplayName("generatePdf() passes signed XML unmodified to PdfA3Converter for embedding")
    void generatePdf_success_embedsSignedXmlUnmodified() throws Exception {
        when(fopPdfGenerator.generatePdf(any(), any())).thenReturn(new byte[100]);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(new byte[200]);

        service.generatePdf(DOC_NUMBER, SIGNED_XML);

        verify(pdfA3Converter).convertToPdfA3(any(), eq(SIGNED_XML),
                eq("taxinvoice-" + DOC_NUMBER + ".xml"), eq(DOC_NUMBER));
    }

    @Test
    @DisplayName("generatePdf() wraps FopPdfGenerationException")
    void generatePdf_fopFails_wrapsException() throws Exception {
        when(fopPdfGenerator.generatePdf(any(), any()))
                .thenThrow(new FopTaxInvoicePdfGenerator.PdfGenerationException("XSL failed"));

        assertThatThrownBy(() -> service.generatePdf(DOC_NUMBER, SIGNED_XML))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("PDF generation failed");
        verifyNoInteractions(pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() wraps PdfConversionException")
    void generatePdf_pdfA3Fails_wrapsException() throws Exception {
        when(fopPdfGenerator.generatePdf(any(), any())).thenReturn(new byte[100]);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any()))
                .thenThrow(new PdfA3Converter.PdfConversionException("ICC missing"));

        assertThatThrownBy(() -> service.generatePdf(DOC_NUMBER, SIGNED_XML))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("PDF/A-3 conversion failed");
    }
}
```

- [ ] **Step 3: Run tests to verify they fail** (impl still has old signature)

```bash
mvn test -Dtest=TaxInvoicePdfGenerationServiceImplTest -pl . 2>&1 | tail -15
```
Expected: FAIL — compilation errors or `generatePdf` signature mismatch.

- [ ] **Step 4: Rewrite TaxInvoicePdfGenerationServiceImpl**

Replace the entire content of `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImpl.java`:

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

@Service
@Slf4j
public class TaxInvoicePdfGenerationServiceImpl implements TaxInvoicePdfGenerationService {

    private static final String RSM_NS =
        "urn:etda:uncefact:data:standard:TaxInvoice_CrossIndustryInvoice:2";
    private static final String RAM_NS =
        "urn:etda:uncefact:data:standard:TaxInvoice_ReusableAggregateBusinessInformationEntity:2";
    private static final String GRAND_TOTAL_XPATH =
        "/rsm:TaxInvoice_CrossIndustryInvoice" +
        "/rsm:SupplyChainTradeTransaction" +
        "/ram:ApplicableHeaderTradeSettlement" +
        "/ram:SpecifiedTradeSettlementHeaderMonetarySummation" +
        "/ram:GrandTotalAmount";

    private static final NamespaceContext NS_CONTEXT = new NamespaceContext() {
        @Override
        public String getNamespaceURI(String prefix) {
            return switch (prefix) {
                case "rsm" -> RSM_NS;
                case "ram" -> RAM_NS;
                default    -> XMLConstants.NULL_NS_URI;
            };
        }
        @Override public String getPrefix(String ns) { return null; }
        @Override public Iterator<String> getPrefixes(String ns) { return Collections.emptyIterator(); }
    };

    private final FopTaxInvoicePdfGenerator fopPdfGenerator;
    private final PdfA3Converter pdfA3Converter;

    public TaxInvoicePdfGenerationServiceImpl(FopTaxInvoicePdfGenerator fopPdfGenerator,
                                               PdfA3Converter pdfA3Converter) {
        this.fopPdfGenerator = fopPdfGenerator;
        this.pdfA3Converter  = pdfA3Converter;
    }

    @Override
    public byte[] generatePdf(String taxInvoiceNumber, String signedXml)
            throws TaxInvoicePdfGenerationException {

        log.info("Starting PDF generation for tax invoice: {}", taxInvoiceNumber);

        if (signedXml == null || signedXml.isBlank()) {
            throw new TaxInvoicePdfGenerationException(
                "signedXml is null or blank for tax invoice: " + taxInvoiceNumber);
        }

        try {
            BigDecimal grandTotal  = extractGrandTotal(signedXml, taxInvoiceNumber);
            String amountInWords   = ThaiAmountWordsConverter.toWords(grandTotal);
            log.debug("Grand total {} → amountInWords: {}", grandTotal, amountInWords);

            Map<String, Object> params = Map.of("amountInWords", amountInWords);
            byte[] basePdf = fopPdfGenerator.generatePdf(signedXml, params);
            log.debug("Generated base PDF: {} bytes", basePdf.length);

            String xmlFilename = "taxinvoice-" + taxInvoiceNumber + ".xml";
            byte[] pdfA3 = pdfA3Converter.convertToPdfA3(basePdf, signedXml, xmlFilename, taxInvoiceNumber);
            log.info("Generated PDF/A-3 for tax invoice {}: {} bytes", taxInvoiceNumber, pdfA3.length);
            return pdfA3;

        } catch (FopTaxInvoicePdfGenerator.PdfGenerationException e) {
            log.error("FOP PDF generation failed for tax invoice: {}", taxInvoiceNumber, e);
            throw new TaxInvoicePdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        } catch (PdfA3Converter.PdfConversionException e) {
            log.error("PDF/A-3 conversion failed for tax invoice: {}", taxInvoiceNumber, e);
            throw new TaxInvoicePdfGenerationException("PDF/A-3 conversion failed: " + e.getMessage(), e);
        } catch (TaxInvoicePdfGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during PDF generation for tax invoice: {}", taxInvoiceNumber, e);
            throw new TaxInvoicePdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    private BigDecimal extractGrandTotal(String signedXml, String taxInvoiceNumber)
            throws TaxInvoicePdfGenerationException {
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(NS_CONTEXT);
            String value = (String) xpath.evaluate(
                GRAND_TOTAL_XPATH,
                new InputSource(new StringReader(signedXml)),
                XPathConstants.STRING);
            if (value == null || value.isBlank()) {
                throw new TaxInvoicePdfGenerationException(
                    "GrandTotalAmount not found in signed XML for tax invoice: " + taxInvoiceNumber);
            }
            return new BigDecimal(value.trim());
        } catch (XPathExpressionException e) {
            throw new TaxInvoicePdfGenerationException(
                "Failed to extract GrandTotalAmount from signed XML: " + e.getMessage(), e);
        } catch (NumberFormatException e) {
            throw new TaxInvoicePdfGenerationException(
                "Invalid GrandTotalAmount in signed XML for tax invoice " + taxInvoiceNumber + ": " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
mvn test -Dtest=TaxInvoicePdfGenerationServiceImplTest -pl . 2>&1 | tail -10
```
Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/domain/service/TaxInvoicePdfGenerationService.java \
        src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImpl.java \
        src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/out/pdf/TaxInvoicePdfGenerationServiceImplTest.java
git commit -m "feat: generate PDF from signed XML — remove taxInvoiceDataJson from service"
```

---

## Task 4: Remove taxInvoiceDataJson from Command + Handler

**Files:**
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java`
- Modify: `src/main/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandler.java`

- [ ] **Step 1: Update KafkaTaxInvoiceProcessCommand**

Replace the entire content of `src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java`:

```java
package com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.saga.domain.model.SagaCommand;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

public class KafkaTaxInvoiceProcessCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    @Getter @JsonProperty("documentId")    private final String documentId;
    @Getter @JsonProperty("documentNumber") private final String documentNumber;
    @Getter @JsonProperty("signedXmlUrl")  private final String signedXmlUrl;

    @JsonCreator
    public KafkaTaxInvoiceProcessCommand(
            @JsonProperty("eventId")       UUID eventId,
            @JsonProperty("occurredAt")    Instant occurredAt,
            @JsonProperty("eventType")     String eventType,
            @JsonProperty("version")       int version,
            @JsonProperty("sagaId")        String sagaId,
            @JsonProperty("sagaStep")      SagaStep sagaStep,
            @JsonProperty("correlationId") String correlationId,
            @JsonProperty("documentId")    String documentId,
            @JsonProperty("documentNumber") String documentNumber,
            @JsonProperty("signedXmlUrl")  String signedXmlUrl) {
        super(eventId, occurredAt, eventType, version, sagaId, sagaStep, correlationId);
        this.documentId     = documentId;
        this.documentNumber = documentNumber;
        this.signedXmlUrl   = signedXmlUrl;
    }

    /** Convenience constructor for testing. */
    public KafkaTaxInvoiceProcessCommand(String sagaId, SagaStep sagaStep, String correlationId,
                                         String documentId, String documentNumber, String signedXmlUrl) {
        super(sagaId, sagaStep, correlationId);
        this.documentId     = documentId;
        this.documentNumber = documentNumber;
        this.signedXmlUrl   = signedXmlUrl;
    }

    @Override public String getSagaId()       { return super.getSagaId(); }
    @Override public SagaStep getSagaStep()   { return super.getSagaStep(); }
    @Override public String getCorrelationId(){ return super.getCorrelationId(); }
    public String getDocumentId()    { return documentId; }
    public String getDocumentNumber(){ return documentNumber; }
    public String getSignedXmlUrl()  { return signedXmlUrl; }
}
```

- [ ] **Step 2: Update SagaCommandHandler — remove getTaxInvoiceDataJson() call**

In `src/main/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandler.java`, find the `generatePdf` call (around line 116) and update it:

```java
// Before
byte[] pdfBytes = pdfGenerationService.generatePdf(
        documentNum, signedXml, command.getTaxInvoiceDataJson());

// After
byte[] pdfBytes = pdfGenerationService.generatePdf(documentNum, signedXml);
```

- [ ] **Step 3: Verify compilation**

```bash
mvn compile -pl . 2>&1 | tail -10
```
Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaTaxInvoiceProcessCommand.java \
        src/main/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandler.java
git commit -m "feat: remove taxInvoiceDataJson from KafkaTaxInvoiceProcessCommand and SagaCommandHandler"
```

---

## Task 5: Update Tests + Cleanup

**Files:**
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandlerTest.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfigTest.java`
- Modify: `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java`
- Delete: `src/main/resources/xsl/taxinvoice.xsl`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Update SagaCommandHandlerTest**

The changes are:
1. `createProcessCommand()` — remove `"{}"` (7th arg → 6th arg)
2. All `generatePdf(anyString(), anyString(), anyString())` → `generatePdf(anyString(), anyString())`
3. All `verify(pdfGenerationService).generatePdf("TXINV-2024-001", SIGNED_XML_CONTENT, "{}") ` → `verify(pdfGenerationService).generatePdf("TXINV-2024-001", SIGNED_XML_CONTENT)`
4. Null-signedXmlUrl command creation — remove `"{}"` arg

Apply these changes to `src/test/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandlerTest.java`:

```java
// Line 67-73: createProcessCommand() — remove last arg
private KafkaTaxInvoiceProcessCommand createProcessCommand() {
    return new KafkaTaxInvoiceProcessCommand(
            "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
            "doc-123", "TXINV-2024-001",
            SIGNED_XML_URL);          // removed: , "{}"
}
```

```java
// testHandleProcessCommand_Success — update mock setup and verify
when(pdfGenerationService.generatePdf(anyString(), anyString()))  // was: anyString(), anyString(), anyString()
        .thenReturn(pdfBytes);
// ...
verify(pdfGenerationService).generatePdf("TXINV-2024-001", SIGNED_XML_CONTENT);  // was: ..., "{}"
```

```java
// testHandleProcessCommand_GenerationFails — update mock
when(pdfGenerationService.generatePdf(anyString(), anyString()))  // was: anyString(), anyString(), anyString()
        .thenThrow(new TaxInvoicePdfGenerationException("FOP failed"));
```

```java
// testHandleProcessCommand_NullSignedXmlUrl — remove last arg
KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
        "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
        "doc-123", "TXINV-2024-001",
        null);   // removed: , "{}"
```

- [ ] **Step 2: Update CamelRouteConfigTest**

In `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfigTest.java`, find `testProcessTaxInvoicePdfCommandSerialization`:

```java
// Before (7 args to convenience constructor + assertions)
KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
        "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
        "doc-123", "TXINV-2024-001",
        "<TaxInvoice>...</TaxInvoice>", "{}");
// ...
assertThat(deserialized.getTaxInvoiceDataJson()).isEqualTo("{}");

// After
KafkaTaxInvoiceProcessCommand command = new KafkaTaxInvoiceProcessCommand(
        "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
        "doc-123", "TXINV-2024-001",
        "http://minio/taxinvoice-signed.xml");
// Remove the getTaxInvoiceDataJson() assertion line entirely
```

- [ ] **Step 3: Update KafkaCommandMapperTest**

In `src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java`:

```java
// Before (full @JsonCreator constructor — 11 args)
var src = new KafkaTaxInvoiceProcessCommand(
        null, null, null, 0,
        "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
        "doc-1", "TINV-001", "http://minio/xml", "{\"k\":\"v\"}");
// assertThat(result.getTaxInvoiceDataJson()).isEqualTo("{\"k\":\"v\"}");

// After (full @JsonCreator constructor — 10 args, no taxInvoiceDataJson)
var src = new KafkaTaxInvoiceProcessCommand(
        null, null, null, 0,
        "saga-1", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-1",
        "doc-1", "TINV-001", "http://minio/xml");
// Remove the getTaxInvoiceDataJson assertion
```

- [ ] **Step 4: Run the full test suite**

```bash
mvn test -pl . 2>&1 | tail -20
```
Expected: All tests pass (previously failing due to compilation errors now fixed).

- [ ] **Step 5: Delete the old XSL template**

```bash
git rm src/main/resources/xsl/taxinvoice.xsl
```

- [ ] **Step 6: Remove the max-json-size-bytes config property**

In `src/main/resources/application.yml`, find and remove the line:
```yaml
    max-json-size-bytes: ${TAXINVOICE_MAX_JSON_SIZE_BYTES:1048576}
```

- [ ] **Step 7: Run the full test suite one final time**

```bash
mvn test -pl . 2>&1 | tail -20
```
Expected: All tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/com/wpanther/taxinvoice/pdf/application/service/SagaCommandHandlerTest.java \
        src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/config/CamelRouteConfigTest.java \
        src/test/java/com/wpanther/taxinvoice/pdf/infrastructure/adapter/in/kafka/KafkaCommandMapperTest.java \
        src/main/resources/application.yml
git commit -m "chore: update tests and remove taxInvoiceDataJson remnants"
```

---

## Self-Review Against Spec

| Spec requirement | Task |
|-----------------|------|
| Remove `taxInvoiceDataJson` from command | Task 4 Step 1 |
| Remove `taxInvoiceDataJson` from service interface | Task 3 Step 1 |
| Remove `taxInvoiceDataJson` from `TaxInvoicePdfGenerationServiceImpl` | Task 3 Step 4 |
| New `taxinvoice-direct.xsl` matching `rsm:TaxInvoice_CrossIndustryInvoice` | Task 2 Step 1 |
| `ThaiAmountWordsConverter.toWords()` | Task 1 Step 3 |
| `amountInWords` injected as XSLT parameter | Task 3 Step 4 |
| `FopTaxInvoicePdfGenerator` accepts `Map<String, Object> params` | Task 2 Step 2 |
| Update `TAXINVOICE_XSL_PATH` constant | Task 2 Step 2 |
| XPath extraction of `GrandTotalAmount` | Task 3 Step 4 |
| Error on missing `GrandTotalAmount` | Task 3 Step 4 |
| Signed XML reaches `PdfA3Converter` unmodified | Task 3 Step 4 |
| Delete `taxinvoice.xsl` | Task 5 Step 5 |
| Remove `app.taxinvoice.max-json-size-bytes` | Task 5 Step 6 |
| Update all affected tests | Task 5 Steps 1–3 |
