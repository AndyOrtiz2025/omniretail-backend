package com.omniretail.backend.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omniretail.backend.administration.entity.UserType;
import com.omniretail.backend.catalog.entity.Category;
import com.omniretail.backend.catalog.repository.CategoryRepository;
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
        category = Category.builder().name("Cat").slug("cat").imageUrl("https://cdn.example/old.jpg").build();
        category.setTenantId(tenantId); ReflectionTestUtils.setField(category, "id", categoryId);
        when(repository.findForUpdateByTenantIdAndId(tenantId, categoryId)).thenReturn(Optional.of(category));
        when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void uploadReplacesReferenceAndSchedulesOldCleanup() {
        String local = "/media/" + tenantId + "/categories/" + categoryId + "/" + UUID.randomUUID() + ".png";
        MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
        when(storage.storeImage(tenantId, "categories", categoryId, file)).thenReturn(local);
        assertThat(service.uploadImage(categoryId, file).imageUrl()).isEqualTo(local);
        verify(storage).deleteAfterCommit("https://cdn.example/old.jpg");
    }

    @Test void deleteOnlyClearsDatabaseReferenceAndSchedulesCleanup() {
        assertThat(service.deleteImage(categoryId).imageUrl()).isNull();
        verify(storage).deleteAfterCommit("https://cdn.example/old.jpg");
    }
}
