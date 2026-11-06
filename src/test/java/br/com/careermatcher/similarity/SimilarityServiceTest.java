package br.com.careermatcher.similarity;

import br.com.careermatcher.DTOs.matching.CandidateDTO;
import br.com.careermatcher.enums.Role;
import br.com.careermatcher.security.*;
import br.com.careermatcher.validations.ValidationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static br.com.careermatcher.similarity.SimilaritySnapshot.*;

class SimilarityServiceTest {
    final CurrentActor actor = new CurrentActor();
    final SimilaritySnapshotRepository repository = mock(SimilaritySnapshotRepository.class);
    final CandidateSimilarityService service = new CandidateSimilarityService(actor,repository,new SimilaritySettings());
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    void login(Role role) {
        var user = new AuthUser("user","u@test.com","",role,10L,role==Role.CANDIDATO?1L:null,true,"Company");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"",List.of()));
    }
    CandidateDTO dto(String id) { return new CandidateDTO(id,"FORGED",null,null,null,null); }
    @ParameterizedTest @EnumSource(value=Role.class,names={"ADMINISTRADOR","RECRUTADOR","GERENTE"})
    void staffUsesOnlyPersistedProfile(Role role) {
        login(role); when(repository.load()).thenReturn(ForaTest.fixture());
        var result=service.findSimilar(dto("1"),null);
        assertThat(result.requestedK()).isEqualTo(10);
        assertThat(result.candidates()).allSatisfy(c -> assertThat(c.name()).isNotEqualTo("FORGED"));
        assertThat(result.sourceSkills()).hasSize(3);
        assertThat(result.candidates()).anySatisfy(c -> assertThat(c.indirectAffinity()).isTrue());
        verify(repository).load();
    }
    @Test void requiresAuthenticatedStaffAndIgnoresPayloadIdentityForAccess() {
        assertThatThrownBy(()->service.findSimilar(dto("1"),1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(401));
        login(Role.CANDIDATO);
        assertThatThrownBy(()->service.findSimilar(dto("1"),1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(403));
        verifyNoInteractions(repository);
    }
    @Test void validatesOnlySelectionAndK() {
        login(Role.GERENTE);
        for(String id:Arrays.asList(null,"","-1","x","9223372036854775808"))
            assertThatThrownBy(()->service.findSimilar(dto(id),1)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(()->service.findSimilar(null,1)).isInstanceOf(ValidationException.class);
        for(int k:new int[]{0,-1,101}) assertThatThrownBy(()->service.findSimilar(dto("1"),k)).isInstanceOf(ValidationException.class);
        verifyNoInteractions(repository);
    }
    @Test void acceptsExistingCandidateDtoAtRootAndReturnsControlledBudgetFailure() throws Exception {
        login(Role.RECRUTADOR);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var selected=mapper.readValue("{\"id\":\"1\"}",CandidateDTO.class);
        when(repository.load()).thenReturn(ForaTest.fixture());
        assertThat(service.findSimilar(selected,1).referenceCandidateId()).isEqualTo("1");
        var limited=new SimilaritySettings(); limited.setMaxWalks(1);
        var bounded=new CandidateSimilarityService(actor,repository,limited);
        assertThatThrownBy(()->bounded.findSimilar(selected,1)).isInstanceOfSatisfying(ResponseStatusException.class,
                e->assertThat(e.getStatusCode().value()).isEqualTo(503));
        String json=mapper.writeValueAsString(service.findSimilar(selected,1));
        assertThat(json).doesNotContain("password", "phone", "email");
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/similarity-example.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(service.findSimilar(selected,1)));
    }
    @Test void absentOrInvisibleReferenceIs404AndEmptyReasonsAreDistinct() {
        login(Role.ADMINISTRADOR); when(repository.load()).thenReturn(ForaTest.fixture());
        assertThatThrownBy(()->service.findSimilar(dto("999"),1)).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
        var noSkills=service.findSimilar(dto("6"),1);
        assertThat(noSkills.reason()).isEqualTo(SimilarityResponse.Reason.NO_SKILLS);
        assertThat(noSkills.qualityStatus()).isEqualTo(Fora.QualityStatus.NOT_APPLICABLE);
        var noOthers=service.findSimilar(dto("5"),1);
        assertThat(noOthers.reason()).isEqualTo(SimilarityResponse.Reason.NO_SIMILAR_CANDIDATES);
    }
}
