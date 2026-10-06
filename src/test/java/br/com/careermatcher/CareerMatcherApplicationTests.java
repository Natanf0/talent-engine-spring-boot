package br.com.careermatcher;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.*;
import org.neo4j.harness.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.neo4j.uri=bolt://localhost:1",
        "spring.neo4j.authentication.username=",
        "spring.neo4j.authentication.password=",
        "spring.mail.username=",
        "spring.mail.password="
})
@Import(CareerMatcherApplicationTests.LocalDatabase.class)
@DirtiesContext
class CareerMatcherApplicationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.security.oauth2.jwt.JwtDecoder jwtDecoder;
    @Autowired private RabbitListenerEndpointRegistry listeners;

    @Test void contextLoadsWithQueueListenersPaused() {
        assertThat(listeners.getListenerContainers()).isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalDatabase {
        @Bean(destroyMethod = "close") Neo4j testDatabase() {
            return Neo4jBuilders.newInProcessBuilder().withDisabledServer()
                    .withConfig(org.neo4j.configuration.GraphDatabaseSettings.pagecache_memory, 32L * 1024 * 1024).build();
        }
        @Bean(destroyMethod = "close") Driver driver(Neo4j database) {
            return GraphDatabase.driver(database.boltURI(), AuthTokens.none());
        }
    }
}
