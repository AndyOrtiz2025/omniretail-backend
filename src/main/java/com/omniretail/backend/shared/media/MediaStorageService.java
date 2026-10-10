package com.omniretail.backend.shared.media;

import com.omniretail.backend.shared.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class MediaStorageService {

    /** Logo e imagenes del carrusel de la tienda en linea (ownerId = tenantId). */
    public static final String SCOPE_ECOMMERCE = "ecommerce";

    private static final Pattern MANAGED_URL = Pattern.compile(
            "^/media/([0-9a-fA-F-]{36})/(categories|products|ecommerce)/([0-9a-fA-F-]{36})/([0-9a-fA-F-]{36}\\.(?:jpg|png|webp))$");
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] WEBP_RIFF = {0x52, 0x49, 0x46, 0x46};
    private static final byte[] WEBP = {0x57, 0x45, 0x42, 0x50};

    private final Path root;
    private final long maxSizeBytes;

    public MediaStorageService(
            @Value("${app.media.storage-path:./data/media}") String storagePath,
            @Value("${app.media.max-size-bytes:5242880}") long maxSizeBytes) {
        this.root = Path.of(storagePath).toAbsolutePath().normalize();
        this.maxSizeBytes = maxSizeBytes;
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo inicializar el directorio de media.", exception);
        }
    }

    public String storeImage(UUID tenantId, String scope, UUID ownerId, MultipartFile file) {
        if (tenantId == null || ownerId == null || !("categories".equals(scope) || "products".equals(scope) || SCOPE_ECOMMERCE.equals(scope))) {
            throw invalid("Destino de archivo no valido.");
        }
        if (file == null || file.isEmpty()) {
            throw invalid("La imagen es requerida.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE",
                    "La imagen supera el tamano maximo permitido.");
        }

        DetectedImage detected = detect(file);
        if (!detected.contentType().equalsIgnoreCase(file.getContentType())) {
            throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_INVALID",
                    "El tipo declarado no coincide con el contenido de la imagen.");
        }
        Path directory = safeResolve(tenantId.toString(), scope, ownerId.toString());
        String filename = UUID.randomUUID() + "." + detected.extension();
        Path destination = safeResolve(tenantId.toString(), scope, ownerId.toString(), filename);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, ".upload-", ".tmp");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
            return "/media/" + tenantId + "/" + scope + "/" + ownerId + "/" + filename;
        } catch (IOException exception) {
            deletePathQuietly(temporary);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR",
                    "No se pudo almacenar la imagen.");
        }
    }

    public boolean isManaged(String url) {
        return url != null && MANAGED_URL.matcher(url).matches();
    }

    /** true si la URL es un archivo gestionado que pertenece al negocio indicado (en cualquier zona). */
    public boolean isManagedByTenant(String url, UUID tenantId) {
        Matcher matcher = managedMatcher(url);
        return matcher != null && tenantId != null && matcher.group(1).equalsIgnoreCase(tenantId.toString());
    }

    /** true si la URL es un archivo gestionado del negocio indicado dentro de la zona indicada. */
    public boolean isManagedIn(String url, UUID tenantId, String scope) {
        Matcher matcher = managedMatcher(url);
        return matcher != null
                && tenantId != null
                && matcher.group(1).equalsIgnoreCase(tenantId.toString())
                && matcher.group(2).equals(scope);
    }

    private static Matcher managedMatcher(String url) {
        if (url == null) return null;
        Matcher matcher = MANAGED_URL.matcher(url);
        return matcher.matches() ? matcher : null;
    }

    public void deleteAfterCommit(String url) {
        if (!isManaged(url)) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(url);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(url);
            }
        });
    }

    public void deleteQuietly(String url) {
        if (!isManaged(url)) return;
        try {
            Files.deleteIfExists(pathForManagedUrl(url));
        } catch (IOException | RuntimeException exception) {
            log.warn("No se pudo eliminar media local administrada: {}", url, exception);
        }
    }

    public Path root() {
        return root;
    }

    private DetectedImage detect(MultipartFile file) {
        byte[] header = new byte[12];
        int read;
        try (InputStream input = file.getInputStream()) {
            read = input.read(header);
        } catch (IOException exception) {
            throw invalid("No se pudo leer la imagen.");
        }
        if (startsWith(header, read, JPEG)) return new DetectedImage("jpg", "image/jpeg");
        if (startsWith(header, read, PNG)) return new DetectedImage("png", "image/png");
        if (read >= 12 && startsWith(header, read, WEBP_RIFF)
                && header[8] == WEBP[0] && header[9] == WEBP[1]
                && header[10] == WEBP[2] && header[11] == WEBP[3]) {
            return new DetectedImage("webp", "image/webp");
        }
        throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_INVALID",
                "Solo se permiten imagenes JPEG, PNG o WebP validas.");
    }

    private Path pathForManagedUrl(String url) {
        Matcher matcher = MANAGED_URL.matcher(url);
        if (!matcher.matches()) throw invalid("URL de media no valida.");
        return safeResolve(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
    }

    private Path safeResolve(String... segments) {
        Path result = root;
        for (String segment : segments) result = result.resolve(segment);
        result = result.toAbsolutePath().normalize();
        if (!result.startsWith(root)) throw invalid("Ruta de media no valida.");
        return result;
    }

    private static boolean startsWith(byte[] value, int length, byte[] prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }

    private static void deletePathQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "MEDIA_INVALID", message);
    }

    private record DetectedImage(String extension, String contentType) { }
}
