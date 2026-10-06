package br.com.careermatcher.controllers;

import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.services.CandidatoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/careermatcher")
@RequiredArgsConstructor
public class CandidatoController {
    private final CandidatoService candidatoService;

    @GetMapping({"/candidatos", "/candidatos/"})
    public List<CandidateView> getAllCandidates() { return candidatoService.findAll(); }
    @GetMapping({"/candidatos/{id}", "/candidato/{id}"})
    public CandidateView getCandidateById(@PathVariable long id) { return candidatoService.findCandidate(id); }
    @DeleteMapping("/candidatos/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        candidatoService.delete(id);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/candidatos/page")
    public PageView<CandidateSummary> page(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "asc") String direction) {
        return candidatoService.findPage(page, size, search, sort, direction);
    }

}
