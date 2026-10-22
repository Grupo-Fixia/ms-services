package com.fixia.services.config;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Carga la clave pública RSA con la que se verifican los tokens de ms-users. */
final class JwtKeyLoader {

    private static final int GENERATED_KEY_SIZE = 2048;

    private JwtKeyLoader() {
    }

    /** Clave pública X.509 en formato PEM. */
    static RSAPublicKey fromPem(String pem) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decodePem(pem)));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            // No se incluye el contenido de la clave en el mensaje.
            throw new IllegalStateException("JWT_PUBLIC_KEY no tiene un formato PEM válido (X.509)");
        }
    }

    /**
     * Clave efímera, solo para los perfiles local y test: ningún token real de ms-users se podrá verificar con
     * ella, pero permite arrancar el servicio sin configuración.
     */
    static RSAPublicKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_KEY_SIZE);
            return (RSAPublicKey) generator.generateKeyPair().getPublic();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo generar la clave JWT efímera", e);
        }
    }

    private static byte[] decodePem(String pem) {
        // Las variables de entorno suelen traer los saltos de línea como "\n" literales.
        String normalized = pem.replace("\\n", "\n");
        StringBuilder body = new StringBuilder();
        for (String line : normalized.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("-----")) {
                body.append(trimmed);
            }
        }
        return Base64.getDecoder().decode(body.toString());
    }
}
