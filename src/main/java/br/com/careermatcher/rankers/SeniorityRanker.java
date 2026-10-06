package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.PesosRanker;
import br.com.careermatcher.enums.SENIORITY;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Experience;
import br.com.careermatcher.models.Resume;
import br.com.careermatcher.models.Vacancy;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class SeniorityRanker extends ExplainedRanker {

    @Override
    public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        if (vacancy == null || vacancy.getSeniority() == null) {
            return RankerDetail.single("SeniorityRanker", "NO_SENIORITY_REQUIREMENT", 0,
                    "Vaga sem senioridade definida; contribuição zero.");
        }
        SENIORITY inferred = inferCandidateSeniority(candidate);
        int candidateValue = inferred.getValor();
        int requiredValue = vacancy.getSeniority().getValor();
        double points;
        String code;
        String formula;
        if (candidateValue == requiredValue) {
            points = PesosRanker.SENIORIDADE_CUMPRIDA.getPeso();
            code = "SENIORITY_EQUAL";
            formula = "SENIORIDADE_CUMPRIDA";
        } else if (candidateValue < requiredValue) {
            double distance = (double) (requiredValue - candidateValue) / Math.max(1, requiredValue);
            points = PesosRanker.SENIORIDADE_ABAIXO.getPeso() * distance;
            code = "SENIORITY_BELOW";
            formula = "SENIORIDADE_ABAIXO * (required - candidate) / max(1, required)";
        } else {
            double ratio = (double) candidateValue / Math.max(1, requiredValue);
            points = PesosRanker.SENIORIDADE_ACIMA.getPeso() * ratio;
            code = "SENIORITY_ABOVE";
            formula = "SENIORIDADE_ACIMA * candidate / max(1, required)";
        }
        List<Experience> experiences = candidate == null || candidate.getResume() == null ? List.of()
                : RankerSupport.safeList(candidate.getResume().getExperiences()).stream()
                        .filter(java.util.Objects::nonNull).toList();
        boolean declared = experiences.stream().anyMatch(e -> e.getSeniority() != null);
        return RankerDetail.single("SeniorityRanker", code, points,
                "Senioridade inferida " + inferred + ", exigida " + vacancy.getSeniority() + ": " + points + " pontos.",
                "candidateSeniority", inferred, "requiredSeniority", vacancy.getSeniority(),
                "candidateValue", candidateValue, "requiredValue", requiredValue, "formula", formula,
                "inferenceSource", experiences.isEmpty() ? "NO_EXPERIENCE" : declared ? "HIGHEST_DECLARED" : "TOTAL_MONTHS",
                "totalMonths", experiences.stream().mapToLong(e -> RankerSupport.nonNegativeMonths(e.getDurationMonths())).sum());
    }

    /**
     * Prefers the highest declared experience seniority. Only when none is
     * declared does it infer Junior/Pleno/Senior from total months (24 and 60).
     */
    public SENIORITY inferCandidateSeniority(Candidate candidate) {
        Resume resume = candidate == null ? null : candidate.getResume();
        List<Experience> experiences = resume == null
                ? List.of()
                : RankerSupport.safeList(resume.getExperiences()).stream()
                .filter(java.util.Objects::nonNull)
                .toList();

        if (experiences.isEmpty()) {
            return SENIORITY.ESTAGIARIO;
        }

        SENIORITY declared = experiences.stream()
                .map(Experience::getSeniority)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.comparingInt(SENIORITY::getValor).thenComparingInt(Enum::ordinal))
                .orElse(null);
        if (declared != null) {
            return declared;
        }

        long totalMonths = experiences.stream()
                .mapToLong(experience -> RankerSupport.nonNegativeMonths(experience.getDurationMonths()))
                .sum();
        return inferFromMonths(totalMonths);
    }

    static SENIORITY inferFromMonths(long totalMonths) {
        if (totalMonths < 24) {
            return SENIORITY.JUNIOR;
        }
        if (totalMonths < 60) {
            return SENIORITY.PLENO;
        }
        return SENIORITY.SENIOR;
    }
}
