package br.com.careermatcher.services;

import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Experience;
import br.com.careermatcher.models.Graduate;
import br.com.careermatcher.models.Language;
import br.com.careermatcher.models.Project;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Skill;
import br.com.careermatcher.models.Undergraduate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.CandidateHasSkill;
import br.com.careermatcher.relations.CandidateLanguage;
import br.com.careermatcher.relations.ExperienceDevelopsSkill;
import br.com.careermatcher.relations.ProjectDevelopsSkill;
import br.com.careermatcher.relations.VacancyExperience;
import br.com.careermatcher.relations.VacancyGraduate;
import br.com.careermatcher.relations.VacancyLanguage;
import br.com.careermatcher.relations.VacancySkill;
import br.com.careermatcher.relations.VacancyUndergrad;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Function;

/** Formats the data already loaded for a matching, without reading credentials. */
final class MatchingTableFormatter {

    private static final String MISSING = "Não informado";
    private static final String EMPTY = "Nenhum";
    private static final String NOT_APPLICABLE = "—";
    private static final int FIELD_WIDTH = 24;
    private static final int DATA_WIDTH = 70;

    private MatchingTableFormatter() {
    }

    static String format(Candidate candidate, Vacancy vacancy) {
        Resume resume = candidate == null ? null : candidate.getResume();
        Table table = new Table();
        table.row("Campo", "DE: Candidato", "PARA: Vaga");
        table.row("ID", value(candidate, Candidate::getId), value(vacancy, Vacancy::getId));
        table.row("Nome", value(candidate, Candidate::getName), NOT_APPLICABLE);
        table.row("Descrição", value(candidate, Candidate::getAbout), NOT_APPLICABLE);
        table.row("Cargo", value(candidate, Candidate::getProfessionalTitle), value(vacancy, Vacancy::getRole));
        table.row("Telefone", value(candidate, Candidate::getPhone), NOT_APPLICABLE);
        table.row("LinkedIn", value(candidate, Candidate::getLinkedinURL), NOT_APPLICABLE);
        table.row("Site", value(candidate, Candidate::getPersonalWebsiteURL), NOT_APPLICABLE);
        table.row("Cidade", NOT_APPLICABLE, value(vacancy, Vacancy::getCity));
        table.row("Senioridade", NOT_APPLICABLE, value(vacancy, Vacancy::getSeniority));
        table.row("Modalidade", NOT_APPLICABLE, value(vacancy, Vacancy::getModalidade));
        table.row("ID do currículo", value(resume, Resume::getId), NOT_APPLICABLE);
        table.row("Competências",
                resume == null ? MISSING : items(resume.getCandidateSkills(), MatchingTableFormatter::candidateSkill),
                vacancy == null ? MISSING : items(vacancy.getCompetencias(), MatchingTableFormatter::vacancySkill));
        table.row("Idiomas",
                resume == null ? MISSING : items(resume.getLanguages(), MatchingTableFormatter::candidateLanguage),
                vacancy == null ? MISSING : items(vacancy.getLanguages(), MatchingTableFormatter::vacancyLanguage));
        table.row("Experiências",
                resume == null ? MISSING : items(resume.getExperiences(), MatchingTableFormatter::experience),
                vacancy == null ? MISSING : vacancyExperience(vacancy.getExperience()));
        table.row("Graduação",
                resume == null ? MISSING : undergraduate(resume.getUndergraduate()),
                vacancy == null ? MISSING : items(vacancy.getUndergraduates(), MatchingTableFormatter::vacancyUndergraduate));
        table.row("Pós-graduações",
                resume == null ? MISSING : items(resume.getGraduates(), MatchingTableFormatter::graduate),
                vacancy == null ? MISSING : items(vacancy.getGraduate(), MatchingTableFormatter::vacancyGraduate));
        return table.toString();
    }

    private static String skill(Skill skill) {
        return describe(skill, value -> new Details()
                .field("ID", value.getId())
                .field("Nome", value.getName()));
    }

    private static String language(Language language) {
        return describe(language, value -> new Details()
                .field("ID", value.getId())
                .field("Nome", value.getName()));
    }

    private static Details weightedSkill(Long relationshipId, Double weight, Skill skill) {
        return new Details()
                .field("ID da relação", relationshipId)
                .field("Peso", weight)
                .nested("Competência", skill(skill));
    }

    private static String candidateSkill(CandidateHasSkill relationship) {
        return describe(relationship, value -> weightedSkill(value.getId(), value.getWeight(), value.getSkill()));
    }

    private static String experienceSkill(ExperienceDevelopsSkill relationship) {
        return describe(relationship, value -> weightedSkill(value.getId(), value.getWeight(), value.getSkill()));
    }

    private static String projectSkill(ProjectDevelopsSkill relationship) {
        return describe(relationship, value -> weightedSkill(value.getId(), value.getWeight(), value.getSkill()));
    }

    private static String candidateLanguage(CandidateLanguage relationship) {
        return describe(relationship, value -> new Details()
                .field("ID da relação", value.getId())
                .field("Nível", value.getLevel())
                .nested("Idioma", language(value.getLanguage())));
    }

    private static String experience(Experience experience) {
        return describe(experience, value -> new Details()
                .field("ID", value.getId())
                .field("Cargo", value.getRole())
                .field("Empresa", value.getCompanyName())
                .field("Descrição", value.getDescription())
                .field("Duração (meses)", value.getDurationMonths())
                .field("Senioridade", value.getSeniority())
                .nested("Competências", items(value.getSkillsRelatedToExperience(), MatchingTableFormatter::experienceSkill)));
    }

    private static String undergraduate(Undergraduate undergraduate) {
        return describe(undergraduate, value -> new Details()
                .field("ID", value.getId())
                .field("Área", value.getField())
                .field("Instituição", value.getInstitution())
                .field("Conclusão", value.getCompletionDate())
                .field("Tipo", value.getUndergraduateType())
                .nested("Projetos", items(value.getProjects(), MatchingTableFormatter::project)));
    }

    private static String graduate(Graduate graduate) {
        return describe(graduate, value -> new Details()
                .field("ID", value.getId())
                .field("Grau", value.getDegree())
                .field("Área", value.getField())
                .field("Subárea", value.getSubarea())
                .field("Instituição", value.getInstitution())
                .field("Conclusão", value.getCompletionDate())
                .nested("Projetos", items(value.getProjects(), MatchingTableFormatter::project)));
    }

    private static String project(Project project) {
        return describe(project, value -> new Details()
                .field("ID", value.getId())
                .field("Título", value.getTitle())
                .field("Descrição", value.getDescription())
                .field("Início", value.getStartDate())
                .field("Fim", value.getEndDate())
                .nested("Competências", items(value.getProjectDevelopsSkillList(), MatchingTableFormatter::projectSkill)));
    }

    private static Details requirement(Long relationshipId, Boolean required) {
        return new Details()
                .field("ID da relação", relationshipId)
                .field("Obrigatório", required);
    }

    private static String vacancySkill(VacancySkill relationship) {
        return describe(relationship, value -> requirement(value.getId(), value.getRequired())
                .field("Peso mínimo", value.getWeight())
                .nested("Competência", skill(value.getSkill())));
    }

    private static String vacancyLanguage(VacancyLanguage relationship) {
        return describe(relationship, value -> requirement(value.getId(), value.getRequired())
                .nested("Idioma", language(value.getLanguage())));
    }

    private static String vacancyExperience(VacancyExperience relationship) {
        return describe(relationship, value -> requirement(value.getId(), value.getRequired())
                .nested("Experiência", experience(value.getExperience())));
    }

    private static String vacancyUndergraduate(VacancyUndergrad relationship) {
        return describe(relationship, value -> requirement(value.getId(), value.getRequired())
                .nested("Graduação", undergraduate(value.getUndergraduate())));
    }

    private static String vacancyGraduate(VacancyGraduate relationship) {
        return describe(relationship, value -> requirement(value.getId(), value.getRequired())
                .nested("Pós-graduação", graduate(value.getGraduate())));
    }

    private static <T> String value(T object, Function<T, ?> getter) {
        return object == null ? MISSING : display(getter.apply(object));
    }

    private static String display(Object value) {
        if (value == null) {
            return MISSING;
        }
        if (value instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name();
        }
        return value.toString();
    }

    private static <T> String describe(T object, Function<T, Details> formatter) {
        return object == null ? MISSING : formatter.apply(object).toString();
    }

    private static <T> String items(List<T> values, Function<T, String> formatter) {
        if (values == null) {
            return MISSING;
        }
        if (values.isEmpty()) {
            return EMPTY;
        }
        StringJoiner items = new StringJoiner("\n");
        for (int index = 0; index < values.size(); index++) {
            T value = values.get(index);
            items.add((index + 1) + ".\n" + indent(value == null ? MISSING : formatter.apply(value)));
        }
        return items.toString();
    }

    private static String indent(String text) {
        return "  " + String.join("\n  ", text.split("\\R", -1));
    }

    private static final class Details {
        private final StringJoiner lines = new StringJoiner("\n");

        Details field(String label, Object value) {
            lines.add(label + ": " + display(value));
            return this;
        }

        Details nested(String label, String content) {
            lines.add(label + ":\n" + indent(content));
            return this;
        }

        @Override
        public String toString() {
            return lines.toString();
        }
    }

    private static final class Table {
        private static final String BORDER = "+" + "-".repeat(FIELD_WIDTH + 2)
                + "+" + "-".repeat(DATA_WIDTH + 2)
                + "+" + "-".repeat(DATA_WIDTH + 2) + "+\n";
        private final StringBuilder output = new StringBuilder(BORDER);

        void row(String field, String candidate, String vacancy) {
            List<String> labels = wrap(field, FIELD_WIDTH);
            List<String> left = wrap(candidate, DATA_WIDTH);
            List<String> right = wrap(vacancy, DATA_WIDTH);
            int height = Math.max(labels.size(), Math.max(left.size(), right.size()));
            for (int index = 0; index < height; index++) {
                output.append("| ").append(cell(labels, index, FIELD_WIDTH))
                        .append(" | ").append(cell(left, index, DATA_WIDTH))
                        .append(" | ").append(cell(right, index, DATA_WIDTH)).append(" |\n");
            }
            output.append(BORDER);
        }

        private static String cell(List<String> lines, int index, int width) {
            String value = index < lines.size() ? lines.get(index) : "";
            return value + " ".repeat(width - value.codePointCount(0, value.length()));
        }

        private static List<String> wrap(String text, int width) {
            List<String> lines = new ArrayList<>();
            for (String line : text.replace("\t", "    ").split("\\R", -1)) {
                if (line.isEmpty()) {
                    lines.add("");
                    continue;
                }
                for (int start = 0; start < line.length();) {
                    int length = Math.min(width, line.codePointCount(start, line.length()));
                    int end = line.offsetByCodePoints(start, length);
                    lines.add(line.substring(start, end));
                    start = end;
                }
            }
            return lines;
        }

        @Override
        public String toString() {
            return output.toString();
        }
    }
}
