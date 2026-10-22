package br.com.careermatcher.DTOs.company;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class CompanyDtos {
    private CompanyDtos() {}

    public record CompanyAboutRequest(String about) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompanyView(Long id, String name, String about) {}
}
