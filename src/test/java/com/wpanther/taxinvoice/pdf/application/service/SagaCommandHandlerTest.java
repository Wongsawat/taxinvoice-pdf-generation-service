package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.saga.domain.enums.SagaStep;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceCompensateCommand;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.in.kafka.KafkaTaxInvoiceProcessCommand;
import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.EventPublisher;
import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.messaging.SagaReplyPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Unit Tests")
class SagaCommandHandlerTest {

    @Mock
    private TaxInvoicePdfDocumentRepository repository;

    @Mock
    private TaxInvoicePdfDocumentService pdfDocumentService;

    @Mock
    private SagaReplyPublisher sagaReplyPublisher;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private SagaCommandHandler sagaCommandHandler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sagaCommandHandler, "maxRetries", 3);
    }

    private static final String SIGNED_XML_URL = "http://minio:9000/signed/taxinvoice-signed.xml";
    private static final String SIGNED_XML_CONTENT = "<TaxInvoice>signed</TaxInvoice>";

    private KafkaTaxInvoiceProcessCommand createProcessCommand() {
        return new KafkaTaxInvoiceProcessCommand(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "doc-123", "tax-inv-001", "TXINV-2024-001",
                SIGNED_XML_URL, "{}"
        );
    }

    private KafkaTaxInvoiceCompensateCommand createCompensateCommand() {
        return new KafkaTaxInvoiceCompensateCommand(
                "saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "doc-123", "tax-inv-001"
        );
    }

    private TaxInvoicePdfDocument createCompletedDocument() {
        return TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentPath("/var/documents/taxinvoices/2024/01/15/test.pdf")
                .documentUrl("http://localhost:8084/documents/test.pdf")
                .fileSize(12345L)
                .xmlEmbedded(true)
                .mimeType("application/pdf")
                .retryCount(0)
                .build();
    }

    @Test
    @DisplayName("Should generate PDF and send SUCCESS reply")
    void testHandleProcessCommand_Success() {
        // Given
        KafkaTaxInvoiceProcessCommand command = createProcessCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);

        TaxInvoicePdfDocument document = TaxInvoicePdfDocument.builder()
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentUrl("http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf")
                .fileSize(12345L)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(document);

        // When
        sagaCommandHandler.handle(command);

        // Then
        verify(pdfDocumentService).generatePdf("tax-inv-001", "TXINV-2024-001", SIGNED_XML_CONTENT, "{}");
        verify(eventPublisher).publishPdfGenerated(any());
        verify(sagaReplyPublisher).publishSuccess("saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf", 12345L);
    }

    @Test
    @DisplayName("Should send SUCCESS reply for already completed document (idempotency)")
    void testHandleProcessCommand_AlreadyCompleted() {
        // Given
        KafkaTaxInvoiceProcessCommand command = createProcessCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.of(createCompletedDocument()));

        // When
        sagaCommandHandler.handle(command);

        // Then
        verify(pdfDocumentService, never()).generatePdf(anyString(), anyString(), anyString(), anyString());
        verify(eventPublisher).publishPdfGenerated(any());
        verify(sagaReplyPublisher).publishSuccess(eq("saga-001"), eq(SagaStep.GENERATE_TAX_INVOICE_PDF), eq("corr-456"),
                eq("http://localhost:8084/documents/test.pdf"), eq(12345L));
    }

    @Test
    @DisplayName("Should send FAILURE reply when max retries exceeded")
    void testHandleProcessCommand_MaxRetriesExceeded() {
        // Given
        KafkaTaxInvoiceProcessCommand command = createProcessCommand();
        TaxInvoicePdfDocument failedDocument = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(3)
                .build();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.of(failedDocument));

        // When
        sagaCommandHandler.handle(command);

        // Then
        verify(pdfDocumentService, never()).generatePdf(anyString(), anyString(), anyString(), anyString());
        verify(sagaReplyPublisher).publishFailure("saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456",
                "Maximum retry attempts exceeded");
    }

    @Test
    @DisplayName("Should send FAILURE reply when PDF generation returns FAILED document")
    void testHandleProcessCommand_GenerationFails() {
        // Given
        KafkaTaxInvoiceProcessCommand command = createProcessCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.empty());
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);

        TaxInvoicePdfDocument failedDoc = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.FAILED)
                .errorMessage("FOP transform failed")
                .retryCount(0)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(failedDoc);

        // When
        sagaCommandHandler.handle(command);

        // Then — FAILURE reply published with the error message from the document
        verify(eventPublisher, never()).publishPdfGenerated(any());
        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_TAX_INVOICE_PDF),
                eq("corr-456"), eq("FOP transform failed"));
    }

    @Test
    @DisplayName("Should carry forward retry count correctly from previous failed attempt")
    void testHandleProcessCommand_RetryCountCarriedForward() {
        // Given — previous failed attempt with retryCount=1
        KafkaTaxInvoiceProcessCommand command = createProcessCommand();
        TaxInvoicePdfDocument previousFailed = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(1)
                .build();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.of(previousFailed));
        when(restTemplate.getForObject(SIGNED_XML_URL, String.class)).thenReturn(SIGNED_XML_CONTENT);

        TaxInvoicePdfDocument newDocument = TaxInvoicePdfDocument.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentUrl("http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf")
                .fileSize(5000L)
                .retryCount(0) // starts at 0, must be set to 2
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(newDocument);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        sagaCommandHandler.handle(command);

        // Then — document saved with retryCount = previousFailed.retryCount + 1 = 2
        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRetryCount()).isEqualTo(2);
        verify(sagaReplyPublisher).publishSuccess(anyString(), any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Should delete document and send COMPENSATED reply")
    void testHandleCompensation_Success() {
        // Given
        KafkaTaxInvoiceCompensateCommand command = createCompensateCommand();
        TaxInvoicePdfDocument document = createCompletedDocument();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.of(document));

        // When
        sagaCommandHandler.handle(command);

        // Then
        verify(pdfDocumentService).deletePdfFile(document.getDocumentPath());
        verify(repository).deleteById(document.getId());
        verify(sagaReplyPublisher).publishCompensated("saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456");
    }

    @Test
    @DisplayName("Should send COMPENSATED reply even when no document exists")
    void testHandleCompensation_NoDocumentFound() {
        // Given
        KafkaTaxInvoiceCompensateCommand command = createCompensateCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.empty());

        // When
        sagaCommandHandler.handle(command);

        // Then
        verify(pdfDocumentService, never()).deletePdfFile(anyString());
        verify(repository, never()).deleteById(any());
        verify(sagaReplyPublisher).publishCompensated("saga-001", SagaStep.GENERATE_TAX_INVOICE_PDF, "corr-456");
    }

    @Test
    @DisplayName("Should send FAILURE reply when compensation fails")
    void testHandleCompensation_Failure() {
        // Given
        KafkaTaxInvoiceCompensateCommand command = createCompensateCommand();
        TaxInvoicePdfDocument document = createCompletedDocument();
        when(repository.findByTaxInvoiceId("tax-inv-001")).thenReturn(Optional.of(document));
        doThrow(new RuntimeException("Delete failed"))
                .when(pdfDocumentService).deletePdfFile(anyString());

        // When
        sagaCommandHandler.handle(command);

        // Then
        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq(SagaStep.GENERATE_TAX_INVOICE_PDF),
                eq("corr-456"), contains("Compensation failed"));
    }
}
