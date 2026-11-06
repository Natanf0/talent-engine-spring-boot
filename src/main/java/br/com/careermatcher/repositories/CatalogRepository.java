package br.com.careermatcher.repositories;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.crud.LanguageCatalog;
import br.com.careermatcher.validations.TextSanitizer;
import br.com.careermatcher.validations.ValidationException;
import com.github.benmanes.caffeine.cache.*;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.*;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.*;

@Repository
public class CatalogRepository {
    private final Neo4jClient client;
    private final Cache<String, List<Option>> skills;
    private final Cache<String, List<Option>> languages;
    public CatalogRepository(Neo4jClient client) { this(client, Duration.ofMinutes(5), Duration.ofHours(24)); }
    @Autowired
    public CatalogRepository(Neo4jClient client, @Value("${catalog.cache.skills-ttl:5m}") String skillTtl,
                             @Value("${catalog.cache.languages-ttl:24h}") String languageTtl) {
        this(client, org.springframework.boot.convert.DurationStyle.detectAndParse(skillTtl),
                org.springframework.boot.convert.DurationStyle.detectAndParse(languageTtl));
    }
    public CatalogRepository(Neo4jClient client, Duration skillTtl, Duration languageTtl) {
        this.client = client;
        if (skillTtl.isNegative() || skillTtl.isZero() || languageTtl.isNegative() || languageTtl.isZero())
            throw new IllegalArgumentException("Catalog TTL must be positive");
        skills = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(skillTtl).build();
        languages = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(languageTtl).build();
    }
    public record Option(Integer id, String name, List<String> aliases) {
        public Option(String name) { this(null, name, List.of()); }
        public Option(Integer id, String name) { this(id, name, LanguageCatalog.ENTRIES.get(id-1).aliases()); }
    }
    public void clearCaches() { skills.invalidateAll(); languages.invalidateAll(); }
    public List<Option> search(Kind kind, String query, int limit) {
        return search(kind, query, limit, 0);
    }
    public List<Option> search(Kind kind, String query, int limit, int offset) {
        if (kind != Kind.SKILL && kind != Kind.LANGUAGE) throw new IllegalArgumentException("Not a catalog");
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Invalid catalog limit");
        if (offset < 0) throw new IllegalArgumentException("Invalid catalog offset");
        String key = TextSanitizer.normalizeName(query);
        if (kind == Kind.LANGUAGE) return languages.get("all", ignored -> client.query(
                "MATCH (n:LANGUAGE) WHERE n.catalogId IS NOT NULL RETURN n.catalogId AS id, n.catalogName AS name ORDER BY id")
                .fetchAs(Option.class).mappedBy((types, row) -> new Option(row.get("id").asInt(), row.get("name").asString())).all().stream().toList());
        return skills.get("all", ignored -> client.query("MATCH (n:SKILL) WHERE n.name IS NOT NULL RETURN n.name AS name")
                .fetchAs(String.class).mappedBy((types, row) -> row.get("name").asString()).all().stream()
                .sorted().collect(java.util.stream.Collectors.toMap(TextSanitizer::normalizeName, name -> name, (a,c) -> a, LinkedHashMap::new))
                .values().stream().map(Option::new).toList()).stream()
                .filter(option -> TextSanitizer.normalizeName(option.name()).contains(key))
                .sorted(Comparator.comparingInt((Option option) -> TextSanitizer.normalizeName(option.name()).equals(key) ? 0 : TextSanitizer.normalizeName(option.name()).startsWith(key) ? 1 : 2)
                        .thenComparing(option -> TextSanitizer.normalizeName(option.name())))
                .skip(offset).limit(limit).toList();
    }
    public void seedLanguages() {
        var existing = client.query("MATCH (n:LANGUAGE) RETURN id(n) AS id, n.name AS name ORDER BY id(n)").fetch().all();
        var rows = LanguageCatalog.ENTRIES.stream().map(entry -> {
            var row = new HashMap<String,Object>();
            row.put("rank", entry.id()); row.put("name", entry.name());
            row.put("key", TextSanitizer.normalizeName(entry.name()));
            row.put("existing", existing.stream().filter(n -> n.get("name") instanceof String name
                    && LanguageCatalog.find(name).map(e -> e.id()==entry.id()).orElse(false))
                    .map(n -> n.get("id")).findFirst().orElse(-1L));
            return row;
        }).toList();
        client.query("UNWIND $rows AS row CALL { WITH row MATCH (n:LANGUAGE) WHERE id(n)=row.existing RETURN n "
                + "UNION WITH row WITH row WHERE row.existing=-1 MERGE (n:LANGUAGE {normalizedName:row.key}) ON CREATE SET n.name=row.name RETURN n } "
                + "SET n.catalogId=row.rank, n.catalogName=row.name").bind(rows).to("rows").run();
        languages.invalidateAll();
    }
    private long resolveLanguage(LanguageCatalog.Entry entry) {
        // Reuse legacy English/Portuguese names without changing relationship endpoints.
        var existing = client.query("MATCH (n:LANGUAGE) RETURN id(n) AS id, n.name AS name, n.catalogId AS rank ORDER BY id(n)")
                .fetch().all().stream().filter(row -> Objects.equals(row.get("rank"), (long)entry.id())
                        || row.get("name") instanceof String name && LanguageCatalog.find(name).map(e -> e.id()==entry.id()).orElse(false)).findFirst();
        if (existing.isPresent()) {
            long id = ((Number)existing.get().get("id")).longValue();
            client.query("MATCH (n:LANGUAGE) WHERE id(n)=$id SET n.catalogId=$rank, n.catalogName=$name RETURN id(n)")
                    .bindAll(Map.of("id", id, "rank", entry.id(), "name", entry.name())).run();
            return id;
        }
        return client.query("MERGE (n:LANGUAGE {normalizedName:$key}) ON CREATE SET n.name=$name SET n.catalogId=$rank, n.catalogName=$name RETURN id(n) AS id")
                .bindAll(Map.of("key", TextSanitizer.normalizeName(entry.name()), "name", entry.name(), "rank", entry.id()))
                .fetchAs(Long.class).mappedBy((t,r)->r.get("id").asLong()).one().orElseThrow();
    }
    public long resolve(Kind kind, String name) {
        if (kind == Kind.LANGUAGE) return resolveLanguage(LanguageCatalog.find(name).orElseThrow(() ->
                new ValidationException(List.of(new ValidationException.FieldError("language.name", "Selecione um idioma do catálogo.")))));
        if (kind != Kind.SKILL) throw new IllegalArgumentException("Not a catalog");
        String key = TextSanitizer.normalizeName(name);
        var existing = client.query("MATCH (n:SKILL) RETURN id(n) AS id, n.name AS name ORDER BY id(n)").fetch().all().stream()
                .filter(row -> row.get("name") instanceof String value && TextSanitizer.normalizeName(value).equals(key)).findFirst();
        if (existing.isPresent()) return ((Number)existing.get().get("id")).longValue();
        long id = client.query("MERGE (n:SKILL {normalizedName: $key}) ON CREATE SET n.name = $name RETURN id(n) AS id")
                .bindAll(Map.of("key", key, "name", name)).fetchAs(Long.class)
                .mappedBy((types, row) -> row.get("id").asLong()).one().orElseThrow();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { skills.invalidateAll(); }
            });
        } else skills.invalidateAll();
        return id;
    }
}
