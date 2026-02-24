package com.wpanther.taxinvoice.pdf.application.service;

import com.wpanther.taxinvoice.pdf.domain.model.GenerationStatus;
import com.wpanther.taxinvoice.pdf.domain.model.TaxInvoicePdfDocument;
import com.wpanther.taxinvoice.pdf.domain.repository.TaxInvoicePdfDocumentRepository;
import com.wpanther.taxinvoice.pdf.domain.service.TaxInvoicePdfGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TaxInvoicePdfDocumentService Unit Tests")
class TaxInvoicePdfDocumentServiceTest {

    @Mock
    private TaxInvoicePdfDocumentRepository repository;

    @Mock
    private TaxInvoicePdfGenerationService pdfGenerationService;

    @Mock
    private S3Client s3Client;

    @InjectMocks
    private TaxInvoicePdfDocumentService service;

    private static final String BUCKET = "taxinvoices";
    private static final String BASE_URL = "http://localhost:9000/taxinvoices";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "bucketName", BUCKET);
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
    }

    private TaxInvoicePdfDocument savedWith(GenerationStatus status) {
        return TaxInvoicePdfDocument.builder()
                .taxInvoiceId("tax-inv-001")
                .taxInvoiceNumber("TXINV-001")
                .status(status)
                .build();
    }

    @Test
    @DisplayName("generatePdf() uploads to MinIO and returns COMPLETED document")
    void testGeneratePdf_Success() throws Exception {
        byte[] pdfBytes = new byte[5000];

        // Repository returns the document passed to save()
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(pdfBytes);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        TaxInvoicePdfDocument result = service.generatePdf(
                "tax-inv-001", "TXINV-001", "<xml/>", "{}");

        assertThat(result.getStatus()).isEqualTo(GenerationStatus.COMPLETED);
        assertThat(result.getFileSize()).isEqualTo(5000L);
        assertThat(result.getDocumentUrl()).startsWith(BASE_URL + "/");
        assertThat(result.getDocumentPath()).isNotBlank();
        assertThat(result.isXmlEmbedded()).isTrue();

        // Verify S3 upload happened with correct bucket
        ArgumentCaptor<PutObjectRequest> putCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(putCaptor.capture(), any(RequestBody.class));
        assertThat(putCaptor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(putCaptor.getValue().contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("generatePdf() marks document FAILED and rethrows when PDF generation throws")
    void testGeneratePdf_PdfGenerationFails() throws Exception {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenThrow(new TaxInvoicePdfGenerationService.TaxInvoicePdfGenerationException(
                        "FOP failed", null));

        assertThatThrownBy(() ->
                service.generatePdf("tax-inv-001", "TXINV-001", "<xml/>", "{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Tax invoice PDF generation failed");

        // Repository should have saved a FAILED document
        ArgumentCaptor<TaxInvoicePdfDocument> captor = ArgumentCaptor.forClass(TaxInvoicePdfDocument.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        boolean hasFailed = captor.getAllValues().stream()
                .anyMatch(d -> d.getStatus() == GenerationStatus.FAILED);
        assertThat(hasFailed).isTrue();
    }

    @Test
    @DisplayName("deletePdfFile() sends DeleteObjectRequest to S3")
    void testDeletePdfFile_Success() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        service.deletePdfFile("2024/01/15/taxinvoice-TXINV-001-abc.pdf");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("2024/01/15/taxinvoice-TXINV-001-abc.pdf");
    }

    @Test
    @DisplayName("deletePdfFile() wraps S3 exception in RuntimeException")
    void testDeletePdfFile_S3Fails() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 unavailable"));

        assertThatThrownBy(() -> service.deletePdfFile("some/key.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to delete PDF from MinIO");
    }

    @Test
    @DisplayName("generatePdf() S3 key follows YYYY/MM/DD/<filename> pattern")
    void testGeneratePdf_S3KeyPattern() throws Exception {
        byte[] pdfBytes = new byte[1000];
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pdfGenerationService.generatePdf(anyString(), anyString(), anyString()))
                .thenReturn(pdfBytes);
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        TaxInvoicePdfDocument result = service.generatePdf(
                "tax-inv-001", "TXINV-001", "<xml/>", "{}");

        // documentPath is the S3 key; should match YYYY/MM/DD/taxinvoice-*.pdf
        assertThat(result.getDocumentPath()).matches("\\d{4}/\\d{2}/\\d{2}/taxinvoice-.+\\.pdf");
        // documentUrl = baseUrl + "/" + key
        assertThat(result.getDocumentUrl())
                .startsWith(BASE_URL + "/")
                .endsWith(result.getDocumentPath());
    }
}
