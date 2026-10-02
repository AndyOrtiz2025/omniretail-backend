package com.omniretail.backend.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.shared.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class MediaStorageServiceTest {
    private static final int MAX_SIZE_BYTES = 5 * 1024 * 1024;

    @TempDir Path directory;

    @Test
    void createsMissingNormalizedRootDuringInitialization() {
        Path expectedRoot = directory.resolve("missing").resolve("nested").toAbsolutePath().normalize();
        assertThat(Files.notExists(expectedRoot)).isTrue();

        MediaStorageService service = new MediaStorageService(
                expectedRoot.resolve("..").resolve("nested").toString(), MAX_SIZE_BYTES);

        assertThat(Files.isDirectory(expectedRoot)).isTrue();
        assertThat(service.root()).isEqualTo(expectedRoot);
    }

    @Test
    void storesDetectedPngBelowTenantRootAndNeverTrustsFilename() throws Exception {
        MediaStorageService service = new MediaStorageService(directory.toString(), 1024);
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
        UUID tenantId = UUID.randomUUID(); UUID productId = UUID.randomUUID();

        String url = service.storeImage(tenantId, "products", productId,
                new MockMultipartFile("file", "../../attack.exe", "image/png", png));

        assertThat(url).matches("^/media/" + tenantId + "/products/" + productId + "/[0-9a-f-]+\\.png$");
        assertThat(Files.exists(directory.resolve(url.substring("/media/".length())))).isTrue();
    }

    @Test
    void rejectsOversizedImages() {
        MediaStorageService service = new MediaStorageService(directory.toString(), MAX_SIZE_BYTES);
        assertThatThrownBy(() -> service.storeImage(UUID.randomUUID(), "products", UUID.randomUUID(),
                new MockMultipartFile("file", "photo.png", "image/png", new byte[MAX_SIZE_BYTES + 1])))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("MEDIA_TOO_LARGE"));
    }

    @Test
    void rejectsSpoofedImagesBelowSizeLimit() {
        MediaStorageService service = new MediaStorageService(directory.toString(), MAX_SIZE_BYTES);
        assertThatThrownBy(() -> service.storeImage(UUID.randomUUID(), "products", UUID.randomUUID(),
                new MockMultipartFile("file", "photo.png", "image/png",
                        "not-an-image".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("MEDIA_TYPE_INVALID"));
    }

    @Test
    void ignoresTraversalUrls() throws Exception {
        MediaStorageService service = new MediaStorageService(directory.toString(), MAX_SIZE_BYTES);
        Path outside = directory.getParent().resolve("must-remain.txt");
        Files.writeString(outside, "safe");
        service.deleteQuietly("/media/../../must-remain.txt");
        assertThat(Files.exists(outside)).isTrue();
        Files.deleteIfExists(outside);
    }
}
