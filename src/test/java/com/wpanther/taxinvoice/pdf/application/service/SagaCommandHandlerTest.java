package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.domain.event.CompensateTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.event.ProcessTaxInvoicePdfCommand;
import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.infrastructure.messaging.EventPublisher;
import com.wpanther.taxinvoice.pdf.infrastructure.messaging.SagaReplyPublisher;
import com.wpanther.taxinvoice.pdf.infrastructure.persistence.JpaTaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.infrastructure.persistence.TaxInvoicePdfDocumentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SagaCommandHandler Unit Tests")
class SagaCommandHandlerTest {

    @Mock
    private JpaTaxInvoicePdfDocumentRepository repository;

    @Mock
    private TaxInvoicePdfDocumentService pdfDocumentService;

    @Mock
    private SagaReplyPublisher sagaReplyPublisher;

    @Mock
    private EventPublisher eventPublisher;

    @InjectMocks
    private SagaCommandHandler sagaCommandHandler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sagaCommandHandler, "maxRetries", 3);
    }

    private ProcessTaxInvoicePdfCommand createProcessCommand() {
        return new ProcessTaxInvoicePdfCommand(
                "saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456",
                "doc-123", "tax-inv-001", "TXINV-2024-001",
                "<TaxInvoice>signed</TaxInvoice>", "{}"
        );
    }

    private CompensateTaxInvoicePdfCommand createCompensateCommand() {
        return new CompensateTaxInvoicePdfCommand(
                "saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456",
                "doc-123", "tax-inv-001"
        );
    }

    private TaxInvoicePdfDocumentEntity createCompletedEntity() {
        return TaxInvoicePdfDocumentEntity.builder()
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
        ProcessTaxInvoicePdfCommand command = createProcessCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(createCompletedEntity()));

        TaxInvoicePdfDocument document = TaxInvoicePdfDocument.builder()
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.COMPLETED)
                .documentUrl("http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf")
                .fileSize(12345)
                .build();
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(document);

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        verify(pdfDocumentService).generatePdf("tax-inv-001", "TXINV-2024-001",
                "<TaxInvoice>signed</TaxInvoice>", "{}");
        verify(eventPublisher).publishPdfGenerated(any());

        verify(sagaReplyPublisher).publishSuccess("saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456",
                "http://localhost:9000/taxinvoices/2024/01/15/taxinvoice-TXINV-2024-001-abc.pdf", 12345L);
    }

    @Test
    @DisplayName("Should send SUCCESS reply for already completed document (idempotency)")
    void testHandleProcessCommand_AlreadyCompleted() {
        // Given
        ProcessTaxInvoicePdfCommand command = createProcessCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.of(createCompletedEntity()));

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        verify(pdfDocumentService, never()).generatePdf(anyString(), anyString(), anyString(), anyString());
        verify(eventPublisher).publishPdfGenerated(any());

        verify(sagaReplyPublisher).publishSuccess(eq("saga-001"), eq("GENERATE_TAX_INVOICE_PDF"), eq("corr-456"),
                eq("http://localhost:8084/documents/test.pdf"), eq(12345L));
    }

    @Test
    @DisplayName("Should send FAILURE reply when max retries exceeded")
    void testHandleProcessCommand_MaxRetriesExceeded() {
        // Given
        ProcessTaxInvoicePdfCommand command = createProcessCommand();
        TaxInvoicePdfDocumentEntity failedEntity = TaxInvoicePdfDocumentEntity.builder()
                .id(UUID.randomUUID())
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-2024-001")
                .status(GenerationStatus.FAILED)
                .retryCount(3)
                .build();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.of(failedEntity));

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        verify(pdfDocumentService, never()).generatePdf(anyString(), anyString(), anyString(), anyString());
        verify(sagaReplyPublisher).publishFailure("saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456",
                "Maximum retry attempts exceeded");
    }

    @Test
    @DisplayName("Should send FAILURE reply when PDF generation fails")
    void testHandleProcessCommand_GenerationFails() {
        // Given
        ProcessTaxInvoicePdfCommand command = createProcessCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.empty());
        when(pdfDocumentService.generatePdf(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("PDF generation error"));

        // When
        sagaCommandHandler.handleProcessCommand(command);

        // Then
        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq("GENERATE_TAX_INVOICE_PDF"),
                eq("corr-456"), anyString());
    }

    @Test
    @DisplayName("Should delete document and send COMPENSATED reply")
    void testHandleCompensation_Success() {
        // Given
        CompensateTaxInvoicePdfCommand command = createCompensateCommand();
        TaxInvoicePdfDocumentEntity entity = createCompletedEntity();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.of(entity));

        // When
        sagaCommandHandler.handleCompensation(command);

        // Then
        verify(pdfDocumentService).deletePdfFile(entity.getDocumentPath());
        verify(repository).deleteById(entity.getId());
        verify(sagaReplyPublisher).publishCompensated("saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456");
    }

    @Test
    @DisplayName("Should send COMPENSATED reply even when no document exists")
    void testHandleCompensation_NoDocumentFound() {
        // Given
        CompensateTaxInvoicePdfCommand command = createCompensateCommand();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.empty());

        // When
        sagaCommandHandler.handleCompensation(command);

        // Then
        verify(pdfDocumentService, never()).deletePdfFile(anyString());
        verify(repository, never()).deleteById(any());
        verify(sagaReplyPublisher).publishCompensated("saga-001", "GENERATE_TAX_INVOICE_PDF", "corr-456");
    }

    @Test
    @DisplayName("Should send FAILURE reply when compensation fails")
    void testHandleCompensation_Failure() {
        // Given
        CompensateTaxInvoicePdfCommand command = createCompensateCommand();
        TaxInvoicePdfDocumentEntity entity = createCompletedEntity();
        when(repository.findByTaxInvoiceId("tax-inv-001"))
                .thenReturn(Optional.of(entity));
        doThrow(new RuntimeException("Delete failed"))
                .when(pdfDocumentService).deletePdfFile(anyString());

        // When
        sagaCommandHandler.handleCompensation(command);

        // Then
        verify(sagaReplyPublisher).publishFailure(eq("saga-001"), eq("GENERATE_TAX_INVOICE_PDF"),
                eq("corr-456"), contains("Compensation failed"));
    }
}
