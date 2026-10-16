package com.omniretail.backend.shared.media;

import com.omniretail.backend.shared.exception.BusinessException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;

/** Validacion y normalizacion de contenido no confiable antes de publicarlo en {@code /media}. */
final class ImageContentProcessor {

    static final int MAX_WIDTH = 4096;
    static final int MAX_HEIGHT = 4096;
    static final long MAX_PIXELS = 16_000_000L;

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Set<String> WEBP_CHUNKS = Set.of(
            "VP8X", "VP8 ", "VP8L", "ALPH", "ICCP", "EXIF", "XMP ", "ANIM", "ANMF");
    private final ProcessingGate processingGate;

    ImageContentProcessor() {
        this(new ProcessingGate(1, 10_000));
    }

    ImageContentProcessor(ProcessingGate processingGate) {
        this.processingGate = Objects.requireNonNull(processingGate);
    }

    enum Format {
        JPEG("jpg", "image/jpeg", "JPEG"),
        PNG("png", "image/png", "PNG"),
        WEBP("webp", "image/webp", null);

        private final String extension;
        private final String contentType;
        private final String writerFormat;

        Format(String extension, String contentType, String writerFormat) {
            this.extension = extension;
            this.contentType = contentType;
            this.writerFormat = writerFormat;
        }

        String extension() { return extension; }
        String contentType() { return contentType; }
        String writerFormat() { return writerFormat; }
    }

    static Format detect(Path input) {
        byte[] header = new byte[12];
        int read;
        try (var stream = Files.newInputStream(input)) {
            read = stream.readNBytes(header, 0, header.length);
        } catch (IOException exception) {
            throw invalid("No se pudo leer la imagen.");
        }
        if (read >= 3 && unsigned(header[0]) == 0xff && unsigned(header[1]) == 0xd8
                && unsigned(header[2]) == 0xff) {
            return Format.JPEG;
        }
        if (read >= PNG_SIGNATURE.length && startsWith(header, PNG_SIGNATURE)) {
            return Format.PNG;
        }
        if (read >= 12 && ascii(header, 0, 4).equals("RIFF") && ascii(header, 8, 4).equals("WEBP")) {
            return Format.WEBP;
        }
        throw invalid("Solo se permiten imagenes JPEG, PNG o WebP validas.");
    }

    /**
     * Devuelve el archivo privado que debe moverse al destino final. JPEG y PNG se recodifican sin
     * metadata; WebP se conserva despues de validar RIFF estrictamente y decodificarlo por completo.
     */
    Path validateAndNormalize(Path input, Path stagingDirectory, long maxSizeBytes) {
        boolean acquired = false;
        Path output = null;
        BufferedImage decoded = null;
        BufferedImage normalized = null;
        BufferedImage verified = null;
        boolean outputReady = false;
        try {
            acquired = processingGate.tryAcquire();
            if (!acquired) {
                throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_PROCESSING_BUSY",
                        "El procesador de imagenes esta ocupado. Intenta nuevamente.");
            }
            byte[] bytes = Files.readAllBytes(input);
            if (bytes.length == 0) throw invalid("La imagen es requerida.");
            if (bytes.length > maxSizeBytes) throw tooLarge();

            Format format = detect(input);
            ImageInfo structure = validateStructure(format, bytes);
            validateDimensions(structure.width(), structure.height());
            decoded = decode(input, format, structure);

            if (format == Format.WEBP) {
                return input;
            }

            normalized = normalize(decoded, format, structure.orientation());
            output = Files.createTempFile(stagingDirectory, ".normalized-", ".tmp");
            writeNormalized(normalized, format, output, maxSizeBytes);
            byte[] generated = Files.readAllBytes(output);
            if (generated.length == 0) throw invalid("La imagen normalizada esta vacia.");
            if (generated.length > maxSizeBytes) throw tooLarge();
            ImageInfo generatedInfo = validateStructure(format, generated);
            validateDimensions(generatedInfo.width(), generatedInfo.height());
            verified = decode(output, format, generatedInfo);
            Files.deleteIfExists(input);
            outputReady = true;
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_PROCESSING_INTERRUPTED",
                    "No se pudo procesar la imagen.");
        } catch (SizeLimitExceededException exception) {
            throw tooLarge();
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("El archivo no contiene una imagen valida y completa.");
        } finally {
            if (!outputReady) deleteQuietly(output);
            if (verified != null) verified.flush();
            if (normalized != null && normalized != decoded) normalized.flush();
            if (decoded != null) decoded.flush();
            if (acquired) processingGate.release();
        }
    }

    private static ImageInfo validateStructure(Format format, byte[] bytes) {
        return switch (format) {
            case JPEG -> validateJpeg(bytes);
            case PNG -> validatePng(bytes);
            case WEBP -> validateWebp(bytes);
        };
    }

    private static ImageInfo validateJpeg(byte[] bytes) {
        if (bytes.length < 4 || unsigned(bytes[0]) != 0xff || unsigned(bytes[1]) != 0xd8) {
            throw invalid("El archivo JPEG no es valido.");
        }
        int position = 2;
        int width = -1;
        int height = -1;
        int orientation = 1;
        boolean scanSeen = false;

        while (position < bytes.length) {
            if (unsigned(bytes[position]) != 0xff) throw invalid("La estructura JPEG no es valida.");
            int markerStart = position;
            while (position < bytes.length && unsigned(bytes[position]) == 0xff) position++;
            if (position >= bytes.length) throw invalid("El archivo JPEG esta truncado.");
            int marker = unsigned(bytes[position++]);
            if (marker == 0xd9) {
                if (position != bytes.length || !scanSeen || width <= 0 || height <= 0) {
                    throw invalid("El final del archivo JPEG no es valido.");
                }
                return new ImageInfo(width, height, orientation);
            }
            if (marker == 0x00 || marker == 0xd8 || (marker >= 0xd0 && marker <= 0xd7)) {
                throw invalid("La estructura JPEG no es valida.");
            }
            if (marker == 0x01) continue;
            if (position + 2 > bytes.length) throw invalid("El archivo JPEG esta truncado.");
            int segmentLength = be16(bytes, position);
            if (segmentLength < 2 || position + segmentLength > bytes.length) {
                throw invalid("El archivo JPEG esta truncado.");
            }
            int dataStart = position + 2;
            int dataLength = segmentLength - 2;

            if (isStartOfFrame(marker)) {
                if (dataLength < 6) throw invalid("El encabezado JPEG no es valido.");
                height = be16(bytes, dataStart + 1);
                width = be16(bytes, dataStart + 3);
            } else if (marker == 0xe1) {
                orientation = readExifOrientation(bytes, dataStart, dataLength);
            }

            position += segmentLength;
            if (marker != 0xda) continue;
            scanSeen = true;
            boolean nextMarker = false;
            while (position < bytes.length) {
                if (unsigned(bytes[position]) != 0xff) {
                    position++;
                    continue;
                }
                markerStart = position;
                position++;
                while (position < bytes.length && unsigned(bytes[position]) == 0xff) position++;
                if (position >= bytes.length) throw invalid("El archivo JPEG esta truncado.");
                int scanMarker = unsigned(bytes[position]);
                if (scanMarker == 0x00 || (scanMarker >= 0xd0 && scanMarker <= 0xd7)) {
                    position++;
                    continue;
                }
                position = markerStart;
                nextMarker = true;
                break;
            }
            if (!nextMarker) throw invalid("El archivo JPEG no contiene EOI.");
        }
        throw invalid("El archivo JPEG no contiene EOI.");
    }

    private static ImageInfo validatePng(byte[] bytes) {
        if (bytes.length < PNG_SIGNATURE.length || !startsWith(bytes, PNG_SIGNATURE)) {
            throw invalid("El archivo PNG no es valido.");
        }
        int position = PNG_SIGNATURE.length;
        int width = -1;
        int height = -1;
        boolean ihdrSeen = false;
        boolean idatSeen = false;
        while (position < bytes.length) {
            if (position + 12 > bytes.length) throw invalid("El archivo PNG esta truncado.");
            long chunkLengthLong = be32Unsigned(bytes, position);
            if (chunkLengthLong > Integer.MAX_VALUE) throw invalid("El chunk PNG es demasiado grande.");
            int chunkLength = (int) chunkLengthLong;
            int typeStart = position + 4;
            int dataStart = position + 8;
            long endLong = (long) dataStart + chunkLength + 4L;
            if (endLong > bytes.length) throw invalid("El archivo PNG esta truncado.");
            int end = (int) endLong;
            String type = ascii(bytes, typeStart, 4);

            CRC32 crc = new CRC32();
            crc.update(bytes, typeStart, 4 + chunkLength);
            long expectedCrc = be32Unsigned(bytes, dataStart + chunkLength);
            if (crc.getValue() != expectedCrc) throw invalid("El PNG contiene un CRC invalido.");

            if (!ihdrSeen) {
                if (!"IHDR".equals(type) || chunkLength != 13) throw invalid("El PNG no inicia con IHDR.");
                long rawWidth = be32Unsigned(bytes, dataStart);
                long rawHeight = be32Unsigned(bytes, dataStart + 4);
                if (rawWidth > Integer.MAX_VALUE || rawHeight > Integer.MAX_VALUE) {
                    throw dimensionsInvalid();
                }
                width = (int) rawWidth;
                height = (int) rawHeight;
                ihdrSeen = true;
            } else if ("IHDR".equals(type)) {
                throw invalid("El PNG contiene mas de un IHDR.");
            }
            if ("IDAT".equals(type)) idatSeen = true;
            if ("IEND".equals(type)) {
                if (chunkLength != 0 || !idatSeen || end != bytes.length) {
                    throw invalid("El final del archivo PNG no es valido.");
                }
                return new ImageInfo(width, height, 1);
            }
            position = end;
        }
        throw invalid("El PNG no contiene IEND.");
    }

    private static ImageInfo validateWebp(byte[] bytes) {
        if (bytes.length < 20 || !"RIFF".equals(ascii(bytes, 0, 4))
                || !"WEBP".equals(ascii(bytes, 8, 4))) {
            throw invalid("El archivo WebP no es valido.");
        }
        long declared = le32Unsigned(bytes, 4) + 8L;
        if (declared != bytes.length) throw invalid("La longitud RIFF del WebP no coincide con su contenido.");

        int position = 12;
        int canvasWidth = -1;
        int canvasHeight = -1;
        int imageWidth = -1;
        int imageHeight = -1;
        int imageChunks = 0;
        boolean extended = false;
        boolean alphaChunk = false;
        boolean alphaFlag = false;
        boolean metadataSeen = false;

        while (position < bytes.length) {
            if (position + 8 > bytes.length) throw invalid("El archivo WebP esta truncado.");
            String type = ascii(bytes, position, 4);
            if (!WEBP_CHUNKS.contains(type)) throw invalid("El WebP contiene un chunk no autorizado.");
            long lengthLong = le32Unsigned(bytes, position + 4);
            if (lengthLong > Integer.MAX_VALUE) throw invalid("El chunk WebP es demasiado grande.");
            int length = (int) lengthLong;
            int dataStart = position + 8;
            long dataEndLong = (long) dataStart + length;
            long nextLong = dataEndLong + (length & 1);
            if (nextLong > bytes.length) throw invalid("El archivo WebP esta truncado.");
            int dataEnd = (int) dataEndLong;

            switch (type) {
                case "VP8X" -> {
                    if (position != 12 || extended || length != 10) throw invalid("El chunk VP8X no es valido.");
                    int flags = unsigned(bytes[dataStart]);
                    if ((flags & 0xc1) != 0) throw invalid("El WebP usa flags VP8X reservados.");
                    if ((flags & 0x02) != 0) throw animatedWebp();
                    extended = true;
                    alphaFlag = (flags & 0x10) != 0;
                    canvasWidth = le24(bytes, dataStart + 4) + 1;
                    canvasHeight = le24(bytes, dataStart + 7) + 1;
                }
                case "VP8 " -> {
                    if (++imageChunks > 1 || length < 10) throw invalid("El payload VP8 no es valido.");
                    if (unsigned(bytes[dataStart + 3]) != 0x9d || unsigned(bytes[dataStart + 4]) != 0x01
                            || unsigned(bytes[dataStart + 5]) != 0x2a) {
                        throw invalid("El frame VP8 no contiene una cabecera valida.");
                    }
                    imageWidth = le16(bytes, dataStart + 6) & 0x3fff;
                    imageHeight = le16(bytes, dataStart + 8) & 0x3fff;
                }
                case "VP8L" -> {
                    if (++imageChunks > 1 || length < 5 || unsigned(bytes[dataStart]) != 0x2f) {
                        throw invalid("El payload VP8L no es valido.");
                    }
                    long bits = le32Unsigned(bytes, dataStart + 1);
                    if (((bits >>> 29) & 0x7) != 0) throw invalid("La version VP8L no es valida.");
                    imageWidth = (int) (bits & 0x3fff) + 1;
                    imageHeight = (int) ((bits >>> 14) & 0x3fff) + 1;
                }
                case "ALPH" -> alphaChunk = true;
                case "ANIM", "ANMF" -> throw animatedWebp();
                case "ICCP", "EXIF", "XMP " -> metadataSeen = true;
                default -> throw invalid("El WebP contiene un chunk no autorizado.");
            }
            position = (int) nextLong;
        }

        if (position != bytes.length || imageChunks != 1 || imageWidth <= 0 || imageHeight <= 0) {
            throw invalid("El WebP no contiene una imagen valida.");
        }
        if (!extended && (alphaChunk || metadataSeen)) {
            throw invalid("El WebP simple contiene chunks extendidos.");
        }
        if (alphaChunk && (!extended || !alphaFlag)) throw invalid("El alpha WebP no coincide con VP8X.");
        if (extended && (canvasWidth != imageWidth || canvasHeight != imageHeight)) {
            throw invalid("El canvas WebP no coincide con la imagen.");
        }
        return new ImageInfo(extended ? canvasWidth : imageWidth, extended ? canvasHeight : imageHeight, 1);
    }

    private static BufferedImage decode(Path input, Format expected, ImageInfo structure) {
        AtomicBoolean warned = new AtomicBoolean(false);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(input.toFile())) {
            if (imageInput == null) throw invalid("No se pudo abrir el decoder de imagen.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw invalid("No existe un decoder para la imagen.");
            ImageReader reader = readers.next();
            try {
                String readerFormat = reader.getFormatName().toLowerCase(Locale.ROOT);
                String required = expected == Format.JPEG ? "jpeg" : expected.name().toLowerCase(Locale.ROOT);
                if (!readerFormat.contains(required) && !(expected == Format.JPEG && readerFormat.contains("jpg"))) {
                    throw invalid("El decoder detecto un formato diferente al declarado.");
                }
                reader.addIIOReadWarningListener((source, warning) -> warned.set(true));
                reader.setInput(imageInput, false, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != structure.width() || height != structure.height()) {
                    throw invalid("Las dimensiones declaradas no coinciden con la imagen decodificada.");
                }
                validateDimensions(width, height);
                ImageReadParam param = reader.getDefaultReadParam();
                BufferedImage image = reader.read(0, param);
                if (image == null || image.getWidth() != width || image.getHeight() != height || warned.get()) {
                    throw invalid("La imagen no pudo decodificarse completamente.");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("El archivo no contiene una imagen valida y completa.");
        }
    }

    private static BufferedImage normalize(BufferedImage source, Format format, int orientation) {
        boolean swap = orientation >= 5 && orientation <= 8;
        int targetWidth = swap ? source.getHeight() : source.getWidth();
        int targetHeight = swap ? source.getWidth() : source.getHeight();
        int type = format == Format.PNG && source.getColorModel().hasAlpha()
                ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D graphics = target.createGraphics();
        try {
            if (type == BufferedImage.TYPE_INT_RGB) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
            }
            graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, orientationTransform(orientation, source.getWidth(), source.getHeight()), null);
        } catch (RuntimeException exception) {
            target.flush();
            throw exception;
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static AffineTransform orientationTransform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    private static void writeNormalized(BufferedImage image, Format format, Path output, long maxSizeBytes)
            throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format.writerFormat());
        if (!writers.hasNext()) throw invalid("No existe un encoder seguro para la imagen.");
        ImageWriter writer = writers.next();
        try (OutputStream fileOutput = Files.newOutputStream(output);
                LimitedOutputStream limited = new LimitedOutputStream(fileOutput, maxSizeBytes);
                ImageOutputStream imageOutput = ImageIO.createImageOutputStream(limited)) {
            if (imageOutput == null) throw new IOException("No se pudo crear la salida ImageIO.");
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (format == Format.JPEG && parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(0.90f);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
        } finally {
            writer.dispose();
        }
    }

    private static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT
                || (long) width * (long) height > MAX_PIXELS) {
            throw dimensionsInvalid();
        }
    }

    private static int readExifOrientation(byte[] bytes, int start, int length) {
        if (length < 14 || !"Exif".equals(ascii(bytes, start, 4))
                || bytes[start + 4] != 0 || bytes[start + 5] != 0) return 1;
        int tiff = start + 6;
        boolean little;
        if (bytes[tiff] == 'I' && bytes[tiff + 1] == 'I') little = true;
        else if (bytes[tiff] == 'M' && bytes[tiff + 1] == 'M') little = false;
        else return 1;
        if (read16(bytes, tiff + 2, little) != 42) return 1;
        long ifdOffset = read32(bytes, tiff + 4, little);
        long ifdPositionLong = (long) tiff + ifdOffset;
        int limit = start + length;
        if (ifdPositionLong < tiff || ifdPositionLong + 2 > limit) return 1;
        int ifd = (int) ifdPositionLong;
        int entries = read16(bytes, ifd, little);
        int entry = ifd + 2;
        for (int index = 0; index < entries; index++, entry += 12) {
            if (entry + 12 > limit) return 1;
            if (read16(bytes, entry, little) == 0x0112
                    && read16(bytes, entry + 2, little) == 3
                    && read32(bytes, entry + 4, little) == 1) {
                int value = read16(bytes, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
    }

    private static int read16(byte[] value, int offset, boolean little) {
        return little ? le16(value, offset) : be16(value, offset);
    }

    private static long read32(byte[] value, int offset, boolean little) {
        return little ? le32Unsigned(value, offset) : be32Unsigned(value, offset);
    }

    private static int be16(byte[] value, int offset) {
        require(value, offset, 2);
        return (unsigned(value[offset]) << 8) | unsigned(value[offset + 1]);
    }

    private static int le16(byte[] value, int offset) {
        require(value, offset, 2);
        return unsigned(value[offset]) | (unsigned(value[offset + 1]) << 8);
    }

    private static long be32Unsigned(byte[] value, int offset) {
        require(value, offset, 4);
        return ((long) unsigned(value[offset]) << 24)
                | ((long) unsigned(value[offset + 1]) << 16)
                | ((long) unsigned(value[offset + 2]) << 8)
                | unsigned(value[offset + 3]);
    }

    private static long le32Unsigned(byte[] value, int offset) {
        require(value, offset, 4);
        return unsigned(value[offset])
                | ((long) unsigned(value[offset + 1]) << 8)
                | ((long) unsigned(value[offset + 2]) << 16)
                | ((long) unsigned(value[offset + 3]) << 24);
    }

    private static int le24(byte[] value, int offset) {
        require(value, offset, 3);
        return unsigned(value[offset]) | (unsigned(value[offset + 1]) << 8)
                | (unsigned(value[offset + 2]) << 16);
    }

    private static void require(byte[] value, int offset, int length) {
        if (offset < 0 || offset + length > value.length) throw invalid("La estructura de imagen esta truncada.");
    }

    private static int unsigned(byte value) { return value & 0xff; }

    private static String ascii(byte[] value, int offset, int length) {
        require(value, offset, length);
        return new String(value, offset, length, StandardCharsets.US_ASCII);
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private static BusinessException animatedWebp() {
        return new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_WEBP_ANIMATED_UNSUPPORTED",
                "Las imagenes WebP animadas no estan permitidas.");
    }

    private static BusinessException dimensionsInvalid() {
        return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "MEDIA_DIMENSIONS_INVALID",
                "Las dimensiones de la imagen no estan permitidas.");
    }

    private static BusinessException tooLarge() {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE",
                "La imagen supera el tamano maximo permitido.");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_INVALID", message);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    /** Gate aislable en tests; el bean de produccion conserva un unico permiso y diez segundos de espera. */
    static final class ProcessingGate {
        private final Semaphore semaphore;
        private final long timeoutMillis;

        ProcessingGate(int permits, long timeoutMillis) {
            if (permits <= 0 || timeoutMillis < 0) throw new IllegalArgumentException("Gate de imagen invalido.");
            this.semaphore = new Semaphore(permits, true);
            this.timeoutMillis = timeoutMillis;
        }

        boolean tryAcquire() throws InterruptedException {
            return semaphore.tryAcquire(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        void release() {
            semaphore.release();
        }
    }

    private record ImageInfo(int width, int height, int orientation) {}

    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        private LimitedOutputStream(OutputStream output, long limit) {
            super(output);
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            ensureCapacity(length);
            out.write(value, offset, length);
            written += length;
        }

        private void ensureCapacity(int length) throws SizeLimitExceededException {
            if (length < 0 || written > limit - length) throw new SizeLimitExceededException();
        }
    }

    private static final class SizeLimitExceededException extends IOException {}
}
