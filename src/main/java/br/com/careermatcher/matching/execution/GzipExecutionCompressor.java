package br.com.careermatcher.matching.execution;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Component
public class GzipExecutionCompressor implements ExecutionCompressor {
    @Override public String format() { return "gzip"; }
    @Override public String extension() { return ".json.gz"; }
    @Override public String contentType() { return "application/gzip"; }
    @Override public OutputStream compress(OutputStream output) throws IOException {
        return new GZIPOutputStream(output);
    }
    @Override public InputStream decompress(InputStream input) throws IOException {
        return new GZIPInputStream(input);
    }
}
