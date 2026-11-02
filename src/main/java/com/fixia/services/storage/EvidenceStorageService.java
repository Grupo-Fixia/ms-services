package com.fixia.services.storage;

import com.fixia.services.config.StorageProperties;
import com.fixia.services.exception.InvalidEvidenceException;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Operaciones de almacenamiento de evidencias en RustFS (S3 compatible) y validaciones de formato (GC-439, RD-11).
 */
@Service
public class EvidenceStorageService {

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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 no disponible en la JVM", e);
        }
    }

    /**
     * Escribe el archivo en RustFS.
     */
    public void upload(String objectKey, String contentType, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(contentType)
                .contentLength((long) content.length)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(content));
    }

    /**
     * Obtiene el stream del archivo almacenado en RustFS.
     */
    public ResponseInputStream<GetObjectResponse> download(String objectKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

        return s3Client.getObject(request);
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

