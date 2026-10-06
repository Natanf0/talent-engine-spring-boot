package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.PesosRanker;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.CandidateLanguage;
import br.com.careermatcher.relations.VacancyLanguage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

@Component
public class LanguageRanker extends ExplainedRanker {

    @Override
    public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        Set<String> names = candidateLanguages(candidate == null ? null : candidate.getResume());
        List<RankerDetail.Contribution> contributions = new ArrayList<>();
        if (candidatePerspective) {
            for (Map.Entry<String, Boolean> requirement : requirements(vacancy).entrySet()) {
                boolean found = names.contains(requirement.getKey());
                double points = found ? PesosRanker.IDIOMA_CUMPRIDO.getPeso()
                        : requirement.getValue() ? PesosRanker.IDIOMA_NAO_CUMPRIDO.getPeso() : 0.0;
                contributions.add(languageContribution(requirement.getKey(), found, requirement.getValue(), points, 1));
            }
        } else {
            for (VacancyLanguage r : RankerSupport.safeList(vacancy == null ? null : vacancy.getLanguages())) {
                if (r == null || r.getLanguage() == null) continue;
                String name = RankerSupport.normalize(r.getLanguage().getName());
                boolean found = names.contains(name);
                double multiplier = r.isRequired() ? PesosRanker.VAGA_LINGUA_OBRIGATORIA.getPeso()
                        : PesosRanker.VAGA_LINGUA_DESEJADA.getPeso();
                double points = found ? PesosRanker.VAGA_LINGUA_ATENDIDA.getPeso() * multiplier
                        : r.isRequired() ? PesosRanker.VAGA_LINGUA_NAO_ATENDIDA.getPeso()
                                * PesosRanker.VAGA_LINGUA_OBRIGATORIA.getPeso() : 0.0;
                contributions.add(languageContribution(name, found, r.isRequired(), points, multiplier));
            }
        }
        if (contributions.isEmpty()) contributions.add(RankerDetail.contribution("NO_VALID_LANGUAGE_REQUIREMENTS", 0,
                "Sem requisitos válidos de idiomas; contribuição zero."));
        return RankerDetail.of("LanguageRanker", "Comparação apenas por nome normalizado; níveis não são avaliados. "
                + (candidatePerspective ? "Requisitos duplicados agrupados por nome." : "Cada relação da vaga é pontuada individualmente."), contributions);
    }

    private static RankerDetail.Contribution languageContribution(String name, boolean found, boolean required,
                                                                  double points, double multiplier) {
        return RankerDetail.contribution(found ? "LANGUAGE_PRESENT" : required ? "REQUIRED_LANGUAGE_MISSING" : "OPTIONAL_LANGUAGE_MISSING",
                points, "Idioma " + name + (found ? " encontrado" : " não encontrado") + ": " + points + " pontos; nível não avaliado.",
                "normalizedName", name, "found", found, "required", required, "multiplier", multiplier,
                "levelEvaluated", false);
    }

    private static Set<String> candidateLanguages(Resume resume) {
        Set<String> languages = new LinkedHashSet<>();
        if (resume == null) {
            return languages;
        }
        for (CandidateLanguage relationship : RankerSupport.safeList(resume.getLanguages())) {
            if (relationship != null && relationship.getLanguage() != null) {
                String name = RankerSupport.normalize(relationship.getLanguage().getName());
                if (!name.isEmpty()) {
                    languages.add(name);
                }
            }
        }
        return languages;
    }

    private static Map<String, Boolean> requirements(Vacancy vacancy) {
        Map<String, Boolean> requirements = new LinkedHashMap<>();
        if (vacancy == null) {
            return requirements;
        }
        for (VacancyLanguage relationship : RankerSupport.safeList(vacancy.getLanguages())) {
            if (relationship != null && relationship.getLanguage() != null) {
                String name = RankerSupport.normalize(relationship.getLanguage().getName());
                if (!name.isEmpty()) {
                    requirements.merge(name, relationship.isRequired(), Boolean::logicalOr);
                }
            }
        }
        return requirements;
    }
}
