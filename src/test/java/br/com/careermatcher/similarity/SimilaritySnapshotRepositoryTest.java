package br.com.careermatcher.similarity;

import br.com.careermatcher.security.CurrentActor;
import org.junit.jupiter.api.*;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.springframework.data.neo4j.core.Neo4jClient;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimilaritySnapshotRepositoryTest {
    Neo4j db; Driver driver; Neo4jClient client;
    CurrentActor actor=mock(CurrentActor.class);
    SimilaritySnapshotRepository repository;
    @BeforeAll void start() {
        db=Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                .withConfig(org.neo4j.configuration.GraphDatabaseSettings.pagecache_memory,32L*1024*1024).build();
        driver=GraphDatabase.driver(db.boltURI(),AuthTokens.none());
        client=Neo4jClient.create(driver); repository=new SimilaritySnapshotRepository(client,actor);
    }
    @AfterAll void stop() { if (driver != null) driver.close(); if (db != null) db.close(); }
    @BeforeEach void reset() { client.query("MATCH (n) DETACH DELETE n").run(); }
    @Test void explicitPathsDeduplicateAndExcludeOtherProfilesAndSharedAcademicNodesWithoutWrites() {
        var ids=client.query("""
                CREATE (company:COMPANY), (otherCompany:COMPANY)
                CREATE (a:CANDIDATE {name:'A'})-[:HAS]->(r:RESUME)
                CREATE (b:CANDIDATE {name:'B'})-[:HAS]->(rb:RESUME)
                CREATE (hidden:CANDIDATE {name:'Hidden'})-[:HAS]->(rh:RESUME)
                CREATE (a)-[:`INTERESTED IN`]->(company), (b)-[:`INTERESTED IN`]->(company)
                CREATE (hidden)-[:`INTERESTED IN`]->(otherCompany)
                CREATE (java:SKILL {name:'Java'}), (expOnly:SKILL {name:'Experience only'}), (projectOnly:SKILL {name:'Project only'})
                CREATE (r)-[:CandidateHasSkill {weight:99}]->(java), (r)-[:CandidateHasSkill {weight:99}]->(java)
                CREATE (r)-[:HasExperience]->(e1:EXPERIENCE)-[:DEVELOPS_SKILL {weight:0}]->(java)
                CREATE (r)-[:HasExperience]->(e2:EXPERIENCE)-[:DEVELOPS_SKILL]->(java)
                CREATE (e2)-[:DEVELOPS_SKILL]->(expOnly)
                CREATE (r)-[:GRADUATED_IN]->(u:UNDERGRADUATE)-[:DEVELOPS_A]->(p1:PROJECT)-[:DEVELOPS_SKILL]->(java)
                CREATE (r)-[:POSTGRADUATED_IN]->(g:GRADUATE)-[:DEVELOPS_A]->(p2:PROJECT)-[:DEVELOPS_SKILL]->(java)
                CREATE (g)-[:DEVELOPS_A]->(p1), (p2)-[:DEVELOPS_SKILL]->(projectOnly)
                CREATE (rh)-[:POSTGRADUATED_IN]->(:GRADUATE)-[:DEVELOPS_A]->(:PROJECT)-[:DEVELOPS_SKILL]->(:SKILL {name:'Private'})
                CREATE (r)-[:POSTGRADUATED_IN]->(shared:GRADUATE)<-[:POSTGRADUATED_IN]-(rb)
                CREATE (shared)-[:DEVELOPS_A]->(:PROJECT)-[:DEVELOPS_SKILL]->(:SKILL {name:'Ambiguous'})
                RETURN id(company) AS company, id(otherCompany) AS otherCompany, id(a) AS a, id(java) AS java
                """).fetch().one().orElseThrow();
        when(actor.requireCompany()).thenReturn(((Number)ids.get("company")).longValue());
        long before=count(); var snapshot=repository.load(); long after=count();
        assertThat(before).isEqualTo(after);
        var graph=new SkillProjection(snapshot);
        long a=((Number)ids.get("a")).longValue(), java=((Number)ids.get("java")).longValue();
        assertThat(graph.frequency(a,java)).isEqualTo(5);
        assertThat(snapshot.profiles()).extracting(SimilaritySnapshot.Profile::name).containsExactly("A","B");
        assertThat(graph.skillNames.values()).containsExactlyInAnyOrder("Java","Experience only","Project only");
        assertThat(graph.evidence.get(new SkillProjection.Pair(a,java))).hasSize(5);
        when(actor.requireCompany()).thenReturn(((Number)ids.get("otherCompany")).longValue());
        assertThat(repository.load().profiles()).extracting(SimilaritySnapshot.Profile::name).containsExactly("Hidden");
        System.out.printf("Similarity extraction fixture: profiles=%d facts=%d (single batch)%n",snapshot.profiles().size(),snapshot.evidence().size());
    }
    @Test void measuresPopularSkillAndManyEvidenceProfilesSeparately() {
        long company=((Number)client.query("CREATE (company:COMPANY) RETURN id(company) AS id").fetch().one().orElseThrow().get("id")).longValue();
        client.query("""
                MATCH (company:COMPANY) WHERE id(company)=$company
                CREATE (java:SKILL {name:'Java'})
                WITH company,java UNWIND range(1,100) AS i
                CREATE (c:CANDIDATE {name:'Candidate '+i})-[:HAS]->(r:RESUME)
                CREATE (c)-[:`INTERESTED IN`]->(company)
                WITH r,java UNWIND range(1,50) AS j
                CREATE (r)-[:HasExperience]->(:EXPERIENCE)-[:DEVELOPS_SKILL]->(java)
                """).bind(company).to("company").run();
        when(actor.requireCompany()).thenReturn(company);
        long start=System.nanoTime(); var snapshot=repository.load(); long loaded=System.nanoTime();
        var graph=new SkillProjection(snapshot); long origin=snapshot.profiles().getFirst().id();
        var sigma=graph.sigma(origin);
        var result=new Fora().compute(graph,sigma,graph.targets(origin,sigma),10,ForaTest.parameters());
        long completed=System.nanoTime();
        assertThat(snapshot.evidence()).hasSize(5000);
        assertThat(graph.frequency(origin,graph.skillNames.keySet().iterator().next())).isEqualTo(50);
        assertThat(result.top()).hasSize(10);
        System.out.printf("Similarity synthetic: profiles=100 facts=5000 extractionMs=%.2f processingMs=%.2f walks=%d pushes=%d rounds=%d quality=%s%n",
                (loaded-start)/1e6,(completed-loaded)/1e6,result.walks(),result.pushes(),result.rounds(),result.qualityStatus());
    }
    long count() { return ((Number)client.query("MATCH ()-[r]->() RETURN count(r) AS count").fetch().one().orElseThrow().get("count")).longValue(); }
}
