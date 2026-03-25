package com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.storage;

import com.wpanther.taxinvoice.pdf.infrastructure.adapter.out.persistence.JpaTaxInvoicePdfDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Periodic cleanup service for orphaned PDFs in MinIO.
 * <p>
 * Reconciles MinIO objects against database records to detect and remove
 * PDFs that were uploaded but never committed (e.g., service crash between
 * upload and DB commit).
 * <p>
 * Enabled via configuration: {@code app.minio.cleanup.enabled=true}
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.minio.cleanup.enabled", havingValue = "true", matchIfMissing = false)
public class MinioCleanupService {

    private final MinioStorageAdapter minioStorage;
    private final JpaTaxInvoicePdfDocumentRepository repository;

    /**
     * Run orphaned PDF cleanup daily at 2 AM.
     * <p>
     * Cron schedule can be customized via {@code app.minio.cleanup.cron} property.
     */
    @Scheduled(cron = "${app.minio.cleanup.cron:0 0 2 * * ?}")
    public void cleanupOrphanedPdfs() {
        log.info("Starting orphaned PDF cleanup job");

        try {
            // Get all PDF objects from MinIO
            List<String> minioKeys = minioStorage.listAllPdfs();
            log.debug("Found {} PDF objects in MinIO", minioKeys.size());

            // Get all document paths from database
            Set<String> databaseKeys = repository.findAllDocumentPaths();
            log.debug("Found {} document paths in database", databaseKeys.size());

            // Find orphaned objects (in MinIO but not in database)
            List<String> orphanedKeys = minioKeys.stream()
                    .filter(key -> !databaseKeys.contains(key))
                    .toList();

            if (orphanedKeys.isEmpty()) {
                log.info("No orphaned PDFs found");
                return;
            }

            log.warn("Found {} orphaned PDF(s) to delete: {}", orphanedKeys.size(), orphanedKeys);

            // Delete orphaned objects
            int deletedCount = 0;
            for (String key : orphanedKeys) {
                minioStorage.deleteWithoutCircuitBreaker(key);
                deletedCount++;
            }

            log.info("Orphaned PDF cleanup completed: {} of {} objects deleted",
                    deletedCount, orphanedKeys.size());

        } catch (Exception e) {
            log.error("Orphaned PDF cleanup job failed: {}", e.getMessage(), e);
        }
    }
}
