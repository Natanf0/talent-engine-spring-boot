package br.com.careermatcher.controllers;

import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.services.VagaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/careermatcher/vagas")
@RequiredArgsConstructor
public class VagaController {
    private final VagaService vagaService;

    @GetMapping({"", "/"})
    public List<VacancyView> findAllVagas() { return vagaService.findAll(); }
    @GetMapping("/{id}")
    public VacancyView find(@PathVariable long id) { return vagaService.findPublic(id); }
    @PostMapping({"", "/"})
    public ResponseEntity<VacancyView> create(@RequestBody VacancyWriteRequest request) {
        VacancyView result = vagaService.create(request.fields());
        return ResponseEntity.created(URI.create("/api/careermatcher/vagas/" + result.id())).body(result);
    }
    @PatchMapping("/{id}")
    public VacancyView patch(@PathVariable long id, @RequestBody VacancyWriteRequest request) {
        return vagaService.patch(id, request.fields());
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        vagaService.delete(id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/page")
    public PageView<VacancySummary> page(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "asc") String direction) {
        return vagaService.findPage(page, size, search, sort, direction);
    }

}
