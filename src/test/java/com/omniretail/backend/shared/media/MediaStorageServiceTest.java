package com.omniretail.backend.shared.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.omniretail.backend.shared.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class MediaStorageServiceTest {
    private static final int MAX_SIZE_BYTES = 5 * 1024 * 1024;

    @TempDir Path directory;

    @Test
    void createsCanonicalRootAndPrivateSiblingStaging() {
        Path expectedRoot = directory.resolve("missing").resolve("nested").toAbsolutePath().normalize();
        MediaStorageService service = new MediaStorageService(
                expectedRoot.resolve("..").resolve("nested").toString(), MAX_SIZE_BYTES);

        assertThat(Files.isDirectory(expectedRoot)).isTrue();
        assertThat(service.root()).isEqualTo(expectedRoot);
        assertThat(service.stagingRoot()).isDirectory();
        assertThat(service.stagingRoot()).isNotEqualTo(service.root());
        assertThat(service.stagingRoot().startsWith(service.root())).isFalse();
    }

    @Test
    void rejectsInvalidConfigurationDestinationAndEmptyFiles() {
        String storagePath = directory.resolve("invalid-config").toString();
        assertThatThrownBy(() -> new MediaStorageService(storagePath, 0))
                .isInstanceOf(IllegalArgumentException.class);

        MediaStorageService service = service();
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        MockMultipartFile empty = file("empty.png", "image/png", new byte[0]);
        assertCode("MEDIA_INVALID", () -> service.storeImage(null,
                MediaStorageService.SCOPE_PRODUCTS, ownerId, empty));
        assertCode("MEDIA_INVALID", () -> service.storeImage(tenantId,
                "../products", ownerId, empty));
        assertCode("MEDIA_INVALID", () -> service.storeImage(tenantId,
                MediaStorageService.SCOPE_PRODUCTS, null, empty));
        assertCode("MEDIA_INVALID", () -> service.storeImage(tenantId,
                MediaStorageService.SCOPE_PRODUCTS, ownerId, null));
        assertCode("MEDIA_INVALID", () -> service.storeImage(tenantId,
                MediaStorageService.SCOPE_PRODUCTS, ownerId, empty));
    }

    @Test
    void storesRealPngWithUuidAndDoesNotTrustOriginalFilename() throws Exception {
        MediaStorageService service = service();
        UUID tenantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        String url = service.storeImage(tenantId, MediaStorageService.SCOPE_PRODUCTS, productId,
                file("../../attack.exe", "image/png", TestImageFixtures.png()));

        assertThat(url).matches("^/media/" + tenantId + "/products/" + productId
                + "/[0-9a-f-]{36}\\.png$");
        Path stored = pathFor(service, url);
        assertThat(stored).isRegularFile();
        assertThat(ImageIO.read(stored.toFile())).isNotNull();
        assertStagingEmpty(service);
    }

    @Test
    void normalizesJpegAndAppliesExifOrientation() throws Exception {
        MediaStorageService service = service();
        UUID tenant = UUID.randomUUID();

        for (int orientation = 1; orientation <= 8; orientation++) {
            UUID owner = UUID.randomUUID();
            String url = service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                    file("oriented.jpg", "image/jpeg", TestImageFixtures.jpegWithOrientation(orientation)));
            var image = ImageIO.read(pathFor(service, url).toFile());
            boolean swapsDimensions = orientation >= 5;
            assertThat(image.getWidth()).isEqualTo(swapsDimensions ? 1 : 2);
            assertThat(image.getHeight()).isEqualTo(swapsDimensions ? 2 : 1);
        }

        UUID bigEndianOwner = UUID.randomUUID();
        String bigEndianUrl = service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, bigEndianOwner,
                file("big-endian.jpg", "image/jpeg", TestImageFixtures.jpegWithBigEndianOrientation(6)));
        var bigEndianImage = ImageIO.read(pathFor(service, bigEndianUrl).toFile());
        assertThat(bigEndianImage.getWidth()).isEqualTo(1);
        assertThat(bigEndianImage.getHeight()).isEqualTo(2);

        UUID invalidOwner = UUID.randomUUID();
        String invalidUrl = service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, invalidOwner,
                file("invalid-orientation.jpg", "image/jpeg", TestImageFixtures.jpegWithOrientation(9)));
        var invalidImage = ImageIO.read(pathFor(service, invalidUrl).toFile());
        assertThat(invalidImage.getWidth()).isEqualTo(2);
        assertThat(invalidImage.getHeight()).isEqualTo(1);
        assertStagingEmpty(service);
    }

    @Test
    void acceptsAndPreservesSupportedStaticWebpVariants() throws Exception {
        MediaStorageService service = service();
        List<byte[]> variants = List.of(
                TestImageFixtures.webpVp8(),
                TestImageFixtures.webpVp8l(),
                TestImageFixtures.webpVp8xAlpha());

        for (byte[] variant : variants) {
            UUID tenant = UUID.randomUUID();
            UUID owner = UUID.randomUUID();
            String url = service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                    file("image.webp", "image/webp", variant));
            assertThat(Files.readAllBytes(pathFor(service, url))).isEqualTo(variant);
        }
        assertStagingEmpty(service);
    }

    @Test
    void normalizesMimeAliasesParametersAndCaseWithoutAcceptingAmbiguousTypes() {
        MediaStorageService service = service();
        UUID tenant = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        assertThat(service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                file("alias.jpg", " image/JPG; name=photo.jpg ", TestImageFixtures.jpeg()))).endsWith(".jpg");
        assertThat(service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                file("photo.png", "IMAGE/PNG; name=photo.png", TestImageFixtures.png()))).endsWith(".png");
        assertThat(service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                file("photo.webp", "image/webp; version=1", TestImageFixtures.webpVp8()))).endsWith(".webp");

        for (String invalidMime : new String[] {null, "", "image/*", "application/octet-stream",
                "image/png-malicious", "image/png; broken"}) {
            try {
                service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                        file("photo.png", invalidMime, TestImageFixtures.png()));
                fail("MIME ambiguo aceptado: <%s>", invalidMime);
            } catch (BusinessException exception) {
                assertThat(exception.getCode()).as("codigo para MIME <%s>", invalidMime)
                        .isEqualTo("MEDIA_TYPE_INVALID");
            }
        }
    }

    @Test
    void rejectsDeclaredAndActualStreamsOverLimit() {
        MediaStorageService service = new MediaStorageService(directory.resolve("media").toString(), 1024);
        assertCode("MEDIA_TOO_LARGE", () -> service.storeImage(UUID.randomUUID(),
                MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(),
                file("photo.png", "image/png", new byte[1025])));

        byte[] actual = new byte[1025];
        System.arraycopy(TestImageFixtures.png(), 0, actual, 0, Math.min(actual.length, TestImageFixtures.png().length));
        MockMultipartFile lying = new MockMultipartFile("file", "photo.png", "image/png", actual) {
            @Override public long getSize() { return 1; }
        };
        assertCode("MEDIA_TOO_LARGE", () -> service.storeImage(UUID.randomUUID(),
                MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(), lying));
        assertStagingEmpty(service);
    }

    @Test
    void rejectsTruncatedSpoofedMismatchedAndAppendedContent() {
        MediaStorageService service = service();
        byte[] png = TestImageFixtures.png();
        byte[] jpeg = TestImageFixtures.jpeg();
        byte[] webp = TestImageFixtures.webpVp8();

        List<MockMultipartFile> invalid = List.of(
                file("short.png", "image/png", java.util.Arrays.copyOf(png, 20)),
                file("short.jpg", "image/jpeg", java.util.Arrays.copyOf(jpeg, jpeg.length - 2)),
                file("short.webp", "image/webp", java.util.Arrays.copyOf(webp, webp.length - 1)),
                file("fake.png", "image/png", "not-an-image".getBytes(StandardCharsets.UTF_8)),
                file("vector.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.UTF_8)),
                file("program.exe", "image/png", new byte[] {'M', 'Z', 0, 0}),
                file("archive.zip", "image/png", new byte[] {'P', 'K', 3, 4}),
                file("appended.png", "image/png", TestImageFixtures.append(png, (byte) 'P', (byte) 'A', (byte) 'Y')),
                file("appended.jpg", "image/jpeg", TestImageFixtures.append(jpeg, (byte) 'P', (byte) 'A', (byte) 'Y')),
                file("appended.webp", "image/webp", TestImageFixtures.append(webp, (byte) 'P', (byte) 'A', (byte) 'Y')));

        for (MockMultipartFile file : invalid) {
            assertThatThrownBy(() -> service.storeImage(UUID.randomUUID(),
                    MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(), file))
                    .isInstanceOf(BusinessException.class);
        }
        assertCode("MEDIA_TYPE_INVALID", () -> service.storeImage(UUID.randomUUID(),
                MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(),
                file("wrong.jpg", "image/jpeg", png)));
        assertStagingEmpty(service);
    }

    @Test
    void rejectsInvalidJpegMarkersSegmentsAndMissingFrameMetadata() {
        MediaStorageService service = service();
        byte[] jpeg = TestImageFixtures.jpeg();
        byte[] restartOutsideScan = jpeg.clone();
        restartOutsideScan[3] = (byte) 0xd0;
        byte[] shortSegmentLength = jpeg.clone();
        shortSegmentLength[4] = 0;
        shortSegmentLength[5] = 1;
        byte[] shortFrame = jpeg.clone();
        int frameMarker = findJpegFrameMarker(shortFrame);
        shortFrame[frameMarker + 2] = 0;
        shortFrame[frameMarker + 3] = 7;
        byte[] missingFrame = jpeg.clone();
        missingFrame[frameMarker + 1] = (byte) 0xfe;

        List<byte[]> invalid = List.of(
                new byte[] {(byte) 0xff, (byte) 0xd8, 0x01, 0x02},
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xff},
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9},
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x01},
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x00},
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x00, 0x10},
                jpegWithEscapedAndRestartEntropyBytes(),
                restartOutsideScan, shortSegmentLength, shortFrame, missingFrame);

        for (byte[] content : invalid) {
            assertCode("MEDIA_TYPE_INVALID", () -> storeJpeg(service, content));
        }
        assertStagingEmpty(service);
    }

    @Test
    void rejectsJpegMarkerFillWhenDecoderWarns() {
        MediaStorageService service = service();
        byte[] jpegWithFill = insertBytes(TestImageFixtures.jpeg(), 2, new byte[] {(byte) 0xff});

        assertCode("MEDIA_TYPE_INVALID", () -> storeJpeg(service, jpegWithFill));

        assertStagingEmpty(service);
    }

    @Test
    void rejectsInvalidWebpRiffLengthAndAnimation() {
        MediaStorageService service = service();
        byte[] wrongLength = TestImageFixtures.webpVp8().clone();
        wrongLength[4]--;
        byte[] animated = TestImageFixtures.webpVp8xAlpha().clone();
        animated[20] |= 0x02;

        assertCode("MEDIA_TYPE_INVALID", () -> storeWebp(service, wrongLength));
        assertCode("MEDIA_WEBP_ANIMATED_UNSUPPORTED", () -> storeWebp(service, animated));
        assertStagingEmpty(service);
    }

    @Test
    void rejectsInvalidWebpFlagsChunksFramesAndCanvas() {
        MediaStorageService service = service();
        byte[] reservedFlag = TestImageFixtures.webpVp8xAlpha().clone();
        reservedFlag[20] |= (byte) 0x80;
        byte[] mismatchedCanvas = TestImageFixtures.webpVp8xAlpha().clone();
        mismatchedCanvas[24] = 1;
        byte[] unknownChunk = TestImageFixtures.webpVp8().clone();
        System.arraycopy(new byte[] {'J', 'U', 'N', 'K'}, 0, unknownChunk, 12, 4);
        byte[] invalidVp8Header = TestImageFixtures.webpVp8().clone();
        invalidVp8Header[23] = 0;
        byte[] invalidVp8lHeader = TestImageFixtures.webpVp8l().clone();
        invalidVp8lHeader[20] = 0;
        byte[] excessiveChunkLength = TestImageFixtures.webpVp8().clone();
        excessiveChunkLength[19] = (byte) 0x80;
        byte[] invalidVp8lVersion = TestImageFixtures.webpVp8l().clone();
        invalidVp8lVersion[24] |= 0x20;

        for (byte[] content : List.of(reservedFlag, mismatchedCanvas, unknownChunk,
                invalidVp8Header, invalidVp8lHeader, excessiveChunkLength, invalidVp8lVersion)) {
            assertCode("MEDIA_TYPE_INVALID", () -> storeWebp(service, content));
        }
        assertStagingEmpty(service);
    }

    @Test
    void rejectsWebpExtendedChunksWithoutConsistentContainerState() {
        MediaStorageService service = service();
        byte[] simpleMetadata = insertWebpChunk(TestImageFixtures.webpVp8(), 12, "EXIF", new byte[0]);
        byte[] simpleAlpha = insertWebpChunk(TestImageFixtures.webpVp8(), 12, "ALPH", new byte[0]);
        byte[] animationChunk = insertWebpChunk(TestImageFixtures.webpVp8xAlpha(), 30, "ANIM", new byte[6]);
        byte[] duplicateExtendedHeader = insertBytes(TestImageFixtures.webpVp8xAlpha(), 30,
                java.util.Arrays.copyOfRange(TestImageFixtures.webpVp8xAlpha(), 12, 30));
        updateWebpRiffLength(duplicateExtendedHeader);
        byte[] alphaWithoutFlag = TestImageFixtures.webpVp8xAlpha().clone();
        alphaWithoutFlag[20] = 0;
        alphaWithoutFlag = insertWebpChunk(alphaWithoutFlag, 30, "ALPH", new byte[0]);
        byte[] missingImage = java.util.Arrays.copyOf(TestImageFixtures.webpVp8xAlpha(), 30);
        updateWebpRiffLength(missingImage);
        byte[] duplicateImage = insertBytes(TestImageFixtures.webpVp8(), TestImageFixtures.webpVp8().length,
                java.util.Arrays.copyOfRange(TestImageFixtures.webpVp8(), 12, TestImageFixtures.webpVp8().length));
        updateWebpRiffLength(duplicateImage);

        for (byte[] content : List.of(simpleMetadata, simpleAlpha, animationChunk, duplicateExtendedHeader,
                alphaWithoutFlag, missingImage, duplicateImage)) {
            assertThatThrownBy(() -> storeWebp(service, content)).isInstanceOf(BusinessException.class);
        }
        assertStagingEmpty(service);
    }

    @Test
    void rejectsPngCrcDuplicateHeaderMissingEndAndExcessiveChunkLength() {
        MediaStorageService service = service();
        byte[] png = TestImageFixtures.png();
        byte[] invalidCrc = png.clone();
        invalidCrc[29] ^= 1;
        byte[] duplicateHeader = insertBytes(png, 33, java.util.Arrays.copyOfRange(png, 8, 33));
        byte[] missingEnd = java.util.Arrays.copyOf(png, png.length - 12);
        byte[] excessiveChunkLength = png.clone();
        excessiveChunkLength[8] = (byte) 0x80;
        byte[] missingImageData = removePngChunk(png, "IDAT");
        byte[] wrongFirstChunk = png.clone();
        System.arraycopy(new byte[] {'I', 'D', 'A', 'T'}, 0, wrongFirstChunk, 12, 4);
        updatePngChunkCrc(wrongFirstChunk, 8);
        byte[] unsignedExcessiveWidth = png.clone();
        unsignedExcessiveWidth[16] = (byte) 0x80;
        updatePngChunkCrc(unsignedExcessiveWidth, 8);

        for (byte[] content : List.of(invalidCrc, duplicateHeader, missingEnd, excessiveChunkLength,
                missingImageData, wrongFirstChunk)) {
            assertCode("MEDIA_TYPE_INVALID", () -> storePng(service, content));
        }
        assertCode("MEDIA_DIMENSIONS_INVALID", () -> storePng(service, unsignedExcessiveWidth));
        assertStagingEmpty(service);
    }

    @Test
    void rejectsExcessiveOrInvalidPngDimensionsBeforeDecode() {
        MediaStorageService service = service();
        assertCode("MEDIA_DIMENSIONS_INVALID", () -> storePng(service,
                TestImageFixtures.pngWithDimensions(4097, 1)));
        assertCode("MEDIA_DIMENSIONS_INVALID", () -> storePng(service,
                TestImageFixtures.pngWithDimensions(4001, 4001)));
        assertCode("MEDIA_DIMENSIONS_INVALID", () -> storePng(service,
                TestImageFixtures.pngWithDimensions(0, 10)));
        assertStagingEmpty(service);
    }

    @Test
    void rejectsExcessiveJpegAndWebpDimensionsBeforeRasterAllocation() {
        MediaStorageService service = service();
        assertCode("MEDIA_DIMENSIONS_INVALID", () -> service.storeImage(UUID.randomUUID(),
                MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(),
                file("wide.jpg", "image/jpeg", TestImageFixtures.jpeg(4097, 1))));
        assertCode("MEDIA_DIMENSIONS_INVALID", () -> storeWebp(service,
                TestImageFixtures.webpVp8WithDimensions(4097, 1)));
        assertStagingEmpty(service);
    }

    @Test
    void decoderGateSerializesConcurrentOperationsAndTimesOutDeterministically() throws Exception {
        ImageContentProcessor.ProcessingGate waitingGate =
                new ImageContentProcessor.ProcessingGate(1, 1_000);
        assertThat(waitingGate.tryAcquire()).isTrue();
        CountDownLatch contenderStarted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> contender = pool.submit(() -> {
                contenderStarted.countDown();
                boolean acquired = waitingGate.tryAcquire();
                if (acquired) waitingGate.release();
                return acquired;
            });
            assertThat(contenderStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(contender).isNotDone();
            waitingGate.release();
            assertThat(contender.get(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        ImageContentProcessor.ProcessingGate saturatedGate =
                new ImageContentProcessor.ProcessingGate(1, 20);
        assertThat(saturatedGate.tryAcquire()).isTrue();
        MediaStorageService saturated = service(saturatedGate);
        try {
            assertThatThrownBy(() -> storePng(saturated, TestImageFixtures.png()))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getCode()).isEqualTo("MEDIA_PROCESSING_BUSY");
                        assertThat(exception.getStatus().value()).isEqualTo(503);
                    });
        } finally {
            saturatedGate.release();
        }
        storePng(saturated, TestImageFixtures.png());
    }

    @Test
    void decoderGateHandlesInterruptionAndReleasesPermitAfterSuccessAndFailure() throws Exception {
        ImageContentProcessor.ProcessingGate interruptGate =
                new ImageContentProcessor.ProcessingGate(1, 5_000);
        assertThat(interruptGate.tryAcquire()).isTrue();
        MediaStorageService interruptedService = service(interruptGate);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptedFlag = new AtomicBoolean();
        Thread contender = Thread.ofPlatform().start(() -> {
            contenderStarted.countDown();
            try {
                storePng(interruptedService, TestImageFixtures.png());
            } catch (Throwable exception) {
                failure.set(exception);
                interruptedFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        assertThat(contenderStarted.await(1, TimeUnit.SECONDS)).isTrue();
        contender.interrupt();
        contender.join(1_000);
        interruptGate.release();
        assertThat(contender.isAlive()).isFalse();
        assertThat(failure.get()).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("MEDIA_PROCESSING_INTERRUPTED"));
        assertThat(interruptedFlag).isTrue();

        ImageContentProcessor.ProcessingGate reusableGate =
                new ImageContentProcessor.ProcessingGate(1, 0);
        MediaStorageService reusable = service(reusableGate);
        assertCode("MEDIA_TYPE_INVALID", () -> storePng(reusable,
                java.util.Arrays.copyOf(TestImageFixtures.png(), 20)));
        storePng(reusable, TestImageFixtures.png());
        storePng(reusable, TestImageFixtures.png());
    }

    @Test
    void cleansPrivateStagingWhenInputStreamFails() {
        MediaStorageService service = service();
        byte[] png = TestImageFixtures.png();
        MockMultipartFile failing = new MockMultipartFile("file", "photo.png", "image/png", png) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    int position;
                    @Override public int read() throws IOException {
                        if (position >= 16) throw new IOException("simulated");
                        return png[position++] & 0xff;
                    }
                };
            }
        };

        assertCode("MEDIA_STORAGE_ERROR", () -> service.storeImage(UUID.randomUUID(),
                MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(), failing));
        assertStagingEmpty(service);
    }

    @Test
    void acceptsInputStreamThatTemporarilyReturnsZeroBytes() {
        MediaStorageService service = service();
        byte[] png = TestImageFixtures.png();
        MockMultipartFile temporarilyEmpty = new MockMultipartFile("file", "photo.png", "image/png", png) {
            @Override
            public InputStream getInputStream() {
                return new ByteArrayInputStream(png) {
                    private boolean zeroReturned;

                    @Override
                    public int read(byte[] buffer, int offset, int length) {
                        if (!zeroReturned) {
                            zeroReturned = true;
                            return 0;
                        }
                        return super.read(buffer, offset, length);
                    }
                };
            }
        };

        storePng(service, temporarilyEmpty);

        assertStagingEmpty(service);
    }

    @Test
    void cleansPrivateStagingWhenProcessorThrowsUnexpectedRuntimeException() {
        ImageContentProcessor processor = mock(ImageContentProcessor.class);
        MediaStorageService service = new MediaStorageService(
                directory.resolve("media-runtime").toString(), MAX_SIZE_BYTES, processor);
        when(processor.validateAndNormalize(any(Path.class), any(Path.class), anyLong()))
                .thenAnswer(invocation -> {
                    Path uploaded = invocation.getArgument(0);
                    assertThat(uploaded).isRegularFile();
                    assertThat(uploaded.getParent()).isEqualTo(service.stagingRoot());
                    throw new IllegalStateException("simulated unexpected decoder failure");
                });

        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        MockMultipartFile image = file("photo.png", "image/png", TestImageFixtures.png());
        assertThatThrownBy(() -> service.storeImage(
                tenantId, MediaStorageService.SCOPE_PRODUCTS, ownerId, image))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated unexpected decoder failure");
        assertStagingEmpty(service);
    }

    @Test
    void rollbackDeletesNewFileAndCommitKeepsIt() {
        MediaStorageService service = service();
        UUID tenant = UUID.randomUUID();
        UUID owner = UUID.randomUUID();

        TransactionSynchronizationManager.initSynchronization();
        String rolledBack;
        try {
            rolledBack = service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                    file("photo.png", "image/png", TestImageFixtures.png()));
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(pathFor(service, rolledBack)).doesNotExist();

        TransactionSynchronizationManager.initSynchronization();
        String committed;
        try {
            committed = service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS, owner,
                    file("photo.png", "image/png", TestImageFixtures.png()));
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertThat(pathFor(service, committed)).isRegularFile();
    }

    @Test
    void destructiveOperationsRequireExactTenantScopeAndOwner() throws Exception {
        MediaStorageService service = service();
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID otherCategory = UUID.randomUUID();
        String url = service.storeImage(tenant, MediaStorageService.SCOPE_CATEGORIES, category,
                file("photo.png", "image/png", TestImageFixtures.png()));
        Path stored = pathFor(service, url);

        assertThat(service.isManagedByOwner(url, tenant, MediaStorageService.SCOPE_CATEGORIES, category)).isTrue();
        service.deleteQuietly(url, otherTenant, MediaStorageService.SCOPE_CATEGORIES, category);
        service.deleteQuietly(url, tenant, MediaStorageService.SCOPE_CATEGORIES, otherCategory);
        service.deleteQuietly(url, tenant, MediaStorageService.SCOPE_PRODUCTS, category);
        assertThat(stored).exists();

        service.deleteQuietly(url, tenant, MediaStorageService.SCOPE_CATEGORIES, category);
        assertThat(stored).doesNotExist();
    }

    @Test
    void ownershipPredicatesAndImmediateDeletionRequireExactScope() {
        MediaStorageService service = service();
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String url = service.storeImage(tenantId, MediaStorageService.SCOPE_PRODUCTS, ownerId,
                file("photo.png", "image/png", TestImageFixtures.png()));
        Path stored = pathFor(service, url);

        assertThat(service.isManagedByTenant(url, tenantId)).isTrue();
        assertThat(service.isManagedByTenant(url, null)).isFalse();
        assertThat(service.isManagedIn(url, tenantId, MediaStorageService.SCOPE_PRODUCTS)).isTrue();
        assertThat(service.isManagedIn(url, tenantId, MediaStorageService.SCOPE_CATEGORIES)).isFalse();
        assertThat(service.isManagedIn("https://example.invalid/image.png", tenantId,
                MediaStorageService.SCOPE_PRODUCTS)).isFalse();

        service.deleteAfterCommit(url, tenantId, MediaStorageService.SCOPE_CATEGORIES, ownerId);
        assertThat(stored).isRegularFile();
        service.deleteAfterCommit(url, tenantId, MediaStorageService.SCOPE_PRODUCTS, ownerId);
        assertThat(stored).doesNotExist();
    }

    @Test
    void rejectsPublicationWhenTenantPathIsARegularFile() throws IOException {
        MediaStorageService service = service();
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Files.writeString(service.root().resolve(tenantId.toString()), "not a directory");

        assertCode("MEDIA_INVALID", () -> service.storeImage(tenantId,
                MediaStorageService.SCOPE_PRODUCTS, ownerId,
                file("photo.png", "image/png", TestImageFixtures.png())));

        assertStagingEmpty(service);
    }

    @Test
    void ignoresTraversalAndRejectsSymlinkedTenantDirectoryWhenSupported() throws Exception {
        MediaStorageService service = service();
        Path outsideFile = directory.resolve("must-remain.txt");
        Files.writeString(outsideFile, "safe");
        service.deleteQuietly("/media/../../must-remain.txt", UUID.randomUUID(),
                MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID());
        assertThat(outsideFile).exists();

        UUID tenant = UUID.randomUUID();
        Path outsideDirectory = directory.resolve("outside");
        Files.createDirectories(outsideDirectory);
        Path link = service.root().resolve(tenant.toString());
        try {
            Files.createSymbolicLink(link, outsideDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.assumeTrue(false, "El entorno no permite crear symlinks: " + exception.getMessage());
        }
        assertCode("MEDIA_INVALID", () -> service.storeImage(tenant, MediaStorageService.SCOPE_PRODUCTS,
                UUID.randomUUID(), file("photo.png", "image/png", TestImageFixtures.png())));
        assertThat(Files.list(outsideDirectory).findAny()).isEmpty();
    }

    private MediaStorageService service() {
        return new MediaStorageService(directory.resolve("media").toString(), MAX_SIZE_BYTES);
    }

    private MediaStorageService service(ImageContentProcessor.ProcessingGate gate) {
        return new MediaStorageService(directory.resolve("media-" + UUID.randomUUID()).toString(),
                MAX_SIZE_BYTES, new ImageContentProcessor(gate));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static Path pathFor(MediaStorageService service, String url) {
        return service.root().resolve(url.substring("/media/".length()));
    }

    private static void storePng(MediaStorageService service, byte[] content) {
        service.storeImage(UUID.randomUUID(), MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(),
                file("photo.png", "image/png", content));
    }

    private static void storePng(MediaStorageService service, MockMultipartFile file) {
        service.storeImage(UUID.randomUUID(), MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(), file);
    }

    private static void storeWebp(MediaStorageService service, byte[] content) {
        service.storeImage(UUID.randomUUID(), MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(),
                file("photo.webp", "image/webp", content));
    }

    private static void storeJpeg(MediaStorageService service, byte[] content) {
        service.storeImage(UUID.randomUUID(), MediaStorageService.SCOPE_PRODUCTS, UUID.randomUUID(),
                file("photo.jpg", "image/jpeg", content));
    }

    private static int findJpegFrameMarker(byte[] jpeg) {
        for (int index = 2; index + 3 < jpeg.length; index++) {
            int marker = jpeg[index + 1] & 0xff;
            if ((jpeg[index] & 0xff) == 0xff && marker >= 0xc0 && marker <= 0xcf
                    && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                return index;
            }
        }
        throw new AssertionError("Fixture JPEG sin marcador SOF");
    }

    private static byte[] insertBytes(byte[] value, int position, byte[] inserted) {
        byte[] result = new byte[value.length + inserted.length];
        System.arraycopy(value, 0, result, 0, position);
        System.arraycopy(inserted, 0, result, position, inserted.length);
        System.arraycopy(value, position, result, position + inserted.length, value.length - position);
        return result;
    }

    private static byte[] jpegWithEscapedAndRestartEntropyBytes() {
        return new byte[] {
            (byte) 0xff, (byte) 0xd8,
            (byte) 0xff, (byte) 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            (byte) 0xff, (byte) 0xda, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3f, 0x00,
            0x01, (byte) 0xff, 0x00, (byte) 0xff, (byte) 0xd0, 0x02,
            (byte) 0xff, (byte) 0xd9
        };
    }

    private static byte[] insertWebpChunk(byte[] webp, int position, String type, byte[] payload) {
        int paddedLength = payload.length + (payload.length & 1);
        byte[] chunk = new byte[8 + paddedLength];
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, chunk, 0, 4);
        putLittleEndianInt(chunk, 4, payload.length);
        System.arraycopy(payload, 0, chunk, 8, payload.length);
        byte[] result = insertBytes(webp, position, chunk);
        updateWebpRiffLength(result);
        return result;
    }

    private static void updateWebpRiffLength(byte[] webp) {
        putLittleEndianInt(webp, 4, webp.length - 8);
    }

    private static void putLittleEndianInt(byte[] value, int offset, int number) {
        value[offset] = (byte) number;
        value[offset + 1] = (byte) (number >>> 8);
        value[offset + 2] = (byte) (number >>> 16);
        value[offset + 3] = (byte) (number >>> 24);
    }

    private static byte[] removePngChunk(byte[] png, String type) {
        for (int position = 8; position + 12 <= png.length;) {
            int length = ((png[position] & 0xff) << 24) | ((png[position + 1] & 0xff) << 16)
                    | ((png[position + 2] & 0xff) << 8) | (png[position + 3] & 0xff);
            int end = position + 12 + length;
            String actual = new String(png, position + 4, 4, StandardCharsets.US_ASCII);
            if (type.equals(actual)) {
                byte[] result = new byte[png.length - (end - position)];
                System.arraycopy(png, 0, result, 0, position);
                System.arraycopy(png, end, result, position, png.length - end);
                return result;
            }
            position = end;
        }
        throw new AssertionError("Fixture PNG sin chunk " + type);
    }

    private static void updatePngChunkCrc(byte[] png, int chunkStart) {
        int length = ((png[chunkStart] & 0xff) << 24) | ((png[chunkStart + 1] & 0xff) << 16)
                | ((png[chunkStart + 2] & 0xff) << 8) | (png[chunkStart + 3] & 0xff);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(png, chunkStart + 4, length + 4);
        int crcOffset = chunkStart + 8 + length;
        int checksum = (int) crc.getValue();
        png[crcOffset] = (byte) (checksum >>> 24);
        png[crcOffset + 1] = (byte) (checksum >>> 16);
        png[crcOffset + 2] = (byte) (checksum >>> 8);
        png[crcOffset + 3] = (byte) checksum;
    }

    private static void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(code));
    }

    private static void assertStagingEmpty(MediaStorageService service) {
        try (var files = Files.list(service.stagingRoot())) {
            assertThat(files).isEmpty();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }
}
