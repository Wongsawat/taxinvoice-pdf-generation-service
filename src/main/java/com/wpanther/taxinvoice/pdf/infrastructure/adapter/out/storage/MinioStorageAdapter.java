package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.storage;

import com.wpanther.taxinvoice.pdf.application.port.out.PdfStoragePort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.util.UUID;

@Component
@Slf4j
public class MinioStorageAdapter implements PdfStoragePort {

    private final S3Client s3Client;
    private final String bucketName;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public MinioStorageAdapter(
            S3Client s3Client,
            @Value("${app.minio.bucket-name}") String bucketName,
            @Value("${app.minio.base-url}") String baseUrl,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.baseUrl = baseUrl;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("minio");
    }

    @Override
    public String store(String taxInvoiceNumber, byte[] pdfBytes) {
        return CircuitBreaker.decorateSupplier(circuitBreaker, () -> doStore(taxInvoiceNumber, pdfBytes)).get();
    }

    @Override
    public void delete(String s3Key) {
        CircuitBreaker.decorateRunnable(circuitBreaker, () -> doDelete(s3Key)).run();
    }

    @Override
    public String resolveUrl(String s3Key) {
        return baseUrl + "/" + s3Key;
    }

    private String doStore(String taxInvoiceNumber, byte[] pdfBytes) {
        LocalDate now = LocalDate.now();
        String safeName = taxInvoiceNumber.replaceAll("[^a-zA-Z0-9\\-_]", "_");
        String fileName = String.format("taxinvoice-%s-%s.pdf", safeName, UUID.randomUUID());
        String s3Key = String.format("%04d/%02d/%02d/%s",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(), fileName);

        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .contentType("application/pdf")
                .contentLength((long) pdfBytes.length)
                .build();

        s3Client.putObject(put, RequestBody.fromBytes(pdfBytes));
        log.debug("Uploaded PDF to MinIO: bucket={}, key={}", bucketName, s3Key);
        return s3Key;
    }

    private void doDelete(String s3Key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(s3Key)
                .build());
        log.info("Deleted PDF from MinIO: bucket={}, key={}", bucketName, s3Key);
    }
}
