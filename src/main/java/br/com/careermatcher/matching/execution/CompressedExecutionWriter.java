package br.com.careermatcher.matching.execution;

import br.com.careermatcher.DTOs.matching.ExecutionVacancy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
public class CompressedExecutionWriter {
    private final ExecutionCompressor compressor;
    private final ObjectWriter writer;
    private final Path directory;

    @Autowired
    public CompressedExecutionWriter(ObjectMapper mapper, List<ExecutionCompressor> compressors,
                                     @Value("${matching.execution.compression:gzip}") String format) {
        this(mapper, compressors, format, Path.of(System.getProperty("java.io.tmpdir")));
    }

    CompressedExecutionWriter(ObjectMapper mapper, List<ExecutionCompressor> compressors, String format, Path directory) {
        this.directory = directory;
        var matching = compressors.stream().filter(item -> item.format().equals(format)).toList();
        if (matching.size() != 1) throw new IllegalArgumentException("Formato de compactação inválido ou ambíguo: " + format);
        compressor = matching.getFirst();
        writer = mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writer();
    }

    public String format() { return compressor.format(); }
    public String extension() { return compressor.extension(); }
    public String contentType() { return compressor.contentType(); }

    public Path write(List<ExecutionVacancy> document) throws IOException {
        Path temporary = Files.createTempFile(directory, "matching-", compressor.extension());
        try {
            try (var file = Files.newOutputStream(temporary); var compressed = compressor.compress(file)) {
                writer.writeValue(compressed, document);
            }
            return temporary;
        } catch (IOException | RuntimeException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
