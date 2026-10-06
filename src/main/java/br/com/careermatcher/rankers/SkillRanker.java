package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.PesosRanker;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.models.Experience;
import br.com.careermatcher.models.Undergraduate;
import br.com.careermatcher.models.Graduate;
import br.com.careermatcher.models.Project;
import br.com.careermatcher.relations.CandidateHasSkill;
import br.com.careermatcher.relations.ExperienceDevelopsSkill;
import br.com.careermatcher.relations.ProjectDevelopsSkill;
import br.com.careermatcher.relations.VacancySkill;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

@Component
public class SkillRanker extends ExplainedRanker {

    @Override
    public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        CandidateWeights aggregated = candidateWeights(candidate == null ? null : candidate.getResume());
        Map<String, Double> candidateWeights = aggregated.averages();
        Map<String, Requirement> requirements = requirements(vacancy);
        List<RankerDetail.Contribution> contributions = new ArrayList<>();
        for (Map.Entry<String, Requirement> entry : requirements.entrySet()) {
            double candidateWeight = candidateWeights.getOrDefault(entry.getKey(), 0.0);
            Requirement requirement = entry.getValue();
            double ratio = candidateWeight / requirement.minimumWeight();
            double points = 0;
            String code = "SKILL_BELOW_WITHOUT_PENALTY";
            double multiplier = requirement.required() ? PesosRanker.VAGA_SKILL_OBRIGATORIA.getPeso()
                    : PesosRanker.VAGA_SKILL_DESEJADA.getPeso();
            if (candidateWeight >= requirement.minimumWeight()) {
                double relevance = PesosRanker.VAGA_SKILL_ATENDIDA.getPeso() * ratio;
                points = relevance * multiplier;
                code = "SKILL_MET";
            } else if (!candidatePerspective && requirement.required()) {
                points = PesosRanker.VAGA_SKILL_NAO_ATENDIDA.getPeso() * PesosRanker.VAGA_SKILL_OBRIGATORIA.getPeso();
                code = "REQUIRED_SKILL_BELOW";
            }
            contributions.add(RankerDetail.contribution(code, points,
                    "Competência " + entry.getKey() + ": peso médio " + candidateWeight
                            + ", mínimo " + requirement.minimumWeight() + "; contribuição " + points + ".",
                    "skill", entry.getKey(), "candidateAverageWeight", candidateWeight,
                    "sources", aggregated.sources().getOrDefault(entry.getKey(), List.of()),
                    "minimumWeight", requirement.minimumWeight(), "required", requirement.required(),
                    "ratio", ratio, "multiplier", multiplier,
                    "formula", code.equals("SKILL_MET") ? "VAGA_SKILL_ATENDIDA * ratio * multiplier"
                            : code.equals("REQUIRED_SKILL_BELOW") ? "VAGA_SKILL_NAO_ATENDIDA * VAGA_SKILL_OBRIGATORIA" : "0"));
        }
        if (contributions.isEmpty()) contributions.add(RankerDetail.contribution("NO_VALID_SKILL_REQUIREMENTS", 0,
                "Sem requisitos válidos de competências; contribuição zero."));
        return RankerDetail.of("SkillRanker",
                "Média dos pesos por nome normalizado em currículo, experiências e projetos; requisitos duplicados usam maior mínimo e obrigatoriedade OR. Razão sem teto; penalidade fixa apenas pela vaga.", contributions);
    }

    private static CandidateWeights candidateWeights(Resume resume) {
        Map<String, Double> weights = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> sources = new LinkedHashMap<>();
        if (resume == null) return new CandidateWeights(weights, sources);
        for (CandidateHasSkill r : RankerSupport.safeList(resume.getCandidateSkills())) {
            if (r != null) add(weights, counts, sources, "RESUME", resume.getId(), r.getSkill(), r.getWeight());
        }
        for (Experience experience : RankerSupport.safeList(resume.getExperiences())) {
            if (experience == null) continue;
            for (ExperienceDevelopsSkill r : RankerSupport.safeList(experience.getSkillsRelatedToExperience())) {
                if (r != null) add(weights, counts, sources, "EXPERIENCE", experience.getId(), r.getSkill(), r.getWeight());
            }
        }
        Undergraduate undergraduate = resume.getUndergraduate();
        if (undergraduate != null) addProjects(weights, counts, sources, "UNDERGRADUATE_PROJECT", undergraduate.getProjects());
        for (Graduate graduate : RankerSupport.safeList(resume.getGraduates())) {
            if (graduate != null) addProjects(weights, counts, sources, "GRADUATE_PROJECT", graduate.getProjects());
        }
        weights.replaceAll((name, sum) -> sum / counts.get(name));
        return new CandidateWeights(weights, sources);
    }

    private static void addProjects(Map<String, Double> weights, Map<String, Integer> counts,
                                    Map<String, List<Map<String, Object>>> sources, String type, List<Project> projects) {
        for (Project project : RankerSupport.safeList(projects)) {
            if (project == null) continue;
            for (ProjectDevelopsSkill r : RankerSupport.safeList(project.getProjectDevelopsSkillList())) {
                if (r != null) add(weights, counts, sources, type, project.getId(), r.getSkill(), r.getWeight());
            }
        }
    }

    private static void add(Map<String, Double> weights, Map<String, Integer> counts,
                            Map<String, List<Map<String, Object>>> sources, String type, Long sourceId,
                            br.com.careermatcher.models.Skill skill, Double weight) {
        if (skill == null || RankerSupport.normalize(skill.getName()).isEmpty()) return;
        String name = RankerSupport.normalize(skill.getName());
        double effectiveWeight = RankerSupport.candidateWeight(weight);
        weights.merge(name, effectiveWeight, Double::sum);
        counts.merge(name, 1, Integer::sum);
        sources.computeIfAbsent(name, ignored -> new ArrayList<>()).add(RankerDetail.facts(
                "sourceType", type, "sourceNodeId", sourceId == null ? null : sourceId.toString(),
                "skillId", skill.getId() == null ? null : skill.getId().toString(), "rawName", skill.getName(),
                "rawWeight", weight, "effectiveWeight", effectiveWeight));
    }

    private record CandidateWeights(Map<String, Double> averages, Map<String, List<Map<String, Object>>> sources) {}

    private static Map<String, Requirement> requirements(Vacancy vacancy) {
        Map<String, Requirement> requirements = new LinkedHashMap<>();
        if (vacancy == null) {
            return requirements;
        }
        for (VacancySkill relationship : RankerSupport.safeList(vacancy.getCompetencias())) {
            if (relationship == null || relationship.getSkill() == null) {
                continue;
            }
            String name = RankerSupport.normalize(relationship.getSkill().getName());
            if (name.isEmpty()) {
                continue;
            }
            Requirement current = new Requirement(
                    RankerSupport.requirementWeight(relationship.getWeight()), relationship.isRequired()
            );
            requirements.merge(name, current, (left, right) -> new Requirement(
                    Math.max(left.minimumWeight(), right.minimumWeight()), left.required() || right.required()
            ));
        }
        return requirements;
    }

    private record Requirement(double minimumWeight, boolean required) {
    }
}
