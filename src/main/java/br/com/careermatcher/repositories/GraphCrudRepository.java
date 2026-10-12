package br.com.careermatcher.repositories;

import br.com.careermatcher.crud.GraphSchema;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import br.com.careermatcher.security.CurrentActor;
import br.com.careermatcher.enums.Role;
import org.springframework.transaction.annotation.Transactional;
import static br.com.careermatcher.crud.GraphSchema.*;

/** Parameterized graph persistence. Labels/relationship types come exclusively from GraphSchema. */
@Repository
@Transactional
public class GraphCrudRepository {
    private static final String ORDER_PROPERTY = "crudOrder";
    // Public labels are used only for searching/sorting; payloads retain their enum codes.
    private static final Map<String, Map<String, String>> VACANCY_LABELS = Map.of(
            "seniority", Map.of("ESTAGIARIO", "Estagiário", "TRAINEE", "Trainee", "JUNIOR", "Júnior",
                    "PLENO", "Pleno", "SENIOR", "Sênior", "GESTOR", "Gestor", "GERENTE", "Gerente",
                    "ARQUITETO", "Arquiteto", "ESPECIALISTA", "Especialista"),
            "modalidade", Map.of("REMOTO", "Remoto", "HIBRIDO", "Híbrido", "PRESENCIAL", "Presencial"));
    private static final String CANDIDATE_ACCOUNT_MATCH = """
            MATCH (u)-[account]->(c)
            WHERE id(c)=$id AND $userLabel IN labels(u) AND $candidateLabel IN labels(c)
              AND type(account)=$accountRelationship AND u[$roleProperty]=$candidateRole
            """;
    private static final Map<String, Object> ACCOUNT_PARAMETERS = Map.of(
            "userLabel", "USER", "candidateLabel", "CANDIDATE", "accountRelationship", "IS",
            "roleProperty", "role", "candidateRole", "CANDIDATO");
    private final Neo4jClient client;
    private final CatalogRepository catalogs;
    private final ObjectMapper mapper;
    private final CurrentActor actor;
    public GraphCrudRepository(Neo4jClient client, CatalogRepository catalogs, ObjectMapper mapper, CurrentActor actor) {
        this.client = client; this.catalogs = catalogs; this.mapper = mapper; this.actor = actor;
    }

    private record Scope(String match, Map<String, Object> parameters) {}

    private Scope scope(Kind kind, boolean staffOnly) {
        if (staffOnly) actor.requireStaff();
        if (kind == Kind.VACANCY && actor.user().role() == Role.CANDIDATO) return new Scope(
                "MATCH (n) WHERE $rootLabel IN labels(n) AND EXISTS { "
                        + "MATCH (candidate)-[interest]->(company)-[publication]->(n) "
                        + "WHERE id(candidate)=$candidateId AND $candidateLabel IN labels(candidate) "
                        + "AND $companyLabel IN labels(company) AND type(interest)=$interestRelationship "
                        + "AND type(publication)=$publicationRelationship }",
                Map.of("candidateId", actor.requireCandidate(), "candidateLabel", "CANDIDATE",
                        "companyLabel", "COMPANY", "rootLabel", "VACANCY",
                        "interestRelationship", CompanyInterestRepository.RELATIONSHIP, "publicationRelationship", "PUBLISHES"));
        if (kind == Kind.VACANCY) return new Scope(
                "MATCH (company)-[publication]->(n) WHERE id(company)=$companyId "
                        + "AND $companyLabel IN labels(company) AND $rootLabel IN labels(n) "
                        + "AND type(publication)=$publicationRelationship",
                Map.of("companyId", actor.requireCompany(), "companyLabel", "COMPANY",
                        "rootLabel", "VACANCY", "publicationRelationship", "PUBLISHES"));
        if (kind != Kind.CANDIDATE) throw new IllegalArgumentException("Only aggregate roots may be accessed directly");
        if (actor.user().role() == Role.CANDIDATO) return new Scope(
                "MATCH (n) WHERE $rootLabel IN labels(n) AND id(n)=$candidateId",
                Map.of("rootLabel", "CANDIDATE", "candidateId", actor.requireCandidate()));
        actor.requireStaff();
        return new Scope("MATCH (n) WHERE $rootLabel IN labels(n) AND EXISTS { "
                + "MATCH (n)-[interest]->(company) WHERE id(company)=$companyId "
                + "AND $companyLabel IN labels(company) AND type(interest)=$interestRelationship }",
                Map.of("rootLabel", "CANDIDATE", "companyId", actor.requireCompany(),
                        "companyLabel", "COMPANY", "interestRelationship", CompanyInterestRepository.RELATIONSHIP));
    }

    private void requireRoot(Kind kind, long id, boolean lock) {
        Scope scope = scope(kind, false);
        String query = scope.match() + " AND id(n)=$id "
                + (lock ? "SET n.__crudLock=true REMOVE n.__crudLock " : "") + "RETURN id(n) AS id";
        if (client.query(query).bindAll(scope.parameters()).bind(id).to("id").fetch().one().isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro não encontrado.");
    }

    public List<ObjectNode> findAll(Kind kind) {
        Scope scope = scope(kind, true);
        return client.query(scope.match() + " RETURN DISTINCT id(n) AS id ORDER BY id(n)")
                .bindAll(scope.parameters()).fetchAs(Long.class).mappedBy((types, record) -> record.get("id").asLong()).all().stream()
                .map(id -> find(kind, id)).toList();
    }

    /** Read only root properties; pagination never hydrates the related graph. */
    public List<ObjectNode> findPage(Kind kind, int page, int size) {
        return findPage(kind, page, size, null, null, "asc");
    }

    public List<ObjectNode> findPage(Kind kind, int page, int size, String search, String sort, String direction) {
        validatePage(page, size);
        validateSort(kind, sort, direction);
        Scope scope = pageScope(kind, search);
        List<String> fields = summaryFields(kind);
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        if (kind == Kind.VACANCY) parameters.put("companyNameProperty", "name");
        String order = "id(n) ASC";
        if (sort != null) {
            parameters.put("sort", sort);
            parameters.put("displayLabels", displayLabels(kind));
            order = "toLower(" + summaryText("$sort") + ") " + (direction.equals("desc") ? "DESC" : "ASC") + ", id(n) ASC";
        }
        return client.query(scope.match() + " WITH DISTINCT n ORDER BY " + order + " SKIP $skip LIMIT $limit "
                        + "RETURN id(n) AS id, [field IN $fields | n[field]] AS values"
                        // Match the parameterized scope above even before these schema tokens exist.
                        + (kind == Kind.VACANCY ? ", head([(publisher)-[published]->(n) "
                            + "WHERE $companyLabel IN labels(publisher) AND type(published)=$publicationRelationship "
                            + "| publisher[$companyNameProperty]]) AS companyName" : ""))
                .bindAll(parameters).bind(fields).to("fields").bind((long) page * size).to("skip").bind(size).to("limit").fetch().all().stream()
                .map(row -> {
                    ObjectNode item = mapper.createObjectNode().put("id", ((Number) row.get("id")).longValue());
                    List<?> values = (List<?>) row.get("values");
                    for (int index = 0; index < fields.size(); index++) {
                        if (values.get(index) != null) item.set(fields.get(index), mapper.valueToTree(values.get(index)));
                    }
                    if (kind == Kind.VACANCY && row.get("companyName") != null)
                        item.put("companyName", (String)row.get("companyName"));
                    return item;
                }).toList();
    }

    public long count(Kind kind) {
        return count(kind, null);
    }

    public long count(Kind kind, String search) {
        Scope scope = pageScope(kind, search);
        return client.query(scope.match() + " RETURN count(DISTINCT n) AS total")
                .bindAll(scope.parameters()).fetchAs(Long.class).mappedBy((types, record) -> record.get("total").asLong()).one().orElse(0L);
    }

    private static List<String> summaryFields(Kind kind) {
        return switch (kind) {
            case CANDIDATE -> List.of("name", "professionalTitle", "phone");
            case VACANCY -> List.of("role", "city", "seniority", "modalidade");
            default -> throw new IllegalArgumentException("Unsupported paginated root");
        };
    }

    /** The page and its total must use exactly the same scope and search predicate. */
    private Scope pageScope(Kind kind, String search) {
        Scope scope = scope(kind, kind != Kind.VACANCY);
        if (search == null || search.isBlank()) return scope;
        Map<String, Object> parameters = new HashMap<>(scope.parameters());
        parameters.put("search", search.strip().toLowerCase(Locale.ROOT));
        parameters.put("searchFields", summaryFields(kind));
        parameters.put("displayLabels", displayLabels(kind));
        return new Scope(scope.match()
                + " AND any(field IN $searchFields WHERE toLower(coalesce(toString(n[field]), '')) CONTAINS $search"
                + " OR toLower(" + summaryText("field") + ") CONTAINS $search)", parameters);
    }

    private static Map<String, Map<String, String>> displayLabels(Kind kind) {
        return kind == Kind.VACANCY ? VACANCY_LABELS : Map.of();
    }

    private static String summaryText(String field) {
        return "coalesce($displayLabels[" + field + "][toString(n[" + field + "])], toString(n[" + field + "]), '')";
    }

    public static void validateSort(Kind kind, String sort, String direction) {
        List<String> allowed = kind == Kind.CANDIDATE ? List.of("name", "professionalTitle") : summaryFields(kind);
        if (sort != null && !allowed.contains(sort))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ordenação inválida. Use " + String.join(", ", allowed) + ".");
        if (!"asc".equals(direction) && !"desc".equals(direction))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Direção inválida. Use asc ou desc.");
    }

    public static void validatePage(int page, int size) {
        var errors = new ArrayList<br.com.careermatcher.validations.ValidationException.FieldError>();
        if (page < 0) errors.add(new br.com.careermatcher.validations.ValidationException.FieldError("page", "A página deve ser maior ou igual a zero."));
        if (size < 1 || size > 100) errors.add(new br.com.careermatcher.validations.ValidationException.FieldError("size", "O tamanho deve estar entre 1 e 100."));
        if (!errors.isEmpty()) throw new br.com.careermatcher.validations.ValidationException(errors);
    }

    public ObjectNode find(Kind kind, long id) {
        requireRoot(kind, id, false);
        ObjectNode result = findGraph(kind, id);
        if (kind == Kind.CANDIDATE) addAccountEmail(result);
        return result;
    }

    private record NodeKey(Kind kind, long id) {}
    private record GraphLink(NodeKey target, Map<String, Object> attributes) {}
    private record LoadedNode(ObjectNode properties, Map<String, List<GraphLink>> edges) {}

    /** Bulk equivalent of find: authorize roots, then load only schema-approved descendants.
     * All caches are local to this call; no participant data is shared between requests.
     */
    @Transactional(readOnly = true)
    public List<ObjectNode> findByIds(Kind kind, List<Long> ids, int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batchSize deve ser maior que zero.");
        Scope scope = scope(kind, false);
        Map<NodeKey, LoadedNode> graph = new HashMap<>();
        Map<Kind, Set<Long>> pending = new EnumMap<>(Kind.class);
        List<Long> uniqueIds = new ArrayList<>(new LinkedHashSet<>(ids));
        for (List<Long> batch : batches(uniqueIds, batchSize)) {
            var roots = client.query(scope.match() + " AND id(n) IN $ids RETURN DISTINCT id(n) AS id, properties(n) AS properties")
                    .bindAll(scope.parameters()).bind(batch).to("ids").fetch().all();
            Set<Long> found = new HashSet<>();
            for (var row : roots) {
                long id = ((Number) row.get("id")).longValue();
                found.add(id);
                remember(graph, pending, new NodeKey(kind, id), castMap(row.get("properties")));
            }
            if (!found.containsAll(batch))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro não encontrado.");
        }

        // Breadth-first loading keeps database calls proportional to batches, not participants.
        while (!pending.isEmpty()) {
            Map<Kind, Set<Long>> next = new EnumMap<>(Kind.class);
            for (var entry : pending.entrySet()) {
                Kind sourceKind = entry.getKey();
                var edges = GraphSchema.node(sourceKind).edges().stream().map(edge -> Map.of(
                        "field", edge.field(), "relationship", edge.relationship(), "targetLabel", edge.target().name())).toList();
                for (List<Long> batch : batches(new ArrayList<>(entry.getValue()), batchSize)) {
                    var rows = client.query("""
                            UNWIND $ids AS sourceId
                            MATCH (n) WHERE id(n)=sourceId AND $sourceLabel IN labels(n)
                            MATCH (n)-[r]->(t)
                            UNWIND $edges AS edge
                            WITH n, r, t, edge
                            WHERE type(r)=edge.relationship AND edge.targetLabel IN labels(t)
                            RETURN id(n) AS source, edge.field AS field, edge.targetLabel AS targetLabel,
                                   id(t) AS target, properties(t) AS properties, properties(r) AS attributes
                            ORDER BY source, field, coalesce(r[$orderProperty], id(r)), id(r), id(t)
                            """)
                            .bindAll(Map.of("ids", batch, "sourceLabel", sourceKind.name(),
                                    "edges", edges, "orderProperty", ORDER_PROPERTY)).fetch().all();
                    for (var row : rows) {
                        NodeKey source = new NodeKey(sourceKind, ((Number) row.get("source")).longValue());
                        NodeKey target = new NodeKey(Kind.valueOf((String) row.get("targetLabel")),
                                ((Number) row.get("target")).longValue());
                        remember(graph, next, target, castMap(row.get("properties")));
                        graph.get(source).edges().computeIfAbsent((String) row.get("field"), ignored -> new ArrayList<>())
                                .add(new GraphLink(target, castMap(row.get("attributes"))));
                    }
                }
            }
            pending = next;
        }

        Map<Long, ObjectNode> roots = new HashMap<>();
        for (long id : uniqueIds) roots.put(id, assemble(new NodeKey(kind, id), graph));
        if (kind == Kind.CANDIDATE) {
            for (List<Long> batch : batches(uniqueIds, batchSize)) {
                var emails = client.query("""
                        UNWIND $ids AS candidateId
                        MATCH (c) WHERE id(c)=candidateId AND $candidateLabel IN labels(c)
                        MATCH (u)-[account]->(c)
                        WHERE $userLabel IN labels(u) AND type(account)=$accountRelationship
                          AND u[$roleProperty]=$candidateRole
                        RETURN id(c) AS id, u[$emailProperty] AS email
                        """).bindAll(ACCOUNT_PARAMETERS).bind(batch).to("ids")
                        .bind("email").to("emailProperty").fetch().all();
                for (var row : emails) {
                    if (row.get("email") != null)
                        roots.get(((Number) row.get("id")).longValue()).put("email", row.get("email").toString());
                }
            }
        }
        return ids.stream().map(roots::get).toList();
    }

    private void remember(Map<NodeKey, LoadedNode> graph, Map<Kind, Set<Long>> pending,
                          NodeKey key, Map<String, Object> properties) {
        if (graph.containsKey(key)) return;
        graph.put(key, new LoadedNode(readProperties(key.kind(), key.id(), properties), new HashMap<>()));
        if (!GraphSchema.node(key.kind()).edges().isEmpty())
            pending.computeIfAbsent(key.kind(), ignored -> new LinkedHashSet<>()).add(key.id());
    }

    /** Reconstruct exactly the CRUD representation, including edge attributes and list order. */
    private ObjectNode assemble(NodeKey key, Map<NodeKey, LoadedNode> graph) {
        LoadedNode node = graph.get(key);
        ObjectNode result = node.properties().deepCopy();
        for (Edge edge : GraphSchema.node(key.kind()).edges()) {
            ArrayNode values = mapper.createArrayNode();
            for (GraphLink link : node.edges().getOrDefault(edge.field(), List.of())) {
                ObjectNode target = assemble(link.target(), graph);
                if (edge.wrapper() == null) values.add(target);
                else {
                    ObjectNode wrapper = mapper.createObjectNode();
                    for (Field field : edge.attributes()) {
                        Object value = link.attributes().get(field.name());
                        if (value != null) wrapper.set(field.name(), mapper.valueToTree(value));
                    }
                    wrapper.set(edge.wrapper(), target);
                    values.add(wrapper);
                }
            }
            if (edge.many()) result.set(edge.field(), values);
            else if (!values.isEmpty()) result.set(edge.field(), values.get(0));
        }
        return result;
    }

    private static List<List<Long>> batches(List<Long> ids, int size) {
        var result = new ArrayList<List<Long>>();
        for (int offset = 0; offset < ids.size();) {
            int end = offset + Math.min(size, ids.size() - offset);
            result.add(ids.subList(offset, end));
            offset = end;
        }
        return result;
    }

    private void addAccountEmail(ObjectNode result) {
        client.query(CANDIDATE_ACCOUNT_MATCH + "RETURN u[$emailProperty] AS email")
                .bindAll(ACCOUNT_PARAMETERS).bind("email").to("emailProperty")
                .bind(result.path("id").longValue()).to("id").fetch().one()
                .map(row -> row.get("email")).ifPresent(email -> result.put("email", email.toString()));
    }

    private ObjectNode findGraph(Kind kind, long id) {
        Map<String, Object> properties = client.query("MATCH (n) WHERE id(n)=$id AND $label IN labels(n) RETURN properties(n) AS properties")
                .bind(kind.name()).to("label").bind(id).to("id").fetch().one().map(row -> castMap(row.get("properties")))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro não encontrado."));
        ObjectNode result = readProperties(kind, id, properties);
        for (Edge edge : GraphSchema.node(kind).edges()) {
            // Optional types and labels may not exist yet in a freshly seeded database.
            var relationships = client.query("""
                    MATCH (n)-[r]->(t)
                    WHERE id(n)=$id AND $sourceLabel IN labels(n)
                      AND type(r)=$relationship AND $targetLabel IN labels(t)
                    RETURN id(t) AS target, properties(r) AS properties
                    ORDER BY coalesce(r[$orderProperty], id(r)), id(r)
                    """)
                    .bindAll(Map.of("id", id, "sourceLabel", kind.name(),
                            "relationship", edge.relationship(), "targetLabel", edge.target().name(),
                            "orderProperty", ORDER_PROPERTY)).fetch().all();
            ArrayNode values = mapper.createArrayNode();
            for (var relationship : relationships) {
                ObjectNode target = findGraph(edge.target(), ((Number) relationship.get("target")).longValue());
                if (edge.wrapper() == null) values.add(target);
                else {
                    ObjectNode wrapper = mapper.createObjectNode();
                    Map<String, Object> attrs = castMap(relationship.get("properties"));
                    for (Field field : edge.attributes()) {
                        Object value = attrs.get(field.name());
                        if (value != null) wrapper.set(field.name(), mapper.valueToTree(value));
                    }
                    wrapper.set(edge.wrapper(), target);
                    values.add(wrapper);
                }
            }
            if (edge.many()) result.set(edge.field(), values);
            else if (!values.isEmpty()) result.set(edge.field(), values.get(0));
        }
        return result;
    }

    /** Locks the root before read/merge so concurrent PATCHes cannot silently lose unrelated edits. */
    public void lock(Kind kind, long id) {
        if (kind == Kind.VACANCY) actor.requireStaff();
        requireRoot(kind, id, true);
    }

    /** Candidate changes are only allowed through the owner's profile. */
    public void requireCandidateUpdate(long id) {
        if (actor.requireCandidate() != id)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro não encontrado.");
    }

    /** Public lookup is deliberately restricted to a published vacancy aggregate. */
    public ObjectNode findPublicVacancy(long id) {
        if (client.query("MATCH (:COMPANY)-[:PUBLISHES]->(v:VACANCY) WHERE id(v)=$id RETURN id(v) AS id LIMIT 1")
                .bind(id).to("id").fetch().one().isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vaga não encontrada.");
        return findGraph(Kind.VACANCY, id);
    }

    public long create(Kind kind, ObjectNode data) {
        if (kind == Kind.CANDIDATE)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Candidatos são criados no cadastro de conta.");
        scope(kind, true);
        long companyId = actor.requireCompany();
        long id = client.query("MATCH (company:COMPANY) WHERE id(company)=$companyId "
                + "CREATE (company)-[:PUBLISHES]->(n:VACANCY) SET n=$properties RETURN id(n) AS id")
                .bind(companyId).to("companyId").bind(properties(GraphSchema.node(kind).fields(), data)).to("properties")
                .fetchAs(Long.class).mappedBy((types, record) -> record.get("id").asLong()).one()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa não encontrada."));
        for (Edge edge : GraphSchema.node(kind).edges()) attach(id, edge, data.get(edge.field()));
        return id;
    }

    /** Trusted registration entry point, never exposed directly by a controller. */
    public long createCandidateForRegistration(ObjectNode data) { return createGraph(Kind.CANDIDATE, data); }

    public ObjectNode registeredCandidate(long id) { return findGraph(Kind.CANDIDATE, id); }

    private long createGraph(Kind kind, ObjectNode data) {
        if (GraphSchema.catalog(kind)) return catalogs.resolve(kind, data.required("name").asText());
        long id = client.query("CREATE (n:" + kind.name() + ") SET n=$properties RETURN id(n) AS id")
                .bind(properties(GraphSchema.node(kind).fields(), data)).to("properties")
                .fetchAs(Long.class).mappedBy((types, record) -> record.get("id").asLong()).one().orElseThrow();
        for (Edge edge : GraphSchema.node(kind).edges()) attach(id, edge, data.get(edge.field()));
        return id;
    }

    public void update(Kind kind, long id, ObjectNode data, Set<String> changedFields) {
        if (kind == Kind.CANDIDATE) requireCandidateUpdate(id);
        else actor.requireStaff();
        requireRoot(kind, id, true);
        client.query("MATCH (n:" + kind.name() + ") WHERE id(n)=$id SET n += $properties")
                .bindAll(Map.of("id", id, "properties", properties(GraphSchema.node(kind).fields(), data))).run();
        for (Edge edge : GraphSchema.node(kind).edges()) {
            if (!changedFields.contains(edge.field())) continue;
            List<Long> oldChildren = detach(id, edge);
            attach(id, edge, data.get(edge.field()));
            oldChildren.forEach(child -> cleanup(child, edge.target()));
        }
        if (kind == Kind.CANDIDATE && changedFields.contains("name"))
            client.query(CANDIDATE_ACCOUNT_MATCH + "SET u.name=$name").bindAll(ACCOUNT_PARAMETERS)
                    .bindAll(Map.of("id", id, "name", data.path("name").asText())).run();
    }

    public void delete(Kind kind, long id) {
        scope(kind, true);
        requireRoot(kind, id, true);
        if (kind == Kind.CANDIDATE) {
            client.query("MATCH (candidate)-[interest]->(company) WHERE id(candidate)=$id "
                            + "AND id(company)=$companyId AND type(interest)=$relationship DELETE interest")
                    .bindAll(Map.of("id", id, "companyId", actor.requireCompany(),
                            "relationship", CompanyInterestRepository.RELATIONSHIP)).run();
            return;
        }
        deleteGraph(kind, id);
    }

    private void deleteGraph(Kind kind, long id) {
        List<Map.Entry<Long, Kind>> children = new ArrayList<>();
        for (Edge edge : GraphSchema.node(kind).edges())
            for (Long child : detach(id, edge)) children.add(Map.entry(child, edge.target()));
        client.query("MATCH (n:" + kind.name() + ") WHERE id(n)=$id DETACH DELETE n").bind(id).to("id").run();
        children.forEach(entry -> cleanup(entry.getKey(), entry.getValue()));
    }

    private void attach(long parent, Edge edge, JsonNode value) {
        if (value == null || value.isNull()) return;
        List<JsonNode> elements = new ArrayList<>();
        if (edge.many()) value.forEach(elements::add); else elements.add(value);
        for (int position = 0; position < elements.size(); position++) {
            ObjectNode wrapper = (ObjectNode) elements.get(position);
            ObjectNode target = edge.wrapper() == null ? wrapper : (ObjectNode) wrapper.required(edge.wrapper());
            long child = createGraph(edge.target(), target);
            Map<String, Object> props = properties(edge.attributes(), wrapper);
            props.put(ORDER_PROPERTY, position);
            client.query("MATCH (a) WHERE id(a)=$parent MATCH (b) WHERE id(b)=$child CREATE (a)-[r:" + edge.relationship() + "]->(b) SET r=$properties")
                    .bindAll(Map.of("parent", parent, "child", child, "properties", props)).run();
        }
    }

    private List<Long> detach(long parent, Edge edge) {
        return List.copyOf(client.query("""
                MATCH (n)-[r]->(t)
                WHERE id(n)=$id AND type(r)=$relationship AND $targetLabel IN labels(t)
                WITH r, id(t) AS target DELETE r RETURN target
                """)
                .bindAll(Map.of("id", parent, "relationship", edge.relationship(), "targetLabel", edge.target().name()))
                .fetchAs(Long.class).mappedBy((types, record) -> record.get("target").asLong()).all());
    }

    private void cleanup(long id, Kind kind) {
        if (GraphSchema.catalog(kind)) return;
        // Lock the child and recheck all incoming edges, including relationships outside this CRUD schema.
        boolean exclusive = client.query("MATCH (n:" + kind.name() + ") WHERE id(n)=$id SET n.__crudLock=true REMOVE n.__crudLock "
                        + "WITH n WHERE NOT EXISTS { MATCH ()-->(n) } RETURN id(n) AS id")
                .bind(id).to("id").fetch().one().isPresent();
        if (!exclusive) return;
        deleteGraph(kind, id);
    }

    private Map<String, Object> properties(List<Field> fields, ObjectNode data) {
        Map<String, Object> props = new HashMap<>();
        for (Field field : fields) {
            JsonNode value = data.get(field.name());
            props.put(field.name(), value == null || value.isNull() ? null : mapper.convertValue(value, Object.class));
        }
        return props;
    }
    private ObjectNode readProperties(Kind kind, long id, Map<String, Object> properties) {
        ObjectNode result = mapper.createObjectNode().put("id", id);
        for (Field field : GraphSchema.node(kind).fields()) {
            Object value = properties.get(field.name());
            if (value != null) {
                if (field.type() == Type.DATE) result.put(field.name(), dateString(value));
                else result.set(field.name(), mapper.valueToTree(value));
            }
        }
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }
    private static String dateString(Object value) {
        if (value instanceof ZonedDateTime date) return date.toInstant().toString();
        if (value instanceof OffsetDateTime date) return date.toInstant().toString();
        if (value instanceof java.util.Date date) return date.toInstant().toString();
        if (value instanceof Number number) return Instant.ofEpochMilli(number.longValue()).toString();
        return value.toString();
    }
}
