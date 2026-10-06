package br.com.careermatcher.matching.execution;

import java.nio.file.Path;
import java.io.InputStream;

public interface ExecutionObjectStorage {
    String bucket();
    void put(String bucket, String key, Path file, String contentType);
    void delete(String bucket, String key);
    default InputStream get(String bucket, String key) {
        throw new UnsupportedOperationException("Leitura de execuções não implementada pelo armazenamento.");
    }
}
