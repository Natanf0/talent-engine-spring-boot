package br.com.careermatcher.relocation;
import org.springframework.web.bind.annotation.*;
import br.com.careermatcher.DTOs.crud.CrudDtos.*;
@RestController
public class RelocationController {
    private final RelocationService service;
    public RelocationController(RelocationService service) { this.service=service; }
    public record Verification(String token, Long experienceId) {}
    @PostMapping("/api/careermatcher/internal-relocation/verifications")
    public RelocationService.Link generate() { return service.generate(); }
    @GetMapping("/api/careermatcher/internal-relocation")
    public PageView<CandidateSummary> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String search,
            @RequestParam(required=false) String sort,@RequestParam(defaultValue="asc") String direction) {
        return service.list(page,size,search,sort,direction);
    }
    @PostMapping("/api/me/employee-verification/preview")
    public RelocationService.Preview preview(@RequestBody Verification request) { return service.preview(request.token()); }
    @PostMapping("/api/me/employee-verification")
    public void confirm(@RequestBody Verification request) {
        if (request.experienceId()==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Selecione uma experiência.");
        service.confirm(request.token(),request.experienceId());
    }
}
