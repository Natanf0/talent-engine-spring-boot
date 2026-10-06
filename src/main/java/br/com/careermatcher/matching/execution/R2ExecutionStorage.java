package br.com.careermatcher.matching.execution;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.Map;

@Component
public class R2ExecutionStorage implements ExecutionObjectStorage {
    private final ObjectProvider<S3Client> clients;
    private final R2Properties properties;
    public R2ExecutionStorage(ObjectProvider<S3Client> clients, R2Properties properties) {
        this.clients = clients; this.properties = properties;
    }
    @Override public String bucket() {
        if (properties.bucket() == null || properties.bucket().isBlank())
            throw new IllegalStateException("Configure o bucket R2.");
        return properties.bucket();
    }
    @Override public void put(String bucket, String key, Path file, String contentType) {
        clients.getObject().putObject(PutObjectRequest.builder().bucket(bucket).key(key)
                .contentType(contentType).metadata(Map.of("schema-version", "2")).build(), RequestBody.fromFile(file));
    }
    @Override public void delete(String bucket, String key) {
        clients.getObject().deleteObject(request -> request.bucket(bucket).key(key));
    }
    @Override public InputStream get(String bucket, String key) {
        return clients.getObject().getObject(request -> request.bucket(bucket).key(key));
    }
}
