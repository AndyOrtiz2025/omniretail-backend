package com.omniretail.backend.administration.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.dto.HeroBannerSlideDto;
import com.omniretail.backend.administration.entity.EcommerceConfig;
import com.omniretail.backend.administration.entity.HeroBannerConfig;
import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.administration.repository.BranchRepository;
import com.omniretail.backend.administration.repository.EcommerceConfigRepository;
import com.omniretail.backend.administration.repository.HeroBannerConfigRepository;
import com.omniretail.backend.shared.media.MediaStorageService;
import com.omniretail.backend.shared.security.AuthenticatedUser;
import com.omniretail.backend.shared.security.CurrentUser;
import com.omniretail.backend.shared.security.TenantCapabilityGuard;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

class MediaUploadCleanupServiceTest {

    @Test
    void removesStoredLogoWhenConfigurationPersistenceFails() {
        UUID tenantId = UUID.randomUUID();
        CurrentUser currentUser = currentUser(tenantId);
        EcommerceConfigRepository repository = mock(EcommerceConfigRepository.class);
        MediaStorageService storage = mock(MediaStorageService.class);
        EcommerceMediaReferences references = mock(EcommerceMediaReferences.class);
        EcommerceConfig config = new EcommerceConfig();
        config.setTenantId(tenantId);
        config.setLogoUrl("https://cdn.example/old.png");
        MockMultipartFile file = image();
        String newUrl = managedUrl(tenantId);
        IllegalStateException failure = new IllegalStateException("simulated persistence failure");
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        when(storage.storeImage(tenantId, MediaStorageService.SCOPE_ECOMMERCE, tenantId, file))
                .thenReturn(newUrl);
        when(repository.saveAndFlush(config)).thenThrow(failure);
        EcommerceConfigService service = new EcommerceConfigService(
                repository, mock(BranchRepository.class), mock(TenantCapabilityGuard.class),
                currentUser, storage, references);

        assertThatThrownBy(() -> service.uploadLogo(file)).isSameAs(failure);

        verify(storage).deleteQuietly(newUrl, tenantId, MediaStorageService.SCOPE_ECOMMERCE, tenantId);
    }

    @Test
    void removesStoredSlideWhenBannerPersistenceFails() {
        UUID tenantId = UUID.randomUUID();
        CurrentUser currentUser = currentUser(tenantId);
        HeroBannerConfigRepository repository = mock(HeroBannerConfigRepository.class);
        MediaStorageService storage = mock(MediaStorageService.class);
        EcommerceMediaReferences references = mock(EcommerceMediaReferences.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        HeroBannerConfig config = new HeroBannerConfig();
        config.setTenantId(tenantId);
        config.setSlides(jsonMapper.writeValueAsString(
                List.of(new HeroBannerSlideDto("Title", "Description", "https://cdn.example/old.png"))));
        MockMultipartFile file = image();
        String newUrl = managedUrl(tenantId);
        IllegalStateException failure = new IllegalStateException("simulated persistence failure");
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(config));
        when(storage.storeImage(tenantId, MediaStorageService.SCOPE_ECOMMERCE, tenantId, file))
                .thenReturn(newUrl);
        when(repository.saveAndFlush(config)).thenThrow(failure);
        HeroBannerConfigService service = new HeroBannerConfigService(
                repository, mock(TenantCapabilityGuard.class), currentUser, jsonMapper, storage, references);

        assertThatThrownBy(() -> service.uploadSlideImage(0, file)).isSameAs(failure);

        verify(storage).deleteQuietly(newUrl, tenantId, MediaStorageService.SCOPE_ECOMMERCE, tenantId);
    }

    private static CurrentUser currentUser(UUID tenantId) {
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.require()).thenReturn(new AuthenticatedUser(
                UUID.randomUUID(), tenantId, UserType.employee, null, null, UUID.randomUUID()));
        return currentUser;
    }

    private static MockMultipartFile image() {
        return new MockMultipartFile("file", "image.png", "image/png", new byte[] {1});
    }

    private static String managedUrl(UUID tenantId) {
        return "/media/" + tenantId + "/ecommerce/" + tenantId + "/" + UUID.randomUUID() + ".png";
    }
}
