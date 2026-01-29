package com.wpanther.taxinvoice.pdf.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published when a tax invoice PDF has been generated
 */
public class TaxInvoicePdfGeneratedEvent {

    private final String eventId;
    private final String eventType = "pdf.generated.tax-invoice";
    private final Instant occurredAt;
    private final int version = 1;
    private final String documentId;
    private final String taxInvoiceId;
    private final String taxInvoiceNumber;
    private final String documentUrl;
    private final long fileSize;
    private final boolean xmlEmbedded;
    private final String correlationId;

    public TaxInvoicePdfGeneratedEvent(
        String documentId,
        String taxInvoiceId,
        String taxInvoiceNumber,
        String documentUrl,
        long fileSize,
        boolean xmlEmbedded,
        String correlationId
    ) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.documentId = documentId;
        this.taxInvoiceId = taxInvoiceId;
        this.taxInvoiceNumber = taxInvoiceNumber;
        this.documentUrl = documentUrl;
        this.fileSize = fileSize;
        this.xmlEmbedded = xmlEmbedded;
        this.correlationId = correlationId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public int getVersion() {
        return version;
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

    public String getDocumentUrl() {
        return documentUrl;
    }

    public long getFileSize() {
        return fileSize;
    }

    public boolean isXmlEmbedded() {
        return xmlEmbedded;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
