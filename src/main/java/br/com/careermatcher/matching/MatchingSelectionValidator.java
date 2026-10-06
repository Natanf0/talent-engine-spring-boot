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
    public record SelectedIds(List<Long> candidateIds, List<Long> vacancyIds) {
        public SelectedIds {
            candidateIds = List.copyOf(candidateIds);
            vacancyIds = List.copyOf(vacancyIds);
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
            if (!field.equals("candidateIds") && !field.equals("vacancyIds")) throw error(field, "Campo desconhecido.");
        });
        var candidateIds = ids(object.get("candidateIds"), "candidateIds");
        var vacancyIds = ids(object.get("vacancyIds"), "vacancyIds");
        return new SelectedIds(candidateIds, vacancyIds);
    }

    @Transactional(readOnly = true)
    public Selection resolve(SelectedIds ids) {
        actor.requireStaff();
        // Complete authorization before ranking or writing an execution object.
        var vacancies = repository.findByIds(Kind.VACANCY, ids.vacancyIds(), batchSize).stream().map(node -> mapper.convertValue(node,
                CrudDtos.VacancyView.class)).toList();
        var candidates = repository.findByIds(Kind.CANDIDATE, ids.candidateIds(), batchSize).stream().map(node -> mapper.convertValue(node,
                CrudDtos.CandidateView.class)).toList();
        return new Selection(candidates, vacancies);
    }

    private List<Long> ids(JsonNode values, String field) {
        if (values == null || !values.isArray() || values.isEmpty()) throw error(field, "Selecione ao menos um participante.");
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
