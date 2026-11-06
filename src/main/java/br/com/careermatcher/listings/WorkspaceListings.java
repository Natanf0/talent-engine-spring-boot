package br.com.careermatcher.listings;

import br.com.careermatcher.DTOs.crud.CrudDtos.*;
import br.com.careermatcher.crud.GraphSchema.Kind;
import br.com.careermatcher.enums.*;
import br.com.careermatcher.repositories.GraphCrudRepository;
import br.com.careermatcher.security.CurrentActor;
import com.github.benmanes.caffeine.cache.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.time.Duration;
import java.util.*;
import java.util.function.*;

/** Bounded summary windows shared by company, never by user or by search term. */
@Component
public class WorkspaceListings {
    public enum Listing { TALENT_POOL, INTERNAL, VACANCIES }
    private record Row(long id, Map<String,String> fields) {
        Row { fields=Map.copyOf(fields); }
        String value(String field) { return fields.getOrDefault(field, ""); }
        String optional(String field) { return fields.get(field); }
    }
    private record Snapshot(long total, List<Row> rows) {
        Snapshot { rows=List.copyOf(rows); }
    }
    private final Neo4jClient client;
    private final CurrentActor actor;
    private final TransactionTemplate reads;
    private final int maxRecords;
    private final Map<Listing,Cache<Long,Snapshot>> caches=new EnumMap<>(Listing.class);

    @Autowired
    public WorkspaceListings(Neo4jClient client, CurrentActor actor, PlatformTransactionManager transactions,
            @Value("${workspace.listings.candidates-ttl:10m}") String peopleTtl,
            @Value("${workspace.listings.vacancies-ttl:2m}") String vacanciesTtl,
            @Value("${workspace.listings.max-records:300}") int maxRecords,
            @Value("${workspace.listings.max-workspaces:64}") int maxWorkspaces) {
        this(client,actor,transactions,DurationStyle.detectAndParse(peopleTtl),DurationStyle.detectAndParse(vacanciesTtl),maxRecords,maxWorkspaces,Ticker.systemTicker());
    }
    public WorkspaceListings(Neo4jClient client, CurrentActor actor, PlatformTransactionManager transactions,
            Duration peopleTtl, Duration vacanciesTtl, int maxRecords, int maxWorkspaces, Ticker ticker) {
        if (peopleTtl.isZero() || peopleTtl.isNegative() || vacanciesTtl.isZero() || vacanciesTtl.isNegative()
                || maxRecords<1 || maxWorkspaces<1) throw new IllegalArgumentException("Positive listing cache limits required");
        this.client=client; this.actor=actor; this.maxRecords=maxRecords;
        reads=new TransactionTemplate(transactions); reads.setReadOnly(true);
        for (Listing type:Listing.values()) caches.put(type,Caffeine.newBuilder().maximumSize(maxWorkspaces)
                .expireAfterWrite(type==Listing.VACANCIES?vacanciesTtl:peopleTtl).ticker(ticker).build());
    }
    public PageView<CandidateSummary> candidates(Listing type,int page,int size,String search,String sort,String direction) {
        if (type==Listing.VACANCIES) throw new IllegalArgumentException("Not a people listing");
        return page(type,page,size,search,sort,direction,r->new CandidateSummary(r.id(),r.optional("name"),r.optional("professionalTitle"),r.optional("phone")));
    }
    public PageView<VacancySummary> vacancies(int page,int size,String search,String sort,String direction) {
        return page(Listing.VACANCIES,page,size,search,sort,direction,r->new VacancySummary(r.id(),r.optional("role"),r.optional("city"),
                r.optional("seniority")==null?null:SENIORITY.valueOf(r.value("seniority")),
                r.optional("modalidade")==null?null:Modalidade.valueOf(r.value("modalidade")),r.optional("companyName")));
    }
    private <T> PageView<T> page(Listing type,int page,int size,String search,String sort,String direction,Function<Row,T> mapper) {
        // Reauthorize before every hit: a cached value must never grant workspace access.
        long company=actor.requireCompany();
        GraphCrudRepository.validatePage(page,size);
        GraphCrudRepository.validateSort(kind(type),sort,direction);
        String query=search==null?"":search.strip().toLowerCase(Locale.ROOT);
        long offset=(long)page*size;
        if (TransactionSynchronizationManager.isActualTransactionActive())
            return direct(type,company,page,size,query,sort,direction,mapper);
        Snapshot snapshot=caches.get(type).get(company,ignored->read(()->new Snapshot(count(type,company,""),rows(type,company,0,maxRecords,"",null,"asc"))));
        if (snapshot.total()==snapshot.rows().size()) {
            var result=snapshot.rows().stream().filter(row->matches(type,row,query));
            Comparator<Row> order=Comparator.comparingLong(Row::id);
            if (sort!=null) {
                Comparator<Row> fieldOrder=Comparator.comparing(row->display(type,sort,row.value(sort)).toLowerCase(Locale.ROOT));
                order=(direction.equals("desc")?fieldOrder.reversed():fieldOrder).thenComparingLong(Row::id);
            }
            List<Row> filtered=result.sorted(order).toList();
            return view(filtered.stream().skip(offset).limit(size).toList(),filtered.size(),page,size,mapper);
        }
        if (query.isEmpty() && sort==null && offset+size<=snapshot.rows().size())
            return view(snapshot.rows().subList((int)offset,(int)offset+size),snapshot.total(),page,size,mapper);
        // No query/page caches: deep pages and filters over larger catalogs remain complete without retaining more rows.
        return direct(type,company,page,size,query,sort,direction,mapper);
    }
    private <T> PageView<T> direct(Listing type,long company,int page,int size,String search,String sort,String direction,Function<Row,T> mapper) {
        return read(()->view(rows(type,company,(long)page*size,size,search,sort,direction),count(type,company,search),page,size,mapper));
    }
    private static <T> PageView<T> view(List<Row> rows,long total,int page,int size,Function<Row,T> mapper) {
        return new PageView<>(rows.stream().map(mapper).toList(),page,size,total,(total+size-1)/size);
    }
    private <T> T read(Supplier<T> operation) { return reads.execute(status->operation.get()); }
    private static Kind kind(Listing type) { return type==Listing.VACANCIES?Kind.VACANCY:Kind.CANDIDATE; }
    private static List<String> fields(Listing type) {
        return type==Listing.VACANCIES?List.of("role","city","seniority","modalidade"):List.of("name","professionalTitle","phone");
    }
    private static String display(Listing type,String field,String value) {
        return GraphCrudRepository.displayLabels(kind(type)).getOrDefault(field,Map.of()).getOrDefault(value,value);
    }
    private static boolean matches(Listing type,Row row,String query) {
        return query.isEmpty() || fields(type).stream().anyMatch(field->row.value(field).toLowerCase(Locale.ROOT).contains(query)
                || display(type,field,row.value(field)).toLowerCase(Locale.ROOT).contains(query));
    }
    private static String scope(Listing type) {
        String match=switch(type) {
            case TALENT_POOL -> "MATCH (n:CANDIDATE)-[:`INTERESTED IN`]->(company:COMPANY) WHERE id(company)=$company AND "+TalentPoolScope.notInternal("n","$company");
            case VACANCIES -> "MATCH (company:COMPANY)-[:PUBLISHES]->(n:VACANCY) WHERE id(company)=$company";
            case INTERNAL -> "MATCH (n:CANDIDATE)-[:HAS]->(:RESUME)-[:HasExperience]->(e:EXPERIENCE) WHERE e.currentJob=true AND e.confirmed=true AND e.confirmedCompanyId=$company";
        };
        return match+" AND any(field IN $fields WHERE toLower(coalesce(toString(n[field]),'')) CONTAINS $search OR toLower("+text("field")+") CONTAINS $search) ";
    }
    private static String text(String field) { return "coalesce($labels["+field+"][toString(n["+field+"])],toString(n["+field+"]), '')"; }
    private static Map<String,Object> parameters(Listing type,long company,String search) {
        var parameters=new HashMap<String,Object>(Map.of("company",company,"search",search,"fields",fields(type),"labels",GraphCrudRepository.displayLabels(kind(type))));
        if (type==Listing.TALENT_POOL) parameters.putAll(TalentPoolScope.PARAMETERS);
        return parameters;
    }
    private long count(Listing type,long company,String search) {
        return client.query(scope(type)+"RETURN count(DISTINCT n) AS total").bindAll(parameters(type,company,search))
                .fetchAs(Long.class).one().orElse(0L);
    }
    private List<Row> rows(Listing type,long company,long offset,int limit,String search,String sort,String direction) {
        var params=new HashMap<>(parameters(type,company,search));
        params.put("skip",offset); params.put("limit",limit);
        String order="id(n) ASC";
        if (sort!=null) { params.put("sort",sort); order="toLower("+text("$sort")+") "+(direction.equals("desc")?"DESC":"ASC")+",id(n) ASC"; }
        String companyName=type==Listing.VACANCIES?",head([(publisher:COMPANY)-[:PUBLISHES]->(n) WHERE id(publisher)=$company | publisher.name]) AS companyName":"";
        return client.query(scope(type)+"WITH DISTINCT n ORDER BY "+order+" SKIP $skip LIMIT $limit RETURN id(n) AS id,[field IN $fields | n[field]] AS values"+companyName)
                .bindAll(params).fetch().all().stream().map(raw->{
                    var values=(List<?>)raw.get("values"); var map=new HashMap<String,String>(); var fields=fields(type);
                    for(int i=0;i<fields.size();i++) if(values.get(i)!=null) map.put(fields.get(i),values.get(i).toString());
                    if(raw.get("companyName")!=null) map.put("companyName",raw.get("companyName").toString());
                    return new Row(((Number)raw.get("id")).longValue(),map);
                }).toList();
    }
    public void invalidateCurrentAfterCommit(Listing... types) { invalidateAfterCommit(actor.requireCompany(),types); }
    public void invalidateAfterCommit(long company,Listing... types) {
        Runnable invalidate=()->{ for(Listing type:types)caches.get(type).invalidate(company); };
        if (TransactionSynchronizationManager.isSynchronizationActive()) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void afterCommit(){invalidate.run();}
        }); else invalidate.run();
    }
    public void clearCaches() { caches.values().forEach(Cache::invalidateAll); }
}
