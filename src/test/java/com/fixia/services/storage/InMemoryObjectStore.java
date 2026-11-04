package com.fixia.services.storage;

import org.mockito.invocation.InvocationOnMock;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Almacén de objetos en memoria detrás de un {@code S3Client} simulado (GC-439). Se comporta como un servidor S3 real en
 * lo que importa para las evidencias: valida el SHA-256 que recibe, devuelve el ETag (MD5), falla como un almacenamiento
 * caído cuando se le pide y permite alterar o quitar objetos.
 */
public final class InMemoryObjectStore {

    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final AtomicInteger puts = new AtomicInteger();
    private volatile RuntimeException failure;
    private volatile boolean wrongEtag;

    /** Conecta el simulado de S3 a un almacén nuevo y vacío. */
    public static InMemoryObjectStore install(S3Client s3) {
        InMemoryObjectStore store = new InMemoryObjectStore();
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenAnswer(store::put);
        when(s3.getObject(any(GetObjectRequest.class))).thenAnswer(store::get);
        when(s3.deleteObject(any(DeleteObjectRequest.class))).thenAnswer(store::delete);
        return store;
    }

    private PutObjectResponse put(InvocationOnMock call) throws IOException {
        throwIfDown();
        PutObjectRequest request = call.getArgument(0);
        RequestBody body = call.getArgument(1);
        byte[] bytes = body.contentStreamProvider().newStream().readAllBytes();
        String sent = request.checksumSHA256();
        if (sent != null && !sent.equals(Base64.getEncoder().encodeToString(digest("SHA-256", bytes)))) {
            throw SdkClientException.create("BadDigest: el SHA-256 enviado no coincide con el contenido");
        }
        objects.put(request.key(), bytes);
        puts.incrementAndGet();
        String etag = wrongEtag ? "0123456789abcdef0123456789abcdef" : HexFormat.of().formatHex(digest("MD5", bytes));
        return PutObjectResponse.builder().eTag("\"" + etag + "\"").build();
    }

    private ResponseInputStream<GetObjectResponse> get(InvocationOnMock call) {
        throwIfDown();
        GetObjectRequest request = call.getArgument(0);
        byte[] bytes = objects.get(request.key());
        if (bytes == null) {
            throw NoSuchKeyException.builder().message("The specified key does not exist.").build();
        }
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) bytes.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(bytes)));
    }

    private DeleteObjectResponse delete(InvocationOnMock call) {
        DeleteObjectRequest request = call.getArgument(0);
        deleted.add(request.key());
        objects.remove(request.key());
        return DeleteObjectResponse.builder().build();
    }

    private void throwIfDown() {
        if (failure != null) {
            throw failure;
        }
    }

    /** El almacenamiento deja de responder: toda operación lanza como un cliente sin conexión. */
    public void goDown() {
        failure = SdkClientException.create("Unable to execute HTTP request: Connection refused");
    }

    public void comeBack() {
        failure = null;
    }

    /** Las cargas se guardan pero el servidor responde con un ETag que no corresponde al contenido. */
    public void answerWithWrongEtag() {
        wrongEtag = true;
    }

    /** Cambia el contenido de un objeto ya almacenado, como una alteración fuera de la aplicación. */
    public void tamper(String key, byte[] newContent) {
        objects.put(key, newContent);
    }

    public void remove(String key) {
        objects.remove(key);
    }

    public Map<String, byte[]> objects() {
        return objects;
    }

    public List<String> deleted() {
        return deleted;
    }

    public int puts() {
        return puts.get();
    }

    private static byte[] digest(String algorithm, byte[] content) {
        try {
            return MessageDigest.getInstance(algorithm).digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
