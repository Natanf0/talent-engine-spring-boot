package br.com.careermatcher.ai;

import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.enums.Role;
import br.com.careermatcher.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiExtractionControllerTest {
    private AiExtractionService service;
    private MockMvc mvc;
    @BeforeEach void setup() {
        service = mock(AiExtractionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AiExtractionController(new CurrentActor(), service))
                .setControllerAdvice(new AiExtractionExceptionHandler()).build();
        when(service.extract(any(), any(), any())).thenReturn(new AiExtractionDtos.ExtractionResponse(new ObjectMapper().createObjectNode(), List.of(), List.of()));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
    private void actor(Role role) {
        var user = new AuthUser("test", "Test", "test@example.com", role,
                role == Role.CANDIDATO ? null : 1L, role == Role.CANDIDATO ? 2L : null, true, null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, "", List.of()));
    }
    @Test void requiresCandidateIdentityAndDoesNotAcceptStaffForPersonalImport() throws Exception {
        var request = multipart("/api/me/candidate/ai-extraction").file(PdfTextExtractionServiceTest.file("pdf".getBytes()));
        mvc.perform(request).andExpect(status().isUnauthorized());
        actor(Role.RECRUTADOR); mvc.perform(request).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        actor(Role.CANDIDATO); mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.pendingFields").isArray());
        verify(service).extract(eq(Kind.CANDIDATE), any(), isNull());
    }
    @Test void staffCanSubmitVacancyTextAndCandidatesCannot() throws Exception {
        actor(Role.CANDIDATO);
        mvc.perform(multipart("/api/careermatcher/vagas/ai-extraction").param("text", "Vaga Java")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        for (Role role : List.of(Role.ADMINISTRADOR, Role.GERENTE, Role.RECRUTADOR)) {
            actor(role);
            mvc.perform(multipart("/api/careermatcher/vagas/ai-extraction").param("text", "Vaga Java")).andExpect(status().isOk());
        }
        verify(service, times(3)).extract(Kind.VACANCY, null, "Vaga Java");
    }
    @Test void rejectsAllVacancyFileFieldsEvenWithValidTextBeforeCallingService() throws Exception {
        actor(Role.RECRUTADOR);
        for (String field : List.of("file", "attachment")) {
            for (String text : List.of("", "Vaga Java")) {
                mvc.perform(multipart("/api/careermatcher/vagas/ai-extraction")
                        .file(new org.springframework.mock.web.MockMultipartFile(field, "vaga.pdf", "application/pdf", "pdf".getBytes()))
                        .param("text", text))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("O preenchimento automático de vagas aceita apenas texto. Cole a descrição da vaga."));
            }
        }
        verifyNoInteractions(service);
    }
    @Test void rejectsMissingOrBlankVacancyTextWithoutPdfInstructions() throws Exception {
        actor(Role.GERENTE);
        mvc.perform(multipart("/api/careermatcher/vagas/ai-extraction"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Cole a descrição da vaga para preencher os campos automaticamente."));
        for (String text : List.of("", "  ", "\n\t"))
            mvc.perform(multipart("/api/careermatcher/vagas/ai-extraction").param("text", text))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Cole a descrição da vaga para preencher os campos automaticamente."));
        verifyNoInteractions(service);
    }
    @Test void missingFileIsAnActionableBadRequest() throws Exception {
        actor(Role.CANDIDATO);
        mvc.perform(multipart("/api/me/candidate/ai-extraction")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(service);
    }
}
