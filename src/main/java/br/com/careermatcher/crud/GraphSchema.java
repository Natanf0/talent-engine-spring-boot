package br.com.careermatcher.crud;

import br.com.careermatcher.enums.*;
import java.util.*;

/** Closed schema: all graph labels, relationship types and writable fields are application constants. */
public final class GraphSchema {
    private GraphSchema() {}
    public enum Kind { CANDIDATE, VACANCY, RESUME, EXPERIENCE, UNDERGRADUATE, GRADUATE, PROJECT, SKILL, LANGUAGE }
    public enum Type { TEXT, HTML, EMAIL, PHONE, URL, PASSWORD, DATE, MONTHS, NUMBER, POSITIVE_NUMBER, BOOLEAN, ENUM }
    public record Field(String name, Type type, boolean required, int limit, Set<String> values) {}
    public record Edge(String field, String relationship, Kind target, boolean many, String wrapper, List<Field> attributes) {}
    public record Node(Kind kind, List<Field> fields, List<Edge> edges) {}
    private static Field field(String name, Type type, boolean required, int limit) {
        return new Field(name, type, required, limit, Set.of());
    }
    private static Field text(String name, boolean required) { return field(name, Type.TEXT, required, 150); }
    private static Field enumeration(String name, boolean required, Class<? extends Enum<?>> type) {
        Set<String> values = new LinkedHashSet<>();
        for (Enum<?> value : type.getEnumConstants()) values.add(value.name());
        return new Field(name, Type.ENUM, required, 0, Set.copyOf(values));
    }
    private static Edge edge(String field, String relationship, Kind target, boolean many) {
        return new Edge(field, relationship, target, many, null, List.of());
    }
    private static Edge weighted(String field, String relationship, boolean requirement) {
        return new Edge(field, relationship, Kind.SKILL, true, "skill", requirement
                ? List.of(field("weight", Type.POSITIVE_NUMBER, true, 0), field("required", Type.BOOLEAN, false, 0))
                : List.of(field("weight", Type.NUMBER, true, 0)));
    }
    private static Edge language(boolean requirement) {
        return new Edge(requirement ? "languageRequirements" : "languages", requirement ? "NEEDS_LANGUAGE" : "SPEAKS_LANGUAGE",
                Kind.LANGUAGE, true, "language", requirement
                ? List.of(field("level", Type.NUMBER, true, 0), field("required", Type.BOOLEAN, false, 0))
                : List.of(field("level", Type.NUMBER, true, 0)));
    }
    private static Edge requirement(String field, String relation, Kind target, String wrapper, boolean many) {
        return new Edge(field, relation, target, many, wrapper, List.of(field("required", Type.BOOLEAN, false, 0)));
    }
    public static boolean catalog(Kind kind) { return kind == Kind.SKILL || kind == Kind.LANGUAGE; }
    public static Node node(Kind kind) {
        return switch (kind) {
            case CANDIDATE -> new Node(kind, List.of(text("name", true), field("about", Type.HTML, false, 5000),
                    text("professionalTitle", true), field("phone", Type.PHONE, false, 30),
                    field("linkedinURL", Type.URL, false, 2048),
                    field("personalWebsiteURL", Type.URL, false, 2048)), List.of(edge("resume", "HAS", Kind.RESUME, false)));
            case VACANCY -> new Node(kind, List.of(text("role", true), field("city", Type.TEXT, true, 100),
                    enumeration("seniority", true, SENIORITY.class), enumeration("modalidade", true, Modalidade.class),
                    field("additionalInfo", Type.HTML, false, 1500)),
                    List.of(weighted("skillRequirements", "NEEDS_SKILL", true), language(true),
                            requirement("experienceRequirement", "NEEDS_EXPERIENCE", Kind.EXPERIENCE, "experience", false),
                            requirement("undergraduateRequirements", "NEEDS_UNDERGRAD", Kind.UNDERGRADUATE, "undergraduate", true),
                            requirement("graduateRequirements", "NEEDS_GRADUATE", Kind.GRADUATE, "graduate", true)));
            case RESUME -> new Node(kind, List.of(), List.of(weighted("skills", "CandidateHasSkill", false),
                    edge("experiences", "HasExperience", Kind.EXPERIENCE, true), language(false),
                    edge("undergraduate", "GRADUATED_IN", Kind.UNDERGRADUATE, false),
                    edge("graduates", "POSTGRADUATED_IN", Kind.GRADUATE, true)));
            case EXPERIENCE -> new Node(kind, List.of(text("role", true), text("companyName", false),
                    field("description", Type.HTML, false, 5000), field("durationMonths", Type.MONTHS, true, 0),
                    enumeration("seniority", true, SENIORITY.class)), List.of(weighted("skills", "DEVELOPS_SKILL", false)));
            case UNDERGRADUATE -> new Node(kind, List.of(text("field", true), text("institution", false),
                    field("completionDate", Type.DATE, false, 0), enumeration("undergraduateType", false, UNDERGRADUATETYPE.class)),
                    List.of(edge("projects", "DEVELOPS_A", Kind.PROJECT, true)));
            case GRADUATE -> new Node(kind, List.of(enumeration("degree", true, DEGREE.class), text("field", true),
                    text("subarea", false), text("institution", false), field("completionDate", Type.DATE, false, 0)),
                    List.of(edge("projects", "DEVELOPS_A", Kind.PROJECT, true)));
            case PROJECT -> new Node(kind, List.of(text("title", true), field("description", Type.HTML, false, 5000),
                    field("startDate", Type.DATE, true, 0), field("endDate", Type.DATE, false, 0)),
                    List.of(weighted("skills", "DEVELOPS_SKILL", false)));
            case SKILL, LANGUAGE -> new Node(kind, List.of(field("name", Type.TEXT, true, 100)), List.of());
        };
    }
}
