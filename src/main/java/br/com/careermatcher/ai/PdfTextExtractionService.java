package br.com.careermatcher.ai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

@Service
public class PdfTextExtractionService {
    private final AiExtractionProperties properties;
    public PdfTextExtractionService(AiExtractionProperties properties) { this.properties = properties; }

    public String extract(MultipartFile file) {
        if (file == null || file.isEmpty()) throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "Envie um PDF com conteúdo.");
        if (file.getSize() > properties.maxPdfBytes()) throw tooLarge();
        try {
            byte[] bytes;
            try (var input = file.getInputStream()) {
                bytes = input.readNBytes((int) properties.maxPdfBytes() + 1);
            }
            if (bytes.length > properties.maxPdfBytes()) throw tooLarge();
            String header = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1);
            if (!header.contains("%PDF-")) throw failure(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "O arquivo deve ser um PDF válido.");
            try (var document = Loader.loadPDF(bytes)) {
                if (document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent())
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "Envie um PDF sem proteção ou criptografia.");
                if (document.getNumberOfPages() > properties.maxPages())
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "O PDF deve ter no máximo " + properties.maxPages() + " páginas.");
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setPageEnd("\n\n");
                var writer = new LimitedWriter(properties.maxTextCharacters());
                stripper.writeText(document, writer);
                String text = normalize(writer.toString());
                if (text.codePoints().filter(Character::isLetterOrDigit).count() < 20)
                    throw failure(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Não foi possível extrair texto. Envie um PDF com texto selecionável ou preencha manualmente.");
                return text;
            }
        } catch (TextLimitException exception) {
            throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "O texto excede o limite de " + properties.maxTextCharacters() + " caracteres.");
        } catch (IOException exception) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "Não foi possível ler o PDF. Confira se está íntegro e sem senha.");
        }
    }

    public String normalizeText(String text) {
        if (text == null || text.isBlank()) throw failure(HttpStatus.BAD_REQUEST, "Informe um texto ou um PDF.");
        if (text.length() > properties.maxTextCharacters())
            throw failure(HttpStatus.PAYLOAD_TOO_LARGE, "O texto excede o limite de " + properties.maxTextCharacters() + " caracteres.");
        String normalized = normalize(text);
        if (normalized.isBlank()) throw failure(HttpStatus.BAD_REQUEST, "Informe um texto com conteúdo.");
        return normalized;
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a0', ' ')
                .replaceAll("[\\p{Cc}&&[^\n\t]]", "")
                .replaceAll("[ \t]+", " ").replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n").strip();
    }
    private ResponseStatusException tooLarge() {
        return failure(HttpStatus.PAYLOAD_TOO_LARGE, "O PDF excede o limite de " + properties.maxPdfBytes() + " bytes (padrão: 5 MB).");
    }
    private static ResponseStatusException failure(HttpStatus status, String message) { return new ResponseStatusException(status, message); }
    private static class TextLimitException extends IOException {}
    private static final class LimitedWriter extends Writer {
        private final StringBuilder value = new StringBuilder();
        private final int limit;
        LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if ((long) value.length() + length > limit) throw new TextLimitException();
            value.append(chars, offset, length);
        }
        @Override public void flush() {}
        @Override public void close() {}
        @Override public String toString() { return value.toString(); }
    }
}
