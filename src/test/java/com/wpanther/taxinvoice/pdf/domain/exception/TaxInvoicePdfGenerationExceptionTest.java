package com.wpanther.taxinvoice.pdf.domain.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaxInvoicePdfGenerationExceptionTest {

    @Test
    void constructor_withMessage_storesMessage() {
        var ex = new TaxInvoicePdfGenerationException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
    }

    @Test
    void constructor_withMessageAndCause_storesBoth() {
        var cause = new RuntimeException("root");
        var ex = new TaxInvoicePdfGenerationException("wrapped", cause);
        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void isRuntimeException() {
        assertThat(new TaxInvoicePdfGenerationException("x"))
                .isInstanceOf(RuntimeException.class);
    }
}
