package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.crud.CrudDtos;
import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.security.CurrentActor;
import br.com.careermatcher.validations.ValidationException;
import br.com.careermatcher.validations.ValidationException.FieldError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Resolves authoritative participants from IDs after checking workspace ownership. */
@Component
public class MatchingSelectionValidator {
    public record SelectedIds(List<Long> candidateIds, List<Long> vacancyIds, boolean allCandidates, List<Long> excludedCandidateIds) {
        public SelectedIds {
            candidateIds = List.copyOf(candidateIds);
            vacancyIds = List.copyOf(vacancyIds);
            excludedCandidateIds = List.copyOf(excludedCandidateIds);
        }
        public SelectedIds(List<Long> candidateIds,List<Long> vacancyIds) {
            this(candidateIds,vacancyIds,false,List.of());
        }
    }
    public record Selection(List<CrudDtos.CandidateView> candidates, List<CrudDtos.VacancyView> vacancies) {}
    private final GraphCrudRepository repository;
    private final ObjectMapper mapper;
    private final CurrentActor actor;
    private final int batchSize;
    public MatchingSelectionValidator(GraphCrudRepository repository, ObjectMapper mapper, CurrentActor actor,
            @Value("${matching.selection.batch-size:200}") int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("matching.selection.batch-size deve ser maior que zero.");
        this.repository = repository; this.mapper = mapper; this.actor = actor;
        this.batchSize = batchSize;
    }

    @Transactional(readOnly = true)
    public Selection validate(JsonNode body) {
        return resolve(parse(body));
    }

    /** Validates the request without querying participants. */
    public SelectedIds parse(JsonNode body) {
        actor.requireStaff();
        if (!(body instanceof ObjectNode object)) throw error("body", "Objeto JSON obrigatório.");
        object.fieldNames().forEachRemaining(field -> {
            if (!Set.of("candidateIds","vacancyIds","allCandidates","excludedCandidateIds").contains(field))
                throw error(field, "Campo desconhecido.");
        });
        if (object.has("allCandidates") && !object.get("allCandidates").isBoolean()) throw error("allCandidates","Informe true ou false.");
        boolean all=object.path("allCandidates").asBoolean(false);
        var vacancyIds=ids(object.get("vacancyIds"),"vacancyIds");
        var excluded=object.has("excludedCandidateIds")?ids(object.get("excludedCandidateIds"),"excludedCandidateIds",true):List.<Long>of();
        if (all) {
            if (object.has("candidateIds") && (!object.get("candidateIds").isArray() || !object.get("candidateIds").isEmpty()))
                throw error("candidateIds","Não combine IDs individuais com a seleção de todos os candidatos.");
            return new SelectedIds(List.of(),vacancyIds,true,excluded);
        }
        if (!excluded.isEmpty()) throw error("excludedCandidateIds","Exclusões exigem a seleção de todos os candidatos.");
        return new SelectedIds(ids(object.get("candidateIds"),"candidateIds"),vacancyIds,false,List.of());
    }

    @Transactional(readOnly = true)
    public Selection resolve(SelectedIds ids) {
        actor.requireStaff();
        // Complete authorization before ranking or writing an execution object.
        var vacancies = repository.findByIds(Kind.VACANCY, ids.vacancyIds(), batchSize).stream().map(node -> mapper.convertValue(node,
                CrudDtos.VacancyView.class)).toList();
        List<Long> candidateIds=ids.candidateIds();
        if (ids.allCandidates()) {
            var excluded=new HashSet<>(ids.excludedCandidateIds());
            candidateIds=repository.talentPoolIds().stream().filter(id->!excluded.contains(id)).toList();
        }
        if (candidateIds.isEmpty()) throw error("candidateIds","Nenhum candidato disponível no Banco de Talentos para esta seleção.");
        var candidates = repository.findTalentPoolByIds(candidateIds, batchSize).stream().map(node -> mapper.convertValue(node,
                CrudDtos.CandidateView.class)).toList();
        return new Selection(candidates, vacancies);
    }

    private List<Long> ids(JsonNode values, String field) { return ids(values,field,false); }
    private List<Long> ids(JsonNode values, String field,boolean allowEmpty) {
        if (values == null || !values.isArray() || !allowEmpty && values.isEmpty()) throw error(field, "Selecione ao menos um participante.");
        var result = new ArrayList<Long>();
        var seen = new HashSet<Long>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
                throw error(field + "[" + index + "]", "ID numérico obrigatório.");
            if (!seen.add(value.longValue())) throw error(field + "[" + index + "]", "ID repetido.");
            result.add(value.longValue());
        }
        return List.copyOf(result);
    }
    private static ValidationException error(String field, String message) {
        return new ValidationException(List.of(new FieldError(field, message)));
    }
}
