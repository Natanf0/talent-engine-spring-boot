package br.com.careermatcher.security;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import static br.com.careermatcher.security.AuthDtos.*;

@RestController
@RequestMapping("/api/admin/users")
public class AdminController {
    private final CollaboratorService service;
    public AdminController(CollaboratorService service) { this.service=service; }
    @GetMapping public PageView<AuthUser> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.list(page,size); }
    @PostMapping public InvitationResponse invite(@Valid @RequestBody InviteRequest request) { return service.invite(request); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable String id) { service.delete(id); }
}
