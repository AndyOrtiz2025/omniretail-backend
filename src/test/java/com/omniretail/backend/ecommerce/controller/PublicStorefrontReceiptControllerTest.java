package com.omniretail.backend.ecommerce.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.ecommerce.service.StorefrontReceiptService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class PublicStorefrontReceiptControllerTest {

    @Test
    void returnsTheReceiptAsADownloadablePdf() {
        StorefrontReceiptService receipts = mock(StorefrontReceiptService.class);
        PublicStorefrontReceiptController controller = new PublicStorefrontReceiptController(receipts);
        when(receipts.render("ferreteria", "tracking-token")).thenReturn(new byte[] {1, 2, 3});

        var response = controller.download("ferreteria", "tracking-token");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"comprobante-pedido.pdf\"");
        assertThat(response.getBody()).containsExactly(1, 2, 3);
        verify(receipts).render("ferreteria", "tracking-token");
    }
}
