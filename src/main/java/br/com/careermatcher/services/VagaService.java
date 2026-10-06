package br.com.careermatcher.services;

import br.com.careermatcher.DTOs.crud.CrudDtos.VacancyView;
import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.DTOs.crud.CrudDtos.VacancySummary;
import br.com.careermatcher.validations.VagaValidations;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static br.com.careermatcher.crud.GraphSchema.Kind.VACANCY;

@Service
@RequiredArgsConstructor
@Transactional
public class VagaService {
    private final GraphCrudRepository repository;
    private final VagaValidations validations;
    private final ObjectMapper mapper;

    public VacancyView create(ObjectNode input) {
        ObjectNode clean = validations.validate(input);
        return view(repository.find(VACANCY, repository.create(VACANCY, clean)));
    }
    public VacancyView patch(long id, ObjectNode patch) {
        repository.lock(VACANCY, id);
        ObjectNode clean = validations.patch(repository.find(VACANCY, id), patch);
        Set<String> changed = new HashSet<>();
        patch.fieldNames().forEachRemaining(changed::add);
        repository.update(VACANCY, id, clean, changed);
        return view(repository.find(VACANCY, id));
    }
    @Transactional(readOnly = true)
    public List<VacancyView> findAll() { return repository.findAll(VACANCY).stream().map(this::view).toList(); }
    @Transactional(readOnly = true)
    public VacancyView find(long id) { return view(repository.find(VACANCY, id)); }
    @Transactional(readOnly = true)
    public VacancyView findPublic(long id) { return view(repository.findPublicVacancy(id)); }
    public void delete(long id) { repository.lock(VACANCY, id); repository.delete(VACANCY, id); }
    private VacancyView view(ObjectNode data) { return mapper.convertValue(data, VacancyView.class); }
    @Transactional(readOnly = true)
    public PageView<VacancySummary> findPage(int page, int size) {
        return findPage(page, size, null, null, "asc");
    }
    @Transactional(readOnly = true)
    public PageView<VacancySummary> findPage(int page, int size, String search, String sort, String direction) {
        GraphCrudRepository.validatePage(page, size);
        GraphCrudRepository.validateSort(VACANCY, sort, direction);
        long total = repository.count(VACANCY, search);
        var content = repository.findPage(VACANCY, page, size, search, sort, direction).stream()
                .map(item -> mapper.convertValue(item, VacancySummary.class)).toList();
        return new PageView<>(content, page, size, total, (total + size - 1) / size);
    }

}
