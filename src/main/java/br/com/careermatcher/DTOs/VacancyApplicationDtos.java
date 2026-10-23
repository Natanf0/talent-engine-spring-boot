package br.com.careermatcher.DTOs;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public final class VacancyApplicationDtos {
    private VacancyApplicationDtos() {}

    public record ApplicationRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal income
    ) {}

    public record ApplicationResponse(long vacancyId, long companyId, String companyName, Double income,
                                      String message) {}

    public record ApplicationStatus(boolean applied) {}
}
