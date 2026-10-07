package br.com.careermatcher.security;

import br.com.careermatcher.DTOs.crud.CrudDtos.PageView;
import br.com.careermatcher.enums.Role;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Repository
public class UserRepository {
    private final Neo4jClient client;
    public UserRepository(Neo4jClient client) { this.client = client; }
    private static final String PROJECTION = " RETURN properties(u) AS data, "
            + "[(u)-[:WORKS_FOR]->(c:COMPANY) | {id:id(c),name:c.name}] AS companies, "
            + "[(u)-[:IS]->(c:CANDIDATE) | id(c)] AS candidates";

    public record Account(AuthUser user, String password, String invitationHash,
                          Instant invitationExpiresAt, boolean validLinks) {}

    public void initialize() {
        client.query("CREATE CONSTRAINT user_id_unique IF NOT EXISTS FOR (u:USER) REQUIRE u.id IS UNIQUE").run();
        client.query("CREATE CONSTRAINT user_email_unique IF NOT EXISTS FOR (u:USER) REQUIRE u.email IS UNIQUE").run();
        client.query("CREATE CONSTRAINT user_invitation_unique IF NOT EXISTS FOR (u:USER) REQUIRE u.invitationHash IS UNIQUE").run();
    }
    public Optional<Account> findByEmail(String email) {
        return client.query("MATCH (u:USER) WHERE u.email=$email" + PROJECTION).bind(email).to("email").fetch().one().map(this::account);
    }
    public Optional<Account> findById(String id) {
        return client.query("MATCH (u:USER) WHERE u.id=$id" + PROJECTION).bind(id).to("id").fetch().one().map(this::account);
    }
    public Optional<Account> findByInvitation(String hash) {
        return client.query("MATCH (u:USER) WHERE u.invitationHash=$hash" + PROJECTION).bind(hash).to("hash").fetch().one().map(this::account);
    }
    public void lock(String id) {
        if (client.query("MATCH (u:USER) WHERE u.id=$id SET u.__authLock=true REMOVE u.__authLock RETURN u.id AS id")
                .bind(id).to("id").fetch().one().isEmpty()) throw missing();
    }
    public AuthUser createCandidate(String email, String name, String password, long candidateId, Instant now) {
        String id = UUID.randomUUID().toString();
        if (client.query("MATCH (c:CANDIDATE) WHERE id(c)=$candidateId "
                + "CREATE (u:USER {id:$id,email:$email,name:$name,password:$password,role:'CANDIDATO',cadastroFinalizado:true,createdAt:$createdAt})-[:IS]->(c) RETURN u.id AS id")
                .bindAll(Map.of("candidateId",candidateId,"id",id,"email",email,"name",name,"password",password,"createdAt",now.toString()))
                .fetch().one().isEmpty()) throw missing();
        return findById(id).orElseThrow().user();
    }
    public AuthUser createInvitation(String email, Role role, long companyId) {
        String id = UUID.randomUUID().toString();
        if (client.query("MATCH (c:COMPANY) WHERE id(c)=$companyId "
                + "CREATE (u:USER {id:$id,email:$email,role:$role,cadastroFinalizado:false})-[:WORKS_FOR]->(c) RETURN u.id AS id")
                .bindAll(Map.of("companyId",companyId,"id",id,"email",email,"role",role.name())).fetch().one().isEmpty()) throw missing();
        return findById(id).orElseThrow().user();
    }
    public void setInvitation(String id, String hash, Instant expiresAt) {
        client.query("MATCH (u:USER) WHERE u.id=$id SET u.invitationHash=$hash,u.invitationExpiresAt=$expiresAt")
                .bindAll(Map.of("id",id,"hash",hash,"expiresAt",expiresAt.toString())).run();
    }
    public void complete(String id, String expectedHash, String name, String password, Instant now) {
        if (client.query("MATCH (u:USER) WHERE u.id=$id AND u.invitationHash=$hash AND u.cadastroFinalizado=false "
                + "SET u.name=$name,u.password=$password,u.createdAt=$now,u.cadastroFinalizado=true "
                + "REMOVE u.invitationHash,u.invitationExpiresAt RETURN u.id AS id")
                .bindAll(Map.of("id",id,"hash",expectedHash,"name",name,"password",password,"now",now.toString())).fetch().one().isEmpty())
            throw AuthValidation.invalidInvite();
    }
    public PageView<AuthUser> collaborators(long companyId, int page, int size) {
        String match = "MATCH (u:USER)-[:WORKS_FOR]->(c:COMPANY) WHERE id(c)=$companyId AND u.role IN ['GERENTE','RECRUTADOR']";
        long total = client.query(match + " RETURN count(DISTINCT u) AS total").bind(companyId).to("companyId")
                .fetchAs(Long.class).mappedBy((types,r)->r.get("total").asLong()).one().orElse(0L);
        var content = client.query(match + " WITH DISTINCT u ORDER BY u.email,u.id SKIP $skip LIMIT $size" + PROJECTION)
                .bindAll(Map.of("companyId",companyId,"skip",(long)page*size,"size",size)).fetch().all().stream()
                .map(this::account).map(Account::user).toList();
        return new PageView<>(content,page,size,total,(total+size-1)/size);
    }
    public void deleteCollaborator(long companyId, String id) {
        if (client.query("MATCH (u:USER)-[:WORKS_FOR]->(c:COMPANY) WHERE id(c)=$companyId AND u.id=$id "
                + "AND u.role IN ['GERENTE','RECRUTADOR'] WITH DISTINCT u DETACH DELETE u RETURN count(*) AS deleted")
                .bindAll(Map.of("companyId",companyId,"id",id)).fetchAs(Long.class)
                .mappedBy((types,r)->r.get("deleted").asLong()).one().orElse(0L)==0) throw missing();
    }
    @SuppressWarnings("unchecked")
    private Account account(Map<String,Object> row) {
        Map<String,Object> data = (Map<String,Object>)row.get("data");
        List<Map<String,Object>> companies = (List<Map<String,Object>>)row.get("companies");
        List<Number> candidates = (List<Number>)row.get("candidates");
        Role role = null;
        try { role = Role.valueOf(Objects.toString(data.get("role"),"")); } catch (IllegalArgumentException ignored) {}
        boolean links = role != null && (role.isStaff() ? companies.size()==1 && candidates.isEmpty() : candidates.size()==1 && companies.isEmpty());
        AuthUser user = new AuthUser((String)data.get("id"),(String)data.get("name"),(String)data.get("email"),role,
                companies.size()==1?((Number)companies.getFirst().get("id")).longValue():null,
                candidates.size()==1?candidates.getFirst().longValue():null,Boolean.TRUE.equals(data.get("cadastroFinalizado")),
                companies.size()==1?(String)companies.getFirst().get("name"):null);
        Object expires = data.get("invitationExpiresAt");
        Instant expiry = expires == null ? null : expires instanceof java.time.ZonedDateTime time ? time.toInstant() : Instant.parse(expires.toString());
        return new Account(user,(String)data.get("password"),(String)data.get("invitationHash"),expiry,links);
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Registro não encontrado."); }
}
