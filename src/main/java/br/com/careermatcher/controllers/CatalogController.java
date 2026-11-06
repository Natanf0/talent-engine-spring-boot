package br.com.careermatcher.controllers;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.repositories.CatalogRepository;
import br.com.careermatcher.validations.TextSanitizer;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogRepository catalogs;
    private final TextSanitizer sanitizer;
    public CatalogController(CatalogRepository catalogs, TextSanitizer sanitizer) {
        this.catalogs = catalogs;
        this.sanitizer = sanitizer;
    }
    @GetMapping("/{catalog}")
    public List<CatalogRepository.Option> search(@PathVariable String catalog,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Kind kind = switch (catalog) {
            case "skills" -> Kind.SKILL;
            case "languages" -> Kind.LANGUAGE;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catálogo não encontrado.");
        };
        if (query.length() > 100 || limit < 1 || limit > 50 || offset < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Busca de até 100 caracteres, limite entre 1 e 50 e offset não negativo.");
        return catalogs.search(kind, sanitizer.plain(query), limit, offset);
    }
}
