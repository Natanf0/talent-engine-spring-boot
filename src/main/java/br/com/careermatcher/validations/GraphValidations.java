package br.com.careermatcher.validations;

import br.com.careermatcher.crud.GraphSchema;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.*;
import java.time.format.*;
import java.util.*;
import static br.com.careermatcher.crud.GraphSchema.*;
import static br.com.careermatcher.validations.ValidationException.FieldError;

@Component
public class GraphValidations {
    private final TextSanitizer sanitizer;
    public GraphValidations(TextSanitizer sanitizer) { this.sanitizer = sanitizer; }

    public ObjectNode validate(Kind kind, ObjectNode input) {
        List<FieldError> errors = new ArrayList<>();
        if (input == null) throw new ValidationException(List.of(new FieldError("body", "Objeto JSON obrigatório.")));
        ObjectNode clean = validateNode(kind, kind, input, "", errors);
        if (!errors.isEmpty()) throw new ValidationException(errors);
        return clean;
    }

    /** Partial extraction is not a write payload: omissions must never delete data or invent defaults. */
    public record Draft(ObjectNode data, List<FieldError> pendingFields, List<FieldError> warnings) {}
    public Draft validateDraft(Kind kind, ObjectNode input) {
        List<FieldError> pending = new ArrayList<>();
        List<FieldError> warnings = new ArrayList<>();
        ObjectNode data = draftNode(kind, input, "", pending, warnings);
        return new Draft(data, List.copyOf(pending), List.copyOf(warnings));
    }

    private ObjectNode draftNode(Kind kind, ObjectNode input, String path, List<FieldError> pending, List<FieldError> warnings) {
        Node schema = GraphSchema.node(kind);
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        Set<String> allowed = new HashSet<>();
        for (Field field : schema.fields()) {
            allowed.add(field.name());
            draftField(field, input.get(field.name()), output, join(path, field.name()), pending, warnings);
        }
        for (Edge edge : schema.edges()) {
            allowed.add(edge.field());
            JsonNode raw = input.get(edge.field());
            if (raw == null || raw.isNull()) continue;
            String edgePath = join(path, edge.field());
            if (edge.many()) {
                if (!raw.isArray() || raw.size() > 100) throw new IllegalArgumentException("Lista incompatível.");
                ArrayNode items = JsonNodeFactory.instance.arrayNode();
                Set<String> names = new HashSet<>();
                for (JsonNode item : raw) {
                    String itemPath = edgePath + "[" + items.size() + "]";
                    // Compute pending paths only for entries retained in the draft.
                    List<FieldError> itemPending = new ArrayList<>();
                    ObjectNode clean = draftEdge(edge, item, itemPath, itemPending, warnings);
                    if (clean.isEmpty()) continue;
                    if (GraphSchema.catalog(edge.target())) {
                        String name = clean.path(edge.wrapper()).path("name").asText("");
                        if (name.isBlank()) continue;
                        if (!names.add(TextSanitizer.normalizeName(name))) {
                            error(warnings, edgePath, "Item repetido removido após normalização.");
                            continue;
                        }
                    }
                    items.add(clean);
                    pending.addAll(itemPending);
                }
                if (!items.isEmpty()) output.set(edge.field(), items);
            } else {
                List<FieldError> itemPending = new ArrayList<>();
                ObjectNode clean = draftEdge(edge, raw, edgePath, itemPending, warnings);
                if (!clean.isEmpty()) { output.set(edge.field(), clean); pending.addAll(itemPending); }
            }
        }
        unknownDraftFields(input, allowed, path, warnings);
        if (kind == Kind.PROJECT && output.hasNonNull("startDate") && output.hasNonNull("endDate")
                && Instant.parse(output.path("endDate").asText()).isBefore(Instant.parse(output.path("startDate").asText()))) {
            output.remove("endDate");
            error(warnings, join(path, "endDate"), "Término anterior ao início foi descartado.");
        }
        return output;
    }

    private ObjectNode draftEdge(Edge edge, JsonNode raw, String path, List<FieldError> pending, List<FieldError> warnings) {
        if (!raw.isObject()) throw new IllegalArgumentException("Objeto incompatível.");
        if (edge.wrapper() == null) return draftNode(edge.target(), (ObjectNode) raw, path, pending, warnings);
        JsonNode target = raw.get(edge.wrapper());
        if (target == null || !target.isObject()) throw new IllegalArgumentException("Wrapper incompatível.");
        ObjectNode clean = draftNode(edge.target(), (ObjectNode) target, join(path, edge.wrapper()), pending, warnings);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        if (clean.isEmpty()) return result;
        result.set(edge.wrapper(), clean);
        Set<String> allowed = new HashSet<>(Set.of(edge.wrapper()));
        for (Field attribute : edge.attributes()) {
            allowed.add(attribute.name());
            draftField(attribute, raw.get(attribute.name()), result, join(path, attribute.name()), pending, warnings);
        }
        unknownDraftFields((ObjectNode) raw, allowed, path, warnings);
        return result;
    }

    private void draftField(Field field, JsonNode raw, ObjectNode out, String path, List<FieldError> pending, List<FieldError> warnings) {
        Field optional = new Field(field.name(), field.type(), false, field.limit(), field.values());
        int before = warnings.size();
        validateField(optional, raw, out, path, warnings);
        // The current form supports these narrower ranges; do not return an unselectable suggestion.
        JsonNode value = out.get(field.name());
        if (value != null && ((field.name().equals("weight") && (value.doubleValue() < 1 || value.doubleValue() > 10
                        || value.doubleValue() != Math.rint(value.doubleValue())))
                || (field.name().equals("level") && !Set.of(1.0, 3.0, 6.0, 9.0).contains(value.doubleValue())))) {
            error(warnings, path, "Valor fora das opções do formulário foi descartado.");
        }
        if (warnings.size() > before) out.remove(field.name());
        else if (field.name().equals("weight") && value != null) out.put(field.name(), value.intValue());
        if (field.required() && !out.has(field.name())) error(pending, path, "Preencha este campo antes de salvar.");
    }

    private void unknownDraftFields(ObjectNode input, Set<String> allowed, String path, List<FieldError> warnings) {
        input.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) error(warnings, join(path, name), "Campo desconhecido ou não editável foi descartado.");
        });
    }

    /** RFC 7396-like object merge; arrays replace wholesale. Null is retained for required-field validation. */
    public ObjectNode merge(ObjectNode current, ObjectNode patch) {
        if (patch == null) throw new ValidationException(List.of(new FieldError("body", "Objeto JSON obrigatório.")));
        ObjectNode merged = current.deepCopy();
        patch.properties().forEach(entry -> {
            JsonNode previous = merged.get(entry.getKey());
            JsonNode value = entry.getValue();
            merged.set(entry.getKey(), value.isObject() && previous != null && previous.isObject()
                    ? merge((ObjectNode) previous, (ObjectNode) value) : value.deepCopy());
        });
        return merged;
    }

    public ObjectNode withoutIds(ObjectNode source) {
        ObjectNode result = source.deepCopy();
        removeIds(result);
        return result;
    }
    private void removeIds(JsonNode node) {
        if (node.isObject()) ((ObjectNode) node).remove("id");
        if (node.isContainerNode()) node.forEach(this::removeIds);
    }

    private ObjectNode validateNode(Kind kind, Kind aggregate, ObjectNode input, String path, List<FieldError> errors) {
        Node schema = GraphSchema.node(kind, aggregate);
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        Set<String> allowed = new HashSet<>();
        for (Field field : schema.fields()) {
            allowed.add(field.name());
            validateField(field, input.get(field.name()), output, join(path, field.name()), errors);
        }
        for (Edge edge : schema.edges()) {
            allowed.add(edge.field());
            JsonNode raw = input.get(edge.field());
            String edgePath = join(path, edge.field());
            if (raw == null || raw.isNull()) {
                if (edge.many()) output.putArray(edge.field());
                continue;
            }
            if (edge.many()) {
                if (!raw.isArray()) { error(errors, edgePath, "Deve ser uma lista."); continue; }
                ArrayNode items = output.putArray(edge.field());
                Set<String> names = new HashSet<>();
                for (int i = 0; i < raw.size(); i++) {
                    ObjectNode item = validateEdge(edge, aggregate, raw.get(i), edgePath + "[" + i + "]", errors);
                    items.add(item);
                    if (GraphSchema.catalog(edge.target())) {
                        String key = TextSanitizer.normalizeName(item.path(edge.wrapper()).path("name").asText());
                        if (!names.add(key)) error(errors, edgePath + "[" + i + "]", "Nome repetido após normalização.");
                    }
                }
            } else output.set(edge.field(), validateEdge(edge, aggregate, raw, edgePath, errors));
        }
        input.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) error(errors, join(path, name), "Campo desconhecido ou não editável.");
        });
        if (kind == Kind.PROJECT && output.hasNonNull("startDate") && output.hasNonNull("endDate")) {
            if (Instant.parse(output.path("endDate").asText()).isBefore(Instant.parse(output.path("startDate").asText())))
                error(errors, join(path, "endDate"), "Término não pode anteceder o início.");
        }
        return output;
    }

    private ObjectNode validateEdge(Edge edge, Kind aggregate, JsonNode raw, String path, List<FieldError> errors) {
        if (!raw.isObject()) { error(errors, path, "Objeto obrigatório."); return JsonNodeFactory.instance.objectNode(); }
        if (edge.wrapper() == null) return validateNode(edge.target(), aggregate, (ObjectNode) raw, path, errors);
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Set<String> allowed = new HashSet<>(Set.of(edge.wrapper()));
        for (Field field : edge.attributes()) {
            allowed.add(field.name());
            validateField(field, raw.get(field.name()), result, join(path, field.name()), errors);
            if (field.type() == Type.BOOLEAN && !result.has(field.name())) result.put(field.name(), false);
        }
        JsonNode target = raw.get(edge.wrapper());
        if (target == null || !target.isObject()) error(errors, join(path, edge.wrapper()), "Objeto obrigatório.");
        else result.set(edge.wrapper(), validateNode(edge.target(), aggregate, (ObjectNode) target, join(path, edge.wrapper()), errors));
        raw.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) error(errors, join(path, name), "Campo desconhecido ou não editável.");
        });
        return result;
    }

    private void validateField(Field field, JsonNode raw, ObjectNode out, String path, List<FieldError> errors) {
        if (raw == null || raw.isNull()) {
            if (field.required()) error(errors, path, "Campo obrigatório.");
            return;
        }
        switch (field.type()) {
            case MONTHS -> {
                if (!raw.isIntegralNumber() || !raw.canConvertToInt() || raw.intValue() <= 0)
                    error(errors, path, "Informe um número JSON inteiro maior que zero.");
                else out.put(field.name(), raw.intValue());
            }
            case NUMBER, POSITIVE_NUMBER -> {
                if (!raw.isNumber() || !Double.isFinite(raw.doubleValue()) || raw.doubleValue() < 0
                        || field.type() == Type.POSITIVE_NUMBER && raw.doubleValue() == 0)
                    error(errors, path, "Informe um número finito " + (field.type() == Type.NUMBER ? "não negativo." : "maior que zero."));
                else out.put(field.name(), raw.doubleValue());
            }
            case BOOLEAN -> {
                if (!raw.isBoolean()) error(errors, path, "Informe true ou false.");
                else out.put(field.name(), raw.booleanValue());
            }
            default -> validateString(field, raw, out, path, errors);
        }
    }

    private void validateString(Field field, JsonNode raw, ObjectNode out, String path, List<FieldError> errors) {
        if (!raw.isTextual()) { error(errors, path, "Texto obrigatório; conversão automática de tipos não é permitida."); return; }
        String value = raw.textValue();
        if (field.limit() > 0 && value.codePointCount(0, value.length()) > field.limit()) {
            error(errors, path, "Máximo de " + field.limit() + " caracteres."); return;
        }
        try {
            if (field.type() == Type.DATE) {
                out.put(field.name(), OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME
                        .withResolverStyle(ResolverStyle.STRICT)).toInstant().toString());
                return;
            }
            if (field.type() == Type.ENUM) {
                if (!field.values().contains(value)) error(errors, path, "Valor inválido. Permitidos: " + field.values());
                else out.put(field.name(), value);
                return;
            }
            if (field.type() != Type.PASSWORD) value = field.type() == Type.HTML ? sanitizer.html(value) : sanitizer.plain(value);
            if (field.limit() > 0 && value.codePointCount(0, value.length()) > field.limit()) {
                error(errors, path, "Máximo de " + field.limit() + " caracteres após sanitização."); return;
            }
            String plain = field.type() == Type.HTML ? sanitizer.plain(value) : value;
            if (plain.isBlank()) {
                if (field.required() || field.type() == Type.PASSWORD) error(errors, path, "Não pode estar vazio após sanitização.");
                return;
            }
            if (field.type() != Type.PASSWORD && value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
                error(errors, path, "Caracteres de controle não permitidos."); return;
            }
            if (field.type() == Type.EMAIL && !value.matches("[^\\s@]+@[^\\s@.]+(?:\\.[^\\s@.]+)+")) {
                error(errors, path, "E-mail inválido."); return;
            }
            if (field.type() == Type.PHONE && (!value.matches("[+()0-9 .-]+") || !value.matches(".*[0-9].*"))) {
                error(errors, path, "Telefone inválido."); return;
            }
            if (field.type() == Type.URL) {
                URI uri = URI.create(value);
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) {
                    error(errors, path, "Use uma URL absoluta HTTP ou HTTPS, sem credenciais."); return;
                }
            }
            out.put(field.name(), value);
        } catch (DateTimeException exception) {
            error(errors, path, "Data ISO-8601 válida com fuso obrigatório, por exemplo 2026-09-28T12:00:00Z.");
        } catch (IllegalArgumentException exception) {
            error(errors, path, "Valor inválido.");
        }
    }
    private static String join(String path, String field) { return path.isEmpty() ? field : path + "." + field; }
    private static void error(List<FieldError> errors, String path, String message) { errors.add(new FieldError(path, message)); }
}
