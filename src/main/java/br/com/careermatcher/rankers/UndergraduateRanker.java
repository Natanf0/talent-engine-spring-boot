package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.PesosRanker;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Undergraduate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.VacancyUndergrad;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Component
public class UndergraduateRanker extends ExplainedRanker {

    /**
     * Requirements with the same required/desirable flag are alternatives;
     * only the best matching correlated course in each group is scored.
     */
    @Override
    public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        Undergraduate candidateDegree = candidate == null || candidate.getResume() == null
                ? null
                : candidate.getResume().getUndergraduate();
        List<VacancyUndergrad> requirements = vacancy == null
                ? List.of()
                : RankerSupport.safeList(vacancy.getUndergraduates());

        return RankerDetail.of("UndergraduateRanker", "Melhor alternativa em cada grupo obrigatório/desejável; grupos somados.",
                List.of(scoreGroup(candidateDegree, requirements, true), scoreGroup(candidateDegree, requirements, false)));
    }


    private static RankerDetail.Contribution scoreGroup(
            Undergraduate candidate,
            List<VacancyUndergrad> requirements,
            boolean requiredGroup
    ) {
        boolean hasValidRequirement = false;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<Map<String, Object>> alternatives = new ArrayList<>();

        for (VacancyUndergrad relationship : requirements) {
            Undergraduate requirement = relationship == null ? null : relationship.getUndergraduate();
            if (relationship == null || relationship.isRequired() != requiredGroup || !isValid(requirement)) {
                continue;
            }
            hasValidRequirement = true;
            if (matches(candidate, requirement)) {
                double multiplier = requiredGroup ? PesosRanker.VAGA_GRADUACAO_OBRIGATORIA.getPeso() : PesosRanker.VAGA_GRADUACAO_DESEJADA.getPeso();
                double potentialPoints = matchedScore(candidate, requirement) * multiplier;
                bestScore = Math.max(bestScore, potentialPoints);
                alternatives.add(comparison(candidate, requirement, true, potentialPoints));
            } else {
                alternatives.add(comparison(candidate, requirement, false, 0));
            }
        }

        double points = !hasValidRequirement ? 0 : bestScore != Double.NEGATIVE_INFINITY ? bestScore
                : requiredGroup ? PesosRanker.GRADUACAO_NAO_CUMPRIDA.getPeso() * PesosRanker.VAGA_GRADUACAO_OBRIGATORIA.getPeso() : 0;
        String code = !hasValidRequirement ? "NO_UNDERGRADUATE_REQUIREMENT" : bestScore != Double.NEGATIVE_INFINITY
                ? "BEST_UNDERGRADUATE_ALTERNATIVE" : "UNDERGRADUATE_NOT_MET";
        return RankerDetail.contribution(code, points, "Grupo " + (requiredGroup ? "obrigatório" : "desejável")
                + ": somente a melhor alternativa contribui; " + points + " pontos.",
                "required", requiredGroup, "alternatives", alternatives);
    }

    private static Map<String, Object> comparison(Undergraduate candidate, Undergraduate requirement, boolean matches, double points) {
        return RankerDetail.facts("candidateEducationId", candidate == null || candidate.getId() == null ? null : candidate.getId().toString(),
                "requirementEducationId", requirement.getId() == null ? null : requirement.getId().toString(),
                "candidateField", candidate == null ? null : candidate.getField(), "requiredField", requirement.getField(),
                "candidateType", candidate == null ? null : candidate.getUndergraduateType(), "requiredType", requirement.getUndergraduateType(),
                "matches", matches, "potentialPoints", points,
                "institutionBonusApplied", matches && !RankerSupport.normalize(requirement.getInstitution()).isEmpty()
                        && RankerSupport.normalize(requirement.getInstitution()).equals(RankerSupport.normalize(candidate.getInstitution())),
                "completionBonusApplied", matches && candidate.getCompletionDate() != null && requirement.getCompletionDate() != null
                        && !candidate.getCompletionDate().after(requirement.getCompletionDate()));
    }

    private static boolean isValid(Undergraduate requirement) {
        return requirement != null && !RankerSupport.normalize(requirement.getField()).isEmpty();
    }

    private static boolean matches(Undergraduate candidate, Undergraduate requirement) {
        return candidate != null
                && RankerSupport.normalize(candidate.getField()).equals(RankerSupport.normalize(requirement.getField()))
                && (requirement.getUndergraduateType() == null
                || requirement.getUndergraduateType() == candidate.getUndergraduateType());
    }

    private static double matchedScore(Undergraduate candidate, Undergraduate requirement) {
        double score = PesosRanker.GRADUACAO_CURSO_CUMPRIDO.getPeso();
        String institution = RankerSupport.normalize(requirement.getInstitution());
        if (!institution.isEmpty() && institution.equals(RankerSupport.normalize(candidate.getInstitution()))) {
            score += PesosRanker.GRADUACAO_INSTITUICAO_CUMPRIDO.getPeso();
        }
        if (candidate.getCompletionDate() != null && requirement.getCompletionDate() != null
                && !candidate.getCompletionDate().after(requirement.getCompletionDate())) {
            score += PesosRanker.GRADUACAO_TEMPO_CONCLUIDO_CUMPRIDO.getPeso();
        }
        return score;
    }
}
