package com.fixia.services.storage;

import com.fixia.services.config.StorageProperties;
import com.fixia.services.exception.InvalidEvidenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class EvidenceStorageServiceTest {

    @Mock
    private S3Client s3Client;

    private EvidenceStorageService storageService;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(
                "http://localhost:9000", "us-east-1", "evidences", "test", "test");
        storageService = new EvidenceStorageService(s3Client, properties);
    }

    @Test
    void rechazaArchivoVacio() {
        assertThatThrownBy(() -> storageService.validateAndDetectExtension("image/jpeg", new byte[0]))
                .isInstanceOf(InvalidEvidenceException.class)
                .hasMessageContaining("vacío");
    }

    @Test
    void rechazaArchivoMayorA10MB() {
        byte[] bigFile = new byte[(int) EvidenceStorageService.MAX_FILE_SIZE + 1];
        assertThatThrownBy(() -> storageService.validateAndDetectExtension("image/jpeg", bigFile))
                .isInstanceOf(InvalidEvidenceException.class)
                .hasMessageContaining("10 MB");
    }

    @Test
    void rechazaMimeTypeNoPermitido() {
        byte[] content = new byte[]{1, 2, 3, 4, 5};
        assertThatThrownBy(() -> storageService.validateAndDetectExtension("application/zip", content))
                .isInstanceOf(InvalidEvidenceException.class)
                .hasMessageContaining("Solo se aceptan JPEG, PNG, WebP o PDF");
    }

    @Test
    void detectaMagicBytesValidosJpeg() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 10};
        String ext = storageService.validateAndDetectExtension("image/jpeg", jpeg);
        assertThat(ext).isEqualTo("jpg");
    }

    @Test
    void detectaMagicBytesValidosPng() {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2};
        String ext = storageService.validateAndDetectExtension("image/png", png);
        assertThat(ext).isEqualTo("png");
    }

    @Test
    void detectaMagicBytesValidosPdf() {
        byte[] pdf = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7'};
        String ext = storageService.validateAndDetectExtension("application/pdf", pdf);
        assertThat(ext).isEqualTo("pdf");
    }

    @Test
    void rechazaContenidoQueNoCoincideConMimeDeclarado() {
        byte[] fakePdf = new byte[]{1, 2, 3, 4, 5};
        assertThatThrownBy(() -> storageService.validateAndDetectExtension("application/pdf", fakePdf))
                .isInstanceOf(InvalidEvidenceException.class)
                .hasMessageContaining("no coincide con el formato declarado");
    }

    @Test
    void calculaHashSha256Correctamente() {
        byte[] content = "test evidence content".getBytes();
        String sha256 = storageService.calculateSha256(content);
        assertThat(sha256).hasSize(64);
        assertThat(sha256).isEqualTo("d960609511962d52cd97168d8f128849f532f33b86da2e4804b1a8385630f689");
    }
}

