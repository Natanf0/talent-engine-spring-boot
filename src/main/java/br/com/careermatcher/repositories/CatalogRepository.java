package br.com.careermatcher.repositories;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.validations.TextSanitizer;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Repository;
import java.util.Map;

/** Catalog uniqueness is established by the database setup scripts. */
@Repository
public class CatalogRepository {
    private final Neo4jClient client;

    public CatalogRepository(Neo4jClient client) { this.client = client; }

    public long resolve(Kind kind, String name) {
        if (kind != Kind.SKILL && kind != Kind.LANGUAGE) throw new IllegalArgumentException("Not a catalog");
        String key = TextSanitizer.normalizeName(name);
        return client.query("MERGE (n:" + kind.name() + " {normalizedName: $key}) ON CREATE SET n.name = $name RETURN id(n) AS id")
                .bindAll(Map.of("key", key, "name", name)).fetchAs(Long.class)
                .mappedBy((typeSystem, record) -> record.get("id").asLong()).one().orElseThrow();
    }
}
