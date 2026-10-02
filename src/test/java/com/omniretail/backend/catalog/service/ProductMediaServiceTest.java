package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.ProductMediaCreateRequest;
import com.omniretail.backend.catalog.entity.Product;
import com.omniretail.backend.catalog.entity.ProductMedia;
import com.omniretail.backend.catalog.entity.ProductMediaType;
import com.omniretail.backend.catalog.repository.ProductMediaRepository;
import com.omniretail.backend.catalog.repository.ProductRepository;
import com.omniretail.backend.shared.exception.BusinessException;
import com.omniretail.backend.shared.media.MediaStorageService;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class ProductMediaServiceTest {
    @Mock ProductRepository products; @Mock ProductMediaRepository media; @Mock MediaStorageService storage;
    @Mock CurrentUser currentUser; @InjectMocks ProductMediaService service;
    UUID tenant = UUID.randomUUID(); UUID productId = UUID.randomUUID();
    @BeforeEach void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(UUID.randomUUID(), tenant,
                UserType.employee, null, null, UUID.randomUUID()));
        when(products.findForUpdateByTenantIdAndId(tenant, productId)).thenReturn(Optional.of(Product.builder().build()));
    }
    @Test void uploadsLocalImageAndRegistersExternalVideo() {
        when(media.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
        String url = "/media/" + tenant + "/products/" + productId + "/" + UUID.randomUUID() + ".webp";
        when(storage.storeImage(tenant, "products", productId, file)).thenReturn(url);
        assertThat(service.upload(productId, file, "Alt", 0, true).url()).isEqualTo(url);
        assertThat(service.createExternal(productId, new ProductMediaCreateRequest(ProductMediaType.video,
                "https://video.example/watch/1", null, 1, false)).type()).isEqualTo(ProductMediaType.video);
    }
    @Test void enforcesSixItemLimitAndCleansManagedFileOnDelete() {
        when(media.countByTenantIdAndProductId(tenant, productId)).thenReturn(6L);
        assertThatThrownBy(() -> service.createExternal(productId, new ProductMediaCreateRequest(
                ProductMediaType.image, "https://cdn.example/image.jpg", null, 0, false)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_MEDIA_LIMIT"));
        UUID mediaId = UUID.randomUUID();
        ProductMedia entity = ProductMedia.builder().productId(productId).type(ProductMediaType.image)
                .url("/media/" + tenant + "/products/" + productId + "/" + UUID.randomUUID() + ".jpg")
                .sortOrder(0).build();
        when(media.findByTenantIdAndProductIdAndId(tenant, productId, mediaId)).thenReturn(Optional.of(entity));
        service.delete(productId, mediaId);
        verify(storage).deleteAfterCommit(entity.getUrl());
    }
    @Test void settingPrimaryClearsPreviousPrimaryWithinTransaction() {
        when(media.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        UUID mediaId = UUID.randomUUID();
        ProductMedia entity = ProductMedia.builder().productId(productId).type(ProductMediaType.image)
                .url("https://cdn.example/image.jpg").sortOrder(0).primary(false).build();
        when(media.findByTenantIdAndProductIdAndId(tenant, productId, mediaId)).thenReturn(Optional.of(entity));
        assertThat(service.setPrimary(productId, mediaId).primary()).isTrue();
        verify(media).clearPrimary(tenant, productId);
    }
    @Test void rejectsNonExternalUrlsAndNegativeMultipartSortOrder() {
        assertThatThrownBy(() -> service.createExternal(productId, new ProductMediaCreateRequest(
                ProductMediaType.image, "http://localhost/image.jpg", null, 0, false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_MEDIA_URL_INVALID"));
        assertThatThrownBy(() -> service.upload(productId,
                new MockMultipartFile("file", new byte[] {1}), null, -1, false))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("PRODUCT_MEDIA_SORT_INVALID"));
    }
}
