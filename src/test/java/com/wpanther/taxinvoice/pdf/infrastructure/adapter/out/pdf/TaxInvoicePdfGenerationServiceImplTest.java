package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaxInvoicePdfGenerationServiceImpl Unit Tests")
class TaxInvoicePdfGenerationServiceImplTest {

    @Mock
    private FopTaxInvoicePdfGenerator fopPdfGenerator;

    @Mock
    private PdfA3Converter pdfA3Converter;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private TaxInvoicePdfGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaxInvoicePdfGenerationServiceImpl(fopPdfGenerator, pdfA3Converter, objectMapper, 1048576);
    }

    @Test
    @DisplayName("generatePdf() throws when xmlContent is null")
    void testGeneratePdf_NullXmlContent_Throws() {
        assertThatThrownBy(() ->
                service.generatePdf(TAX_INVOICE_NUMBER, null, MINIMAL_JSON))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("xmlContent (signed XML) is null or blank");

        verifyNoInteractions(fopPdfGenerator);
        verifyNoInteractions(pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() throws when xmlContent is blank")
    void testGeneratePdf_BlankXmlContent_Throws() {
        assertThatThrownBy(() ->
                service.generatePdf(TAX_INVOICE_NUMBER, "   ", MINIMAL_JSON))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("xmlContent (signed XML) is null or blank");

        verifyNoInteractions(fopPdfGenerator);
        verifyNoInteractions(pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() throws when taxInvoiceDataJson is null")
    void testGeneratePdf_NullJson_Throws() {
        assertThatThrownBy(() ->
                service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, null))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("taxInvoiceDataJson is null");

        verifyNoInteractions(fopPdfGenerator);
        verifyNoInteractions(pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() throws when taxInvoiceDataJson exceeds max size")
    void testGeneratePdf_JsonExceedsMaxSize_Throws() {
        // Create JSON that exceeds the 1MB (1048576 bytes) limit
        String largeJson = "{\"data\":\"" + "X".repeat(1048577) + "\"}";

        assertThatThrownBy(() ->
                service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, largeJson))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("taxInvoiceDataJson exceeds max allowed size")
                .hasMessageContaining("1048588 chars > 1048576");

        verifyNoInteractions(fopPdfGenerator);
        verifyNoInteractions(pdfA3Converter);
    }

    private static final String TAX_INVOICE_NUMBER = "TXINV-2024-001";
    private static final String XML_CONTENT = "<TaxInvoice>signed</TaxInvoice>";
    private static final String MINIMAL_JSON = "{}";
    private static final String FULL_JSON = """
            {
              "taxInvoiceNumber": "TXINV-2024-001",
              "taxInvoiceDate": "2024-01-15",
              "seller": { "name": "Seller Co.", "taxId": "1234567890" },
              "buyer":  { "name": "Buyer Co.",  "taxId": "0987654321" },
              "lineItems": [
                { "description": "Service", "quantity": "1", "unitPrice": "100", "amount": "100" }
              ],
              "subtotal": "100", "vatRate": "7", "vatAmount": "7", "grandTotal": "107"
            }
            """;

    @Test
    @DisplayName("generatePdf() calls FOP then PDFBox and returns PDF/A-3 bytes")
    void testGeneratePdf_Success() throws Exception {
        byte[] basePdf = new byte[2000];
        byte[] pdfA3  = new byte[3000];

        when(fopPdfGenerator.generatePdf(anyString())).thenReturn(basePdf);
        when(pdfA3Converter.convertToPdfA3(eq(basePdf), eq(XML_CONTENT), anyString(), eq(TAX_INVOICE_NUMBER)))
                .thenReturn(pdfA3);

        byte[] result = service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, FULL_JSON);

        assertThat(result).isSameAs(pdfA3);
        verify(fopPdfGenerator).generatePdf(anyString());
        verify(pdfA3Converter).convertToPdfA3(eq(basePdf), eq(XML_CONTENT),
                eq("taxinvoice-" + TAX_INVOICE_NUMBER + ".xml"), eq(TAX_INVOICE_NUMBER));
    }

    @Test
    @DisplayName("generatePdf() with minimal valid JSON ({}) still produces XML")
    void testGeneratePdf_MinimalJson() throws Exception {
        byte[] basePdf = new byte[500];
        when(fopPdfGenerator.generatePdf(anyString())).thenReturn(basePdf);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any()))
                .thenReturn(new byte[600]);

        // "{}" is valid JSON with no fields; all values default — should succeed
        service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, MINIMAL_JSON);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(fopPdfGenerator).generatePdf(xmlCaptor.capture());
        assertThat(xmlCaptor.getValue())
                .contains("<taxInvoice>")
                .contains(TAX_INVOICE_NUMBER);
    }

    @Test
    @DisplayName("generatePdf() throws TaxInvoicePdfGenerationException when taxInvoiceDataJson is invalid JSON")
    void testGeneratePdf_InvalidJson_Throws() {
        // Invalid JSON must fail fast during JSON parsing — no silent degradation to a blank PDF
        assertThatThrownBy(() ->
                service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, "not-valid-json"))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class);

        // FOP and PDFBox are not called due to JSON parsing failure
        verifyNoInteractions(fopPdfGenerator);
        verifyNoInteractions(pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() with full JSON passes structured XML to FOP")
    void testGeneratePdf_FullJson_XmlStructure() throws Exception {
        when(fopPdfGenerator.generatePdf(anyString())).thenReturn(new byte[1]);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(new byte[1]);

        service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, FULL_JSON);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(fopPdfGenerator).generatePdf(xmlCaptor.capture());
        String xml = xmlCaptor.getValue();
        assertThat(xml)
                .contains("<seller>")
                .contains("<buyer>")
                .contains("<lineItems>")
                .contains("Seller Co.")
                .contains("grandTotal");
    }

    @Test
    @DisplayName("generatePdf() wraps FopPdfGenerationException in TaxInvoicePdfGenerationException")
    void testGeneratePdf_FopFails() throws Exception {
        when(fopPdfGenerator.generatePdf(anyString()))
                .thenThrow(new FopTaxInvoicePdfGenerator.PdfGenerationException("XSL transform failed"));

        assertThatThrownBy(() ->
                service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, MINIMAL_JSON))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("PDF generation failed");

        verifyNoInteractions(pdfA3Converter);
    }

    @Test
    @DisplayName("generatePdf() wraps PdfConversionException in TaxInvoicePdfGenerationException")
    void testGeneratePdf_PdfA3ConversionFails() throws Exception {
        when(fopPdfGenerator.generatePdf(anyString())).thenReturn(new byte[100]);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any()))
                .thenThrow(new PdfA3Converter.PdfConversionException("ICC profile missing"));

        assertThatThrownBy(() ->
                service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, MINIMAL_JSON))
                .isInstanceOf(TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException.class)
                .hasMessageContaining("PDF/A-3 conversion failed");
    }

    @Test
    @DisplayName("generatePdf() escapes XML special characters in JSON values")
    void testGeneratePdf_XmlEscaping() throws Exception {
        String jsonWithSpecialChars = """
                {
                  "taxInvoiceNumber": "TXINV&001",
                  "seller": { "name": "A & B <Corp>" }
                }
                """;

        when(fopPdfGenerator.generatePdf(anyString())).thenReturn(new byte[1]);
        when(pdfA3Converter.convertToPdfA3(any(), any(), any(), any())).thenReturn(new byte[1]);

        service.generatePdf(TAX_INVOICE_NUMBER, XML_CONTENT, jsonWithSpecialChars);

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        verify(fopPdfGenerator).generatePdf(xmlCaptor.capture());
        String xml = xmlCaptor.getValue();
        assertThat(xml)
                .doesNotContain("A & B")
                .contains("&amp;")
                .contains("&lt;");
    }
}
