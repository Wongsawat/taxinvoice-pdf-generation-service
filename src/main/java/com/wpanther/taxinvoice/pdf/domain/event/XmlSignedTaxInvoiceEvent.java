package com.wpanther.taxinvoice.pdf.domain.event;

/**
 * External event consumed when a tax invoice XML has been signed
 * This event is published by the XML Signing Service
 */
public class XmlSignedTaxInvoiceEvent {

    private final String documentId;
    private final String taxInvoiceId;
    private final String taxInvoiceNumber;
    private final String signedXmlContent;
    private final String taxInvoiceDataJson;
    private final String correlationId;

    public XmlSignedTaxInvoiceEvent(
        String documentId,
        String taxInvoiceId,
        String taxInvoiceNumber,
        String signedXmlContent,
        String taxInvoiceDataJson,
        String correlationId
    ) {
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.signedXmlContent = signedXmlContent;
        this.taxInvoiceDataJson = taxInvoiceDataJson;
        this.correlationId = correlationId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getTaxInvoiceId() {
        return taxInvoiceId;
    }

    public String getTaxInvoiceNumber() {
        return taxInvoiceNumber;
    }

    public String getSignedXmlContent() {
        return signedXmlContent;
    }

    public String getTaxInvoiceDataJson() {
        return taxInvoiceDataJson;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
