package br.com.careermatcher.crud;

import br.com.careermatcher.validations.TextSanitizer;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Total L1+L2 speakers, frozen 2025 ranking. See catalog-languages-source.txt. */
public final class LanguageCatalog {
    public record Entry(int id, String name, List<String> aliases) {}
    public static final List<Entry> ENTRIES = load();
    private static List<Entry> load() {
        try (var input = LanguageCatalog.class.getResourceAsStream("/catalog-languages.txt")) {
            var result = new ArrayList<Entry>();
            for (String line : new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                var names = List.of(line.split("\\|"));
                result.add(new Entry(result.size()+1, names.getFirst(), names.stream().map(LanguageCatalog::key).distinct().toList()));
            }
            if (result.size() != 50) throw new IllegalStateException("Expected 50 languages");
            return List.copyOf(result);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public static String key(String name) {
        return java.text.Normalizer.normalize(TextSanitizer.normalizeName(name), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
    }
    public static Optional<Entry> find(String name) {
        String key = key(name);
        return ENTRIES.stream().filter(e -> e.aliases().contains(key)).findFirst();
    }
    private LanguageCatalog() {}
}
