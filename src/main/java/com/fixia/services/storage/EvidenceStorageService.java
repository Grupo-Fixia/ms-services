package com.fixia.services.storage;

import com.fixia.services.config.StorageProperties;
import com.fixia.services.exception.EvidenceIntegrityException;
import com.fixia.services.exception.EvidenceNotFoundException;
import com.fixia.services.exception.InvalidEvidenceException;
import com.fixia.services.exception.StorageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Operaciones de almacenamiento de evidencias en RustFS (S3 compatible) y validaciones de formato (GC-439, RD-11).
 */
@Service
public class EvidenceStorageService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceStorageService.class);

    public static final long MAX_FILE_SIZE = 10L * 1024L * 1024L; // 10 MB

    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf"
    );

    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "application/pdf", "pdf"
    );

    private final S3Client s3Client;
    private final String bucketName;

    public EvidenceStorageService(S3Client s3Client, StorageProperties properties) {
        this.s3Client = s3Client;
        this.bucketName = properties.bucketEvidences() != null && !properties.bucketEvidences().isBlank()
                ? properties.bucketEvidences() : "evidences";
    }

    /**
     * Valida el tipo MIME permitido, el tamaño máximo y los magic bytes del archivo.
     */
    public String validateAndDetectExtension(String declaredContentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new InvalidEvidenceException("El archivo de evidencia no puede estar vacío");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new InvalidEvidenceException("El archivo excede el tamaño máximo permitido de 10 MB");
        }
        if (declaredContentType == null || !ALLOWED_MIME_TYPES.contains(declaredContentType.toLowerCase())) {
            throw new InvalidEvidenceException("Tipo de archivo no permitido. Solo se aceptan JPEG, PNG, WebP o PDF");
        }

        String mime = declaredContentType.toLowerCase();
        if (!matchesMagicBytes(mime, content)) {
            throw new InvalidEvidenceException("El contenido del archivo no coincide con el formato declarado (" + mime + ")");
        }

        return EXTENSIONS.get(mime);
    }

    /**
     * Calcula el hash SHA-256 en hexadecimal del contenido.
     */
    public String calculateSha256(byte[] content) {
        return HexFormat.of().formatHex(digest("SHA-256", content));
    }

    /**
     * Escribe el archivo en RustFS y confirma que lo almacenado es lo enviado: el servidor valida el SHA-256 que se le
     * indica y, además, el ETag devuelto (MD5 de un objeto de una sola parte) se compara con el calculado aquí.
     *
     * @throws StorageUnavailableException si el almacenamiento no responde o no puede confirmar la carga
     */
    public void upload(String objectKey, String contentType, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .contentLength((long) content.length)
                .checksumSHA256(Base64.getEncoder().encodeToString(digest("SHA-256", content)))
                .build();

        PutObjectResponse response;
        try {
            response = s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException e) {
            log.error("No se pudo escribir el objeto {} en el almacenamiento", objectKey, e);
            throw new StorageUnavailableException("No se pudo escribir en el almacenamiento", e);
        }

        String expectedEtag = HexFormat.of().formatHex(digest("MD5", content));
        String etag = response == null ? null : response.eTag();
        if (etag == null || !expectedEtag.equalsIgnoreCase(etag.replace("\"", ""))) {
            log.error("El almacenamiento no confirmó la carga de {}: ETag {} distinto de {}", objectKey, etag,
                    expectedEtag);
            throw new StorageUnavailableException("El almacenamiento no confirmó la carga del archivo");
        }
    }

    /**
     * Lee el archivo completo (como máximo {@link #MAX_FILE_SIZE}) para poder verificar su integridad antes de
     * entregarlo.
     *
     * @throws EvidenceNotFoundException   si el objeto ya no existe en el almacenamiento
     * @throws StorageUnavailableException si el almacenamiento no responde
     */
    public byte[] download(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(request)) {
            byte[] content = stream.readNBytes((int) MAX_FILE_SIZE + 1);
            if (content.length > MAX_FILE_SIZE) {
                throw new EvidenceIntegrityException("El objeto almacenado excede el tamaño permitido");
            }
            return content;
        } catch (NoSuchKeyException e) {
            log.error("La evidencia {} tiene metadatos pero no existe en el almacenamiento", objectKey);
            throw new EvidenceNotFoundException();
        } catch (SdkException | IOException e) {
            log.error("No se pudo leer el objeto {} del almacenamiento", objectKey, e);
            throw new StorageUnavailableException("No se pudo leer del almacenamiento", e);
        }
    }

    /** Borra un objeto sin propagar errores: se usa para compensar una subida cuya transacción no se confirmó. */
    public void deleteQuietly(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(objectKey).build());
        } catch (RuntimeException e) {
            log.warn("No se pudo borrar el objeto huérfano {}; queda para limpieza manual", objectKey, e);
        }
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo " + algorithm + " no disponible en la JVM", e);
        }
    }

    private boolean matchesMagicBytes(String mime, byte[] data) {
        if (data.length < 4) return false;

        return switch (mime) {
            case "image/jpeg" ->
                // JPEG empieza con FF D8 FF
                    (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;

            case "image/png" ->
                // PNG empieza con 89 50 4E 47 0D 0A 1A 0A
                    data.length >= 8 &&
                            (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G' &&
                            data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A;

            case "image/webp" ->
                // WebP empieza con RIFF....WEBP
                    data.length >= 12 &&
                            data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' &&
                            data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';

            case "application/pdf" ->
                // PDF empieza con %PDF
                    data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';

            default -> false;
        };
    }
}

