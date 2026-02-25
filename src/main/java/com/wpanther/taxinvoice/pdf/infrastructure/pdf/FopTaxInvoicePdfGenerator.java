package com.wpanther.taxinvoice.pdf.infrastructure.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.fop.apps.Fop;
import org.apache.fop.apps.FopFactory;
import org.apache.fop.apps.MimeConstants;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Generates PDF documents using Apache FOP with XSL-FO templates.
 *
 * This class transforms XML tax invoice data using an XSL-FO stylesheet
 * to produce PDF output.
 */
@Component
@Slf4j
public class FopTaxInvoicePdfGenerator {

    private static final String FOP_CONFIG_PATH = "fop/fop.xconf";
    private static final String TAXINVOICE_XSL_PATH = "xsl/taxinvoice.xsl";

    private final FopFactory fopFactory;
    private final TransformerFactory transformerFactory;
    private final Templates cachedTemplates;

    public FopTaxInvoicePdfGenerator() throws Exception {
        this.fopFactory = createFopFactory();
        this.transformerFactory = TransformerFactory.newInstance();
        this.cachedTemplates = compileTemplates(TAXINVOICE_XSL_PATH);
        log.info("FopTaxInvoicePdfGenerator initialized with config: {}", FOP_CONFIG_PATH);
    }

    private Templates compileTemplates(String xslPath) throws Exception {
        ClassPathResource xslResource = new ClassPathResource(xslPath);
        if (!xslResource.exists()) {
            throw new IllegalStateException("XSL template not found at startup: " + xslPath);
        }
        try (InputStream is = xslResource.getInputStream()) {
            return transformerFactory.newTemplates(new StreamSource(is));
        }
    }

    private FopFactory createFopFactory() throws Exception {
        URI baseUri = resolveBaseUri();
        try {
            ClassPathResource configResource = new ClassPathResource(FOP_CONFIG_PATH);
            if (configResource.exists()) {
                try (InputStream configStream = configResource.getInputStream()) {
                    return FopFactory.newInstance(baseUri, configStream);
                }
            } else {
                log.warn("FOP config not found at {}, using default configuration", FOP_CONFIG_PATH);
                return FopFactory.newInstance(baseUri);
            }
        } catch (Exception e) {
            log.warn("Failed to load FOP config, using default: {}", e.getMessage());
            return FopFactory.newInstance(baseUri);
        }
    }

    /**
     * Resolve the FOP base URI used for resolving relative font paths in fop.xconf.
     *
     * <p>Tries the classpath root first (works for both exploded and JAR deployments
     * when fonts are inside BOOT-INF/classes/fonts/). Falls back to the JVM working
     * directory if the classpath root URL cannot be resolved to a URI.</p>
     */
    private URI resolveBaseUri() {
        try {
            URL classpathRoot = new ClassPathResource("").getURL();
            URI uri = classpathRoot.toURI();
            log.debug("FOP base URI resolved to: {}", uri);
            return uri;
        } catch (Exception e) {
            log.warn("Could not resolve classpath root URI for FOP, falling back to working directory: {}",
                    e.getMessage());
            return URI.create("file:" + System.getProperty("user.dir", ".") + "/");
        }
    }

    /**
     * Generate PDF from XML data using the tax invoice XSL-FO template.
     *
     * @param xmlData The XML representation of tax invoice data
     * @return PDF bytes
     * @throws PdfGenerationException if generation fails
     */
    public byte[] generatePdf(String xmlData) throws PdfGenerationException {
        log.debug("Generating PDF with cached template: {}", TAXINVOICE_XSL_PATH);
        try {
            return renderPdf(xmlData, cachedTemplates.newTransformer());
        } catch (javax.xml.transform.TransformerConfigurationException e) {
            throw new PdfGenerationException("Failed to create transformer from cached templates: " + e.getMessage(), e);
        }
    }

    /**
     * Generate PDF from XML data using a specified XSL-FO template.
     * Uses the cached {@link Templates} for the default path; compiles on demand otherwise.
     *
     * @param xmlData The XML representation of tax invoice data
     * @param xslPath Path to the XSL-FO template (classpath resource)
     * @return PDF bytes
     * @throws PdfGenerationException if generation fails
     */
    public byte[] generatePdf(String xmlData, String xslPath) throws PdfGenerationException {
        if (TAXINVOICE_XSL_PATH.equals(xslPath)) {
            return generatePdf(xmlData);
        }
        log.debug("Generating PDF with template: {}", xslPath);
        try {
            ClassPathResource xslResource = new ClassPathResource(xslPath);
            if (!xslResource.exists()) {
                throw new PdfGenerationException("XSL template not found: " + xslPath);
            }
            try (InputStream is = xslResource.getInputStream()) {
                Transformer transformer = transformerFactory.newTransformer(new StreamSource(is));
                return renderPdf(xmlData, transformer);
            }
        } catch (PdfGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate PDF", e);
            throw new PdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    private byte[] renderPdf(String xmlData, Transformer transformer) throws PdfGenerationException {
        try (ByteArrayOutputStream pdfOutput = new ByteArrayOutputStream()) {
            Fop fop = fopFactory.newFop(MimeConstants.MIME_PDF, pdfOutput);
            Source xmlSource = new StreamSource(
                new ByteArrayInputStream(xmlData.getBytes(StandardCharsets.UTF_8))
            );
            Result result = new SAXResult(fop.getDefaultHandler());
            transformer.transform(xmlSource, result);
            byte[] pdfBytes = pdfOutput.toByteArray();
            log.info("Generated PDF: {} bytes", pdfBytes.length);
            return pdfBytes;
        } catch (Exception e) {
            log.error("Failed to generate PDF", e);
            throw new PdfGenerationException("PDF generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Exception thrown when PDF generation fails
     */
    public static class PdfGenerationException extends Exception {
        public PdfGenerationException(String message) {
            super(message);
        }

        public PdfGenerationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
