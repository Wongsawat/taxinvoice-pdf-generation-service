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
