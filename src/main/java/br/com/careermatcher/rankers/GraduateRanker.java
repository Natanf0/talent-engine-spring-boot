package br.com.careermatcher.rankers;

import br.com.careermatcher.enums.DEGREE;
import br.com.careermatcher.enums.PesosRanker;
import br.com.careermatcher.models.Candidate;
import br.com.careermatcher.models.Graduate;
import br.com.careermatcher.models.Vacancy;
import br.com.careermatcher.relations.VacancyGraduate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;

@Component
public class GraduateRanker extends ExplainedRanker {

    /**
     * The graph has no AND/OR marker. The deterministic interpretation is OR
     * among alternatives of the same degree and cumulative across distinct
     * DEGREE values; required and desirable groups remain separate.
     */
    @Override
    public RankerDetail evaluate(Candidate candidate, Vacancy vacancy, boolean candidatePerspective) {
        List<Graduate> candidateDegrees = candidate == null || candidate.getResume() == null
                ? List.of()
                : RankerSupport.safeList(candidate.getResume().getGraduates());
        List<VacancyGraduate> requirements = vacancy == null
                ? List.of()
                : RankerSupport.safeList(vacancy.getGraduate());

        List<RankerDetail.Contribution> groups = new ArrayList<>();
        for (DEGREE degree : DEGREE.values()) {
            groups.add(scoreGroup(candidateDegrees, requirements, degree, true));
            groups.add(scoreGroup(candidateDegrees, requirements, degree, false));
        }
        return RankerDetail.of("GraduateRanker", "Melhor alternativa por grau e obrigatoriedade; grupos somados.", groups);
    }


    private static RankerDetail.Contribution scoreGroup(
            List<Graduate> candidates,
            List<VacancyGraduate> requirements,
            DEGREE degree,
            boolean requiredGroup
    ) {
        boolean hasRequirement = false;
        double bestScore = Double.NEGATIVE_INFINITY;
        List<Map<String, Object>> alternatives = new ArrayList<>();

        for (VacancyGraduate relationship : requirements) {
            Graduate requirement = relationship == null ? null : relationship.getGraduate();
            if (relationship == null || relationship.isRequired() != requiredGroup
                    || requirement == null || requirement.getDegree() != degree) {
                continue;
            }
            hasRequirement = true;
            if (candidates.isEmpty()) alternatives.add(comparison(null, requirement, false, 0));
            for (Graduate candidate : candidates) {
                if (matches(candidate, requirement)) {
                    double multiplier = requiredGroup ? PesosRanker.VAGA_POS_GRADUACAO_OBRIGATORIA.getPeso() : PesosRanker.VAGA_POS_GRADUACAO_DESEJADA.getPeso();
                    double potentialPoints = matchedScore(candidate, requirement) * multiplier;
                    bestScore = Math.max(bestScore, potentialPoints);
                    alternatives.add(comparison(candidate, requirement, true, potentialPoints));
                } else {
                    alternatives.add(comparison(candidate, requirement, false, 0));
                }
            }
        }

        double points = !hasRequirement ? 0 : bestScore != Double.NEGATIVE_INFINITY ? bestScore
                : requiredGroup ? PesosRanker.POS_GRADUACAO_NAO_CUMPRIDA.getPeso() * PesosRanker.VAGA_POS_GRADUACAO_OBRIGATORIA.getPeso() : 0;
        String code = !hasRequirement ? "NO_GRADUATE_REQUIREMENT" : bestScore != Double.NEGATIVE_INFINITY
                ? "BEST_GRADUATE_ALTERNATIVE" : "GRADUATE_NOT_MET";
        return RankerDetail.contribution(code, points, "Grau " + degree + ", grupo " + (requiredGroup ? "obrigatório" : "desejável")
                + ": somente a melhor alternativa contribui; " + points + " pontos.",
                "degree", degree, "required", requiredGroup, "alternatives", alternatives);
    }

    private static Map<String, Object> comparison(Graduate candidate, Graduate requirement, boolean matches, double points) {
        return RankerDetail.facts("candidateEducationId", candidate == null || candidate.getId() == null ? null : candidate.getId().toString(),
                "requirementEducationId", requirement.getId() == null ? null : requirement.getId().toString(),
                "candidateDegree", candidate == null ? null : candidate.getDegree(), "requiredDegree", requirement.getDegree(),
                "candidateField", candidate == null ? null : candidate.getField(), "requiredField", requirement.getField(),
                "candidateSubarea", candidate == null ? null : candidate.getSubarea(), "requiredSubarea", requirement.getSubarea(),
                "matches", matches, "potentialPoints", points,
                "institutionBonusApplied", matches && !RankerSupport.normalize(requirement.getInstitution()).isEmpty()
                        && RankerSupport.normalize(requirement.getInstitution()).equals(RankerSupport.normalize(candidate.getInstitution())),
                "completionBonusApplied", matches && candidate.getCompletionDate() != null && requirement.getCompletionDate() != null
                        && !candidate.getCompletionDate().after(requirement.getCompletionDate()));
    }

    private static boolean matches(Graduate candidate, Graduate requirement) {
        if (candidate == null || candidate.getDegree() != requirement.getDegree()) {
            return false;
        }
        String requiredField = RankerSupport.normalize(requirement.getField());
        String requiredSubarea = RankerSupport.normalize(requirement.getSubarea());
        if (requiredField.isEmpty() && requiredSubarea.isEmpty()) {
            return true;
        }
        return (!requiredField.isEmpty() && requiredField.equals(RankerSupport.normalize(candidate.getField())))
                || (!requiredSubarea.isEmpty()
                && requiredSubarea.equals(RankerSupport.normalize(candidate.getSubarea())));
    }

    private static double matchedScore(Graduate candidate, Graduate requirement) {
        double score = PesosRanker.POS_GRADUACAO_CAMPO_OU_SUBAREA_CUMPRIDO.getPeso();
        String institution = RankerSupport.normalize(requirement.getInstitution());
        if (!institution.isEmpty() && institution.equals(RankerSupport.normalize(candidate.getInstitution()))) {
            score += PesosRanker.POS_GRADUACAO_INSTITUICAO_CUMPRIDA.getPeso();
        }
        if (candidate.getCompletionDate() != null && requirement.getCompletionDate() != null
                && !candidate.getCompletionDate().after(requirement.getCompletionDate())) {
            score += PesosRanker.POS_GRADUACAO_TEMPO_CONCLUIDO_CUMPRIDO.getPeso();
        }
        return score;
    }
}
