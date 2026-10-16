package com.omniretail.backend.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.omniretail.backend.shared.exception.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageContentProcessorTest {

    @TempDir Path directory;

    @Test
    void acceptsProgressiveJpegWithMultipleScans() throws IOException {
        Path input = Files.write(directory.resolve("upload.tmp"), TestImageFixtures.progressiveJpeg());

        Path normalized = new ImageContentProcessor().validateAndNormalize(input, directory, 5 * 1024 * 1024L);

        assertThat(input).doesNotExist();
        assertThat(normalized).isRegularFile();
        assertThat(javax.imageio.ImageIO.read(normalized.toFile())).isNotNull();
    }

    @Test
    void removesNormalizedTemporaryFileWhenEncodedOutputExceedsLimit() throws IOException {
        byte[] jpeg = TestImageFixtures.noisyJpeg(128, 128);
        Path input = Files.write(directory.resolve("upload.tmp"), jpeg);
        ImageContentProcessor processor = new ImageContentProcessor();

        assertCode("MEDIA_TOO_LARGE", () -> processor.validateAndNormalize(input, directory, jpeg.length));

        assertThat(input).isRegularFile();
        try (var files = Files.list(directory)) {
            assertThat(files).containsExactly(input);
        }
    }

    @Test
    void reportsUnreadableDetectionInputAsInvalidMedia() {
        assertCode("MEDIA_TYPE_INVALID", () -> ImageContentProcessor.detect(directory));
    }

    @Test
    void rejectsEmptyOversizeAndUnreadableProcessorInputs() throws IOException {
        ImageContentProcessor processor = new ImageContentProcessor();
        Path empty = Files.createFile(directory.resolve("empty.tmp"));
        assertCode("MEDIA_TYPE_INVALID", () -> processor.validateAndNormalize(empty, directory, 1024));

        byte[] png = TestImageFixtures.png();
        Path oversized = Files.write(directory.resolve("oversized.tmp"), png);
        assertCode("MEDIA_TOO_LARGE", () -> processor.validateAndNormalize(oversized, directory, png.length - 1));

        assertCode("MEDIA_TYPE_INVALID", () -> processor.validateAndNormalize(directory, directory, 1024));
    }

    @Test
    void rejectsInvalidProcessingGateConfiguration() {
        assertThatThrownBy(() -> new ImageContentProcessor.ProcessingGate(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImageContentProcessor.ProcessingGate(1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }
}
