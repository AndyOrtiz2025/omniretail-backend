package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.dto.CategoryCreateRequest;
import com.omniretail.backend.catalog.dto.CategoryUpdateRequest;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.entity.CategoryStatus;
import com.omniretail.backend.catalog.repository.CategoryRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CategoryMediaServiceTest {
    @Mock CategoryRepository repository;
    @Mock MediaStorageService storage;
    @Mock CurrentUser currentUser;
    @InjectMocks CategoryService service;
    UUID tenantId = UUID.randomUUID(); UUID categoryId = UUID.randomUUID();
    Category category;

    @BeforeEach void setUp() {
        when(currentUser.require()).thenReturn(new AuthenticatedUser(
                UUID.randomUUID(), tenantId, UserType.employee, null, null, UUID.randomUUID()));
        category = Category.builder().name("Cat").slug("cat").imageUrl("https://cdn.example/old.jpg")
                .status(CategoryStatus.active).build();
        category.setTenantId(tenantId); ReflectionTestUtils.setField(category, "id", categoryId);
        when(repository.findForUpdateByTenantIdAndId(tenantId, categoryId)).thenReturn(Optional.of(category));
        lenient().when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void uploadReplacesReferenceAndSchedulesOldCleanup() {
        String local = "/media/" + tenantId + "/categories/" + categoryId + "/" + UUID.randomUUID() + ".png";
        MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
        when(storage.storeImage(tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId, file))
                .thenReturn(local);
        assertThat(service.uploadImage(categoryId, file).imageUrl()).isEqualTo(local);
        verify(storage).deleteAfterCommit(
                "https://cdn.example/old.jpg", tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId);
    }

    @Test void deleteOnlyClearsDatabaseReferenceAndSchedulesCleanup() {
        assertThat(service.deleteImage(categoryId).imageUrl()).isNull();
        verify(storage).deleteAfterCommit(
                "https://cdn.example/old.jpg", tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId);
    }

    @Test void updateAcceptsOnlyManagedImageOwnedByTheSameCategory() {
        String owned = managed(tenantId, "categories", categoryId);
        when(storage.isManaged(owned)).thenReturn(true);
        when(storage.isManagedByOwner(owned, tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId))
                .thenReturn(true);

        assertThat(service.update(categoryId, update(owned)).imageUrl()).isEqualTo(owned);
        verify(storage).deleteAfterCommit(
                "https://cdn.example/old.jpg", tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId);
    }

    @Test void updateRejectsAnotherCategoryProductAndTenantManagedImages() {
        String otherCategory = managed(tenantId, "categories", UUID.randomUUID());
        String product = managed(tenantId, "products", UUID.randomUUID());
        String otherTenant = managed(UUID.randomUUID(), "categories", categoryId);

        for (String malicious : new String[] {otherCategory, product, otherTenant}) {
            when(storage.isManaged(malicious)).thenReturn(true);
            assertThatThrownBy(() -> service.update(categoryId, update(malicious)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST));
        }
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void deletingPreviouslyContaminatedReferenceCannotAuthorizeForeignDeletion() {
        String contaminated = managed(UUID.randomUUID(), "products", UUID.randomUUID());
        category.setImageUrl(contaminated);

        assertThat(service.deleteImage(categoryId).imageUrl()).isNull();
        verify(storage).deleteAfterCommit(
                contaminated, tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId);
    }

    @Test void persistenceFailureDeletesNewFileButKeepsOldReferenceScheduledForNoDeletion() {
        String local = managed(tenantId, "categories", categoryId);
        MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
        when(storage.storeImage(tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId, file))
                .thenReturn(local);
        when(repository.saveAndFlush(any())).thenThrow(new IllegalStateException("simulated persistence failure"));

        assertThatThrownBy(() -> service.uploadImage(categoryId, file))
                .isInstanceOf(IllegalStateException.class);
        verify(storage).deleteQuietly(
                local, tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId);
        verify(storage, never()).deleteAfterCommit(
                "https://cdn.example/old.jpg", tenantId, MediaStorageService.SCOPE_CATEGORIES, categoryId);
    }

    @Test void externalUrlsRemainSupportedAndCreateRejectsManagedReferences() {
        String external = "https://cdn.example/category.jpg";
        assertThat(service.update(categoryId, update(external)).imageUrl()).isEqualTo(external);

        String managed = managed(tenantId, "categories", categoryId);
        when(storage.isManaged(managed)).thenReturn(true);
        assertThatThrownBy(() -> service.create(new CategoryCreateRequest(
                null, "Other", "other", null, managed, CategoryStatus.active)))
                .isInstanceOf(BusinessException.class);
    }

    private CategoryUpdateRequest update(String imageUrl) {
        return new CategoryUpdateRequest(null, "Cat", null, null, imageUrl, CategoryStatus.active);
    }

    private static String managed(UUID tenant, String scope, UUID owner) {
        return "/media/" + tenant + "/" + scope + "/" + owner + "/" + UUID.randomUUID() + ".png";
    }
}
