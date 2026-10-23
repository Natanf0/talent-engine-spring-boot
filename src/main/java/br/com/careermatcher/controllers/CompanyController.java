package br.com.careermatcher.controllers;

import br.com.careermatcher.DTOs.company.CompanyDtos.CompanyAboutRequest;
import br.com.careermatcher.DTOs.company.CompanyDtos.CompanyView;
import br.com.careermatcher.services.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/company")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyService companyService;

    @GetMapping
    public CompanyView find() { return companyService.find(); }

    @PatchMapping
    public CompanyView update(@RequestBody CompanyAboutRequest request) { return companyService.update(request); }
}
