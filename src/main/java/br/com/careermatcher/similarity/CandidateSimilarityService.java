package br.com.careermatcher.similarity;

import br.com.careermatcher.DTOs.matching.CandidateDTO;
import br.com.careermatcher.security.CurrentActor;
import br.com.careermatcher.validations.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.stream.Collectors;
import static br.com.careermatcher.similarity.SimilarityResponse.*;
import static br.com.careermatcher.similarity.SimilaritySnapshot.*;

/** Independent, synchronous service entry point. No matching execution or profile updates. */
@Service
public class CandidateSimilarityService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CandidateSimilarityService.class);
    private final CurrentActor actor;
    private final SimilaritySnapshotRepository snapshots;
    private final SimilaritySettings settings;
    public CandidateSimilarityService(CurrentActor actor, SimilaritySnapshotRepository snapshots, SimilaritySettings settings) {
        this.actor = actor; this.snapshots = snapshots; this.settings = settings;
    }
    public SimilarityResponse findSimilar(CandidateDTO candidate, Integer requestedK) {
        actor.requireCompany();
        var p = settings.parameters();
        long id;
        try {
            if (candidate == null || candidate.id() == null || !candidate.id().matches("[0-9]+")) throw new NumberFormatException();
            id = Long.parseLong(candidate.id());
        } catch (NumberFormatException ex) { throw invalid("id", "Informe o identificador do candidato cadastrado."); }
        int k = requestedK == null ? p.defaultK() : requestedK;
        if (k < 1 || k > p.maxK()) throw invalid("k", "Informe k entre 1 e " + p.maxK() + ".");
        long start = System.nanoTime();
        var snapshot = snapshots.load();
        long loaded = System.nanoTime();
        var graph = new SkillProjection(snapshot);
        if (!graph.profiles.containsKey(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro não encontrado.");
        double[] sigma = graph.sigma(id);
        var source = graph.skillNames.entrySet().stream().filter(s -> graph.frequency(id, s.getKey()) > 0)
                .map(s -> new SourceSkill(Long.toString(s.getKey()), s.getValue(), graph.frequency(id, s.getKey()),
                        sigma[graph.index.get(new SkillProjection.Node(SkillProjection.Type.SKILL, s.getKey()))], counts(graph, id, s.getKey()))).toList();
        if (source.isEmpty()) return empty(id, k, source, Reason.NO_SKILLS);
        var targets = graph.targets(id, sigma);
        if (targets.isEmpty()) return empty(id, k, source, Reason.NO_SIMILAR_CANDIDATES);
        Fora.Result result;
        try { result = new Fora().compute(graph, sigma, targets, k, p); }
        catch (Fora.BudgetExceededException exhausted) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Orçamento da busca de similares esgotado antes de uma rodada completa.");
        }
        var candidates = result.top().stream().map(i -> {
            var profile = graph.profiles.get(graph.nodes.get(i).id());
            var shared = source.stream().filter(s -> graph.frequency(profile.id(), Long.parseLong(s.skillId())) > 0)
                    .map(s -> new SharedSkill(s.skillId(), s.name(), s.frequency(), graph.frequency(profile.id(), Long.parseLong(s.skillId())),
                            s.evidenceCounts(), counts(graph, profile.id(), Long.parseLong(s.skillId())))).toList();
            return new SimilarCandidate(Long.toString(profile.id()), profile.name(), profile.professionalTitle(), result.scores()[i], shared, shared.isEmpty());
        }).toList();
        log.debug("Similarity projection=evidence-v1 config={} extractionMs={} processingMs={} nodes={} arcs={} pushes={} walks={} rounds={} delta={} quality={}",
                p, (loaded-start)/1_000_000, (System.nanoTime()-loaded)/1_000_000, graph.nodes.size(), graph.multiplicity,
                result.pushes(), result.walks(), result.rounds(), result.delta(), result.qualityStatus());
        return new SimilarityResponse(Long.toString(id), k, candidates.size(), source, candidates, null, result.qualityStatus(), result.approximationTargetMet());
    }
    private static Map<SourceType, Long> counts(SkillProjection g, long c, long s) {
        return Collections.unmodifiableMap(g.evidence.getOrDefault(new SkillProjection.Pair(c, s), List.of()).stream()
                .collect(Collectors.groupingBy(Evidence::sourceType, TreeMap::new, Collectors.counting())));
    }
    private static SimilarityResponse empty(long id, int k, List<SourceSkill> source, Reason reason) {
        return new SimilarityResponse(Long.toString(id), k, 0, source, List.of(), reason, Fora.QualityStatus.NOT_APPLICABLE, false);
    }
    private static ValidationException invalid(String field, String message) {
        return new ValidationException(List.of(new ValidationException.FieldError(field, message)));
    }
}
