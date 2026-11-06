package br.com.careermatcher.relocation;

import br.com.careermatcher.security.CurrentActor;
import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.repositories.GraphCrudRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;

@Service
@Transactional
public class RelocationService {
    private final Neo4jClient client;
    private final CurrentActor actor;
    private final Clock clock;
    private final br.com.careermatcher.listings.WorkspaceListings listings;
    public RelocationService(Neo4jClient client, CurrentActor actor, Clock clock, br.com.careermatcher.listings.WorkspaceListings listings) {
        this.client=client; this.actor=actor; this.clock=clock; this.listings=listings;
    }
    public record Link(String token, Instant expiresAt) {}
    public record Preview(String companyName) {}
    public Link generate() {
        long company=actor.requireCompany();
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires=clock.instant().plus(Duration.ofHours(24));
        client.query("MATCH (c:COMPANY) WHERE id(c)=$company CREATE (v:EMPLOYEE_VERIFICATION {hash:$hash,expiresAt:$expires})-[:FOR_COMPANY]->(c)")
                .bindAll(Map.of("company",company,"hash",hash(token),"expires",expires.toEpochMilli())).run();
        return new Link(token,expires);
    }
    private static String hash(String token) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,"URL inválida ou expirada.");
    }
    @Transactional(readOnly=true)
    public Preview preview(String token) {
        actor.requireCandidate();
        return client.query("MATCH (v:EMPLOYEE_VERIFICATION {hash:$hash})-[:FOR_COMPANY]->(c:COMPANY) WHERE v.expiresAt>$now RETURN c.name AS name")
                .bindAll(Map.of("hash",hash(token),"now",clock.millis())).fetchAs(String.class)
                .mappedBy((t,r)->r.get("name").asString()).one().map(Preview::new).orElseThrow(RelocationService::invalid);
    }
    public void confirm(String token, long experienceId) {
        long candidate=actor.requireCandidate();
        // Same lock order as profile updates; prevents confirmation racing with resume replacement.
        client.query("MATCH (c:CANDIDATE) WHERE id(c)=$id SET c.__crudLock=true REMOVE c.__crudLock")
                .bind(candidate).to("id").run();
        var verification=client.query("MATCH (v:EMPLOYEE_VERIFICATION {hash:$hash}) RETURN v.expiresAt AS expires")
                .bind(hash(token)).to("hash").fetch().one().orElseThrow(RelocationService::invalid);
        if (((Number)verification.get("expires")).longValue()<=clock.millis()) throw invalid();
        var result=client.query("""
            MATCH (v:EMPLOYEE_VERIFICATION {hash:$hash})-[:FOR_COMPANY]->(company:COMPANY)
            MATCH (c:CANDIDATE)-[:HAS]->(:RESUME)-[:HasExperience]->(e:EXPERIENCE)
            WHERE id(c)=$candidate AND id(e)=$experience AND e.currentJob=true AND v.expiresAt>$now
              AND (coalesce(e.confirmed,false)=false OR e.confirmedCompanyId=id(company))
            SET e.confirmed=true,e.confirmedCompanyId=id(company)
            MERGE (v)-[verification:VERIFIED_CANDIDATE]->(c)
            ON CREATE SET verification.confirmedAt=$now
            MERGE (c)-[:`INTERESTED IN`]->(company)
            RETURN id(e) AS id, id(company) AS companyId
            """).bindAll(Map.of("hash",hash(token),"candidate",candidate,"experience",experienceId,"now",clock.millis())).fetch().one();
        if (result.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecione uma experiência atual do seu currículo ainda não confirmada em outra empresa.");
        listings.invalidateAfterCommit(((Number)result.get().get("companyId")).longValue(),
                br.com.careermatcher.listings.WorkspaceListings.Listing.TALENT_POOL,
                br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL);
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PageView<CandidateSummary> list(int page,int size,String search) {
        return list(page,size,search,null,"asc");
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public PageView<CandidateSummary> list(int page,int size,String search,String sort,String direction) {
        return listings.candidates(br.com.careermatcher.listings.WorkspaceListings.Listing.INTERNAL,page,size,search,sort,direction);
    }
}
