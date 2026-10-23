package br.com.careermatcher.ai;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PdfTextExtractionServiceTest {
    private final PdfTextExtractionService service = new PdfTextExtractionService(AiTestSupport.properties());
    static byte[] pdf(String text, int pages, boolean encrypted) throws IOException {
        try (var document = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); document.addPage(page);
                if (text != null) try (var content = new PDPageContentStream(document, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700); content.showText(text); content.endText();
                }
            }
            if (encrypted) document.protect(new StandardProtectionPolicy("owner", "", new AccessPermission()));
            document.save(out); return out.toByteArray();
        }
    }
    static MockMultipartFile file(byte[] bytes) { return new MockMultipartFile("file", "resume.pdf", "application/pdf", bytes); }
    @Test void extractsAccentsAndPagesFromDigitalPdf() throws Exception {
        assertThat(service.extract(file(pdf("Ana Silva - Desenvolvedora Java - Formação e experiência", 2, false))))
                .contains("Formação", "experiência", "\n\n");
    }
    @Test void rejectsOversizedBeforeReadingFile() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(5242881L);
        assertStatus(() -> service.extract(file), 413);
        verify(file, never()).getInputStream();
    }
    @Test void acceptsExactSizeLimitAndChecksBytesEvenIfSizeIsFalse() throws Exception {
        byte[] bytes = pdf("Ana Silva desenvolvedora Java com muita experiência", 1, false);
        var exact = new PdfTextExtractionService(AiTestSupport.properties(true, bytes.length, 20, 60000));
        assertThat(exact.extract(file(bytes))).contains("Ana Silva");
        MultipartFile lying = mock(MultipartFile.class);
        when(lying.getSize()).thenReturn(1L);
        when(lying.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[5242881]));
        assertStatus(() -> service.extract(lying), 413);
    }
    @Test void rejectsEmptyFakeCorruptScannedProtectedAndTooManyPages() throws Exception {
        assertStatus(() -> service.extract(file(new byte[0])), 422);
        assertStatus(() -> service.extract(file("hello".getBytes())), 415);
        assertStatus(() -> service.extract(file("%PDF-1.7 bad".getBytes())), 422);
        assertStatus(() -> service.extract(file(pdf(null, 1, false))), 422);
        assertStatus(() -> service.extract(file(pdf("Ana Silva desenvolvedora de sistemas Java", 1, true))), 422);
        assertStatus(() -> service.extract(file(pdf(null, 21, false))), 422);
    }
    @Test void boundsOutputDuringExtractionAndPreservesStructureInText() throws Exception {
        var small = new PdfTextExtractionService(AiTestSupport.properties(true, 5242880, 20, 10));
        assertStatus(() -> small.extract(file(pdf("Ana Silva desenvolvedora de sistemas Java", 1, false))), 413);
        assertStatus(() -> small.normalizeText("a".repeat(11)), 413);
        assertThat(service.normalizeText("  Ana\t Silva\r\n\r\nJava\u0000  ")).isEqualTo("Ana Silva\n\nJava");
    }
    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(status));
    }
}
