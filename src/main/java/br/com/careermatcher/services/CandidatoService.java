package br.com.careermatcher.services;

import br.com.careermatcher.DTOs.crud.CrudDtos.CandidateView;
import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.DTOs.crud.CrudDtos.CandidateSummary;
import br.com.careermatcher.validations.CandidatoValidations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static br.com.careermatcher.crud.GraphSchema.Kind.CANDIDATE;

@Service
@RequiredArgsConstructor
@Transactional
public class CandidatoService {
    private final GraphCrudRepository repository;
    private final CandidatoValidations validations;
    private final ObjectMapper mapper;

    /** Called only by account registration inside the enclosing account transaction. */
    public CandidateView registerCandidate(ObjectNode input) {
        ObjectNode clean = validations.validate(input);
        return view(repository.registeredCandidate(repository.createCandidateForRegistration(clean)));
    }

    public CandidateView patch(long id, ObjectNode patch) {
        repository.requireCandidateUpdate(id);
        repository.lock(CANDIDATE, id);
        ObjectNode clean = validations.patch(repository.find(CANDIDATE, id), patch);
        Set<String> changed = new HashSet<>();
        patch.fieldNames().forEachRemaining(changed::add);
        repository.update(CANDIDATE, id, clean, changed);
        return view(repository.find(CANDIDATE, id));
    }

    @Transactional(readOnly = true)
    public List<CandidateView> findAll() { return repository.findAll(CANDIDATE).stream().map(this::view).toList(); }

    @Transactional(readOnly = true)
    public CandidateView findCandidate(long id) { return view(repository.find(CANDIDATE, id)); }

    public void delete(long id) { repository.lock(CANDIDATE, id); repository.delete(CANDIDATE, id); }

    private CandidateView view(ObjectNode data) {
        ObjectNode publicData = data.deepCopy();
        publicData.remove("password");
        return mapper.convertValue(publicData, CandidateView.class);
    }
    @Transactional(readOnly = true)
    public PageView<CandidateSummary> findPage(int page, int size) {
        return findPage(page, size, null, null, "asc");
    }
    @Transactional(readOnly = true)
    public PageView<CandidateSummary> findPage(int page, int size, String search, String sort, String direction) {
        GraphCrudRepository.validatePage(page, size);
        GraphCrudRepository.validateSort(CANDIDATE, sort, direction);
        long total = repository.count(CANDIDATE, search);
        var content = repository.findPage(CANDIDATE, page, size, search, sort, direction).stream()
                .map(item -> mapper.convertValue(item, CandidateSummary.class)).toList();
        return new PageView<>(content, page, size, total, (total + size - 1) / size);
    }

}
