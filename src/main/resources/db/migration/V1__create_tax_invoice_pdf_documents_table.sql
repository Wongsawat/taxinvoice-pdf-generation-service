-- Create tax_invoice_pdf_documents table
CREATE TABLE tax_invoice_pdf_documents (
    id UUID PRIMARY KEY,
    tax_invoice_id VARCHAR(100) NOT NULL UNIQUE,
    tax_invoice_number VARCHAR(50) NOT NULL,
    document_path VARCHAR(500),
    document_url VARCHAR(1000),
    file_size BIGINT,
    mime_type VARCHAR(100) NOT NULL DEFAULT 'application/pdf',
    xml_embedded BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

-- Create indexes
CREATE INDEX idx_tax_invoice_pdf_tax_invoice_id ON tax_invoice_pdf_documents(tax_invoice_id);
CREATE INDEX idx_tax_invoice_pdf_tax_invoice_number ON tax_invoice_pdf_documents(tax_invoice_number);
CREATE INDEX idx_tax_invoice_pdf_status ON tax_invoice_pdf_documents(status);
