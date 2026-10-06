package br.com.careermatcher.matching.execution;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;

/** Implementations own the format; closing the returned stream must finish the archive. */
public interface ExecutionCompressor {
    String format();
    String extension();
    String contentType();
    OutputStream compress(OutputStream output) throws IOException;
    default InputStream decompress(InputStream input) throws IOException {
        throw new IOException("Formato de leitura não suportado: " + format());
    }
}
