package com.omniretail.backend.shared.media;

import com.omniretail.backend.shared.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
public class MediaStorageService {

    public static final String SCOPE_CATEGORIES = "categories";
    public static final String SCOPE_PRODUCTS = "products";
    /** Logo e imagenes del carrusel de la tienda en linea (ownerId = tenantId). */
    public static final String SCOPE_ECOMMERCE = "ecommerce";

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern MANAGED_URL = Pattern.compile(
            "^/media/(" + UUID_PATTERN + ")/(categories|products|ecommerce)/(" + UUID_PATTERN
                    + ")/(" + UUID_PATTERN + "\\.(?:jpg|png|webp))$");

    private final Path root;
    private final Path stagingRoot;
    private final long maxSizeBytes;
    private final ImageContentProcessor imageProcessor;

    @Autowired
    public MediaStorageService(
            @Value("${app.media.storage-path:./data/media}") String storagePath,
            @Value("${app.media.max-size-bytes:5242880}") long maxSizeBytes) {
        this(storagePath, maxSizeBytes, new ImageContentProcessor());
    }

    MediaStorageService(String storagePath, long maxSizeBytes, ImageContentProcessor imageProcessor) {
        if (maxSizeBytes <= 0) throw new IllegalArgumentException("El limite de media debe ser positivo.");
        this.maxSizeBytes = maxSizeBytes;
        this.imageProcessor = java.util.Objects.requireNonNull(imageProcessor);
        try {
            Path configuredRoot = Path.of(storagePath).toAbsolutePath().normalize();
            Files.createDirectories(configuredRoot);
            if (Files.isSymbolicLink(configuredRoot)) {
                throw new IllegalStateException("El directorio de media no puede ser un enlace simbolico.");
            }
            this.root = configuredRoot.toRealPath();
            Path rootName = this.root.getFileName();
            if (rootName == null) throw new IllegalStateException("La raiz del filesystem no puede ser media.");
            Path configuredStaging = this.root.resolveSibling("." + rootName + "-staging").normalize();
            Files.createDirectories(configuredStaging);
            if (Files.isSymbolicLink(configuredStaging)) {
                throw new IllegalStateException("El staging de media no puede ser un enlace simbolico.");
            }
            this.stagingRoot = configuredStaging.toRealPath();
            if (this.stagingRoot.startsWith(this.root)) {
                throw new IllegalStateException("El staging de media debe estar fuera del arbol publico.");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo inicializar el directorio de media.", exception);
        }
    }

    public String storeImage(UUID tenantId, String scope, UUID ownerId, MultipartFile file) {
        requireDestination(tenantId, scope, ownerId);
        if (file == null || file.isEmpty()) throw invalid("La imagen es requerida.");
        if (file.getSize() > maxSizeBytes) throw tooLarge();

        Path uploaded = null;
        Path processed = null;
        Path published = null;
        boolean completed = false;
        try {
            uploaded = Files.createTempFile(stagingRoot, ".upload-", ".tmp");
            copyBounded(file, uploaded);
            ImageContentProcessor.Format detected = ImageContentProcessor.detect(uploaded);
            if (!matchesDeclaredContentType(detected, file.getContentType())) {
                throw new BusinessException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_INVALID",
                        "El tipo declarado no coincide con el contenido de la imagen.");
            }
            processed = imageProcessor.validateAndNormalize(uploaded, stagingRoot, maxSizeBytes);
            if (!processed.equals(uploaded)) uploaded = null;

            Path directory = safeResolve(tenantId.toString(), scope, ownerId.toString());
            ensureSecureDirectory(directory);
            String filename = UUID.randomUUID() + "." + detected.extension();
            Path destination = safeResolve(tenantId.toString(), scope, ownerId.toString(), filename);
            moveWithoutOverwrite(processed, destination);
            published = destination;
            processed = null;

            String url = "/media/" + tenantId + "/" + scope + "/" + ownerId + "/" + filename;
            registerRollbackCleanup(url, tenantId, scope, ownerId);
            completed = true;
            return url;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR",
                    "No se pudo almacenar la imagen.");
        } finally {
            deletePathQuietly(uploaded);
            deletePathQuietly(processed);
            if (!completed) deletePathQuietly(published);
        }
    }

    public boolean isManaged(String url) {
        return managedMatcher(url) != null;
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

    /** Verifica tenant, zona y propietario exactos; debe preceder cualquier operacion destructiva. */
    public boolean isManagedByOwner(String url, UUID tenantId, String scope, UUID ownerId) {
        Matcher matcher = managedMatcher(url);
        return matcher != null
                && tenantId != null
                && ownerId != null
                && matcher.group(1).equalsIgnoreCase(tenantId.toString())
                && matcher.group(2).equals(scope)
                && matcher.group(3).equalsIgnoreCase(ownerId.toString());
    }

    public void deleteAfterCommit(String url, UUID tenantId, String scope, UUID ownerId) {
        if (!isManagedByOwner(url, tenantId, scope, ownerId)) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteQuietly(url, tenantId, scope, ownerId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(url, tenantId, scope, ownerId);
            }
        });
    }

    public void deleteQuietly(String url, UUID tenantId, String scope, UUID ownerId) {
        if (!isManagedByOwner(url, tenantId, scope, ownerId)) return;
        try {
            Path path = pathForManagedUrl(url);
            ensureNoSymbolicLinks(path.getParent());
            if (Files.isSymbolicLink(path)) {
                log.warn("Se rechazo el borrado de un enlace simbolico de media: {}", url);
                return;
            }
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException exception) {
            log.warn("No se pudo eliminar media local administrada: {}", url, exception);
        }
    }

    public Path root() {
        return root;
    }

    Path stagingRoot() {
        return stagingRoot;
    }

    private void copyBounded(MultipartFile file, Path destination) throws IOException {
        long total = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = file.getInputStream(); OutputStream output = Files.newOutputStream(destination)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                if (total > maxSizeBytes - read) throw tooLarge();
                output.write(buffer, 0, read);
                total += read;
            }
        }
        if (total == 0) throw invalid("La imagen es requerida.");
        if (Files.size(destination) != total) {
            throw new IOException("El tamano escrito no coincide con el stream recibido.");
        }
    }

    private void registerRollbackCleanup(String url, UUID tenantId, String scope, UUID ownerId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deleteQuietly(url, tenantId, scope, ownerId);
                }
            }
        });
    }

    private static Matcher managedMatcher(String url) {
        if (url == null) return null;
        Matcher matcher = MANAGED_URL.matcher(url);
        if (!matcher.matches()) return null;
        try {
            UUID.fromString(matcher.group(1));
            UUID.fromString(matcher.group(3));
            UUID.fromString(matcher.group(4).substring(0, 36));
            return matcher;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean matchesDeclaredContentType(
            ImageContentProcessor.Format detected, String declaredContentType) {
        if (declaredContentType == null || declaredContentType.isBlank()) return false;
        try {
            String normalizedContentType = declaredContentType.trim();
            MediaType mediaType = MediaType.parseMediaType(normalizedContentType);
            if (!"image".equalsIgnoreCase(mediaType.getType())) return false;
            if (countTopLevelParameters(normalizedContentType) != mediaType.getParameters().size()) return false;
            if (mediaType.getParameters().entrySet().stream()
                    .anyMatch(parameter -> parameter.getKey().isBlank() || parameter.getValue().isBlank())) {
                return false;
            }
            String subtype = mediaType.getSubtype();
            return switch (detected) {
                case JPEG -> "jpeg".equalsIgnoreCase(subtype) || "jpg".equalsIgnoreCase(subtype);
                case PNG -> "png".equalsIgnoreCase(subtype);
                case WEBP -> "webp".equalsIgnoreCase(subtype);
            };
        } catch (InvalidMediaTypeException exception) {
            return false;
        }
    }

    private static int countTopLevelParameters(String contentType) {
        int count = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < contentType.length(); index++) {
            char character = contentType.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quoted && character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == ';') {
                count++;
            }
        }
        return quoted ? -1 : count;
    }

    private Path pathForManagedUrl(String url) {
        Matcher matcher = managedMatcher(url);
        if (matcher == null) throw invalid("URL de media no valida.");
        return safeResolve(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4));
    }

    private Path safeResolve(String... segments) {
        Path result = root;
        for (String segment : segments) {
            if (segment == null || segment.isBlank() || segment.contains("/") || segment.contains("\\")
                    || ".".equals(segment) || "..".equals(segment)) {
                throw invalid("Ruta de media no valida.");
            }
            result = result.resolve(segment);
        }
        result = result.toAbsolutePath().normalize();
        if (!result.startsWith(root)) throw invalid("Ruta de media no valida.");
        return result;
    }

    private void ensureSecureDirectory(Path directory) throws IOException {
        if (!directory.startsWith(root)) throw invalid("Ruta de media no valida.");
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid("La ruta de media contiene un enlace o archivo no permitido.");
                }
            } else {
                try {
                    Files.createDirectory(current);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Otra request pudo crear el directorio; se valida inmediatamente abajo.
                }
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid("La ruta de media contiene un enlace o archivo no permitido.");
                }
            }
        }
    }

    private void ensureNoSymbolicLinks(Path directory) throws IOException {
        if (directory == null || !directory.startsWith(root)) throw invalid("Ruta de media no valida.");
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw invalid("La ruta de media contiene un enlace simbolico.");
        }
    }

    private static void moveWithoutOverwrite(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void requireDestination(UUID tenantId, String scope, UUID ownerId) {
        if (tenantId == null || ownerId == null || !(SCOPE_CATEGORIES.equals(scope)
                || SCOPE_PRODUCTS.equals(scope) || SCOPE_ECOMMERCE.equals(scope))) {
            throw invalid("Destino de archivo no valido.");
        }
    }

    private static void deletePathQuietly(Path path) {
        if (path == null) return;
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static BusinessException tooLarge() {
        return new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE",
                "La imagen supera el tamano maximo permitido.");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "MEDIA_INVALID", message);
    }
}
