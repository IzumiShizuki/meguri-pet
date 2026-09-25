package com.meguri.core.persona;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.persona.presentation.PresentationResolver;
import com.meguri.core.persona.profile.PersonaProfileRepository;
import com.meguri.core.persona.profile.InMemoryUserProfileRepository;
import com.meguri.core.persona.profile.UserProfileRepository;
import com.meguri.core.persona.prompt.PromptPolicyComposer;
import com.meguri.core.persona.relationship.RelationshipStateRepository;
import com.meguri.core.persona.relationship.RelationshipTransitionService;
import com.meguri.core.persona.runtime.InteractionStateRepository;
import com.meguri.core.persona.runtime.PersonaStateReducer;
import com.meguri.core.persona.runtime.RuntimeOverrideRepository;
import com.meguri.core.persona.scene.SceneStateRepository;
import com.meguri.core.persona.scene.SceneTransitionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersonaRuntimeProperties.class)
public class PersonaRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean
    Clock personaRuntimeClock() {
        return Clock.systemUTC();
    }

    @Bean
    RelationshipTransitionService relationshipTransitionService(RelationshipStateRepository repository,
                                                                Clock clock) {
        return new RelationshipTransitionService(repository, clock);
    }

    @Bean
    SceneTransitionService sceneTransitionService(SceneStateRepository repository, Clock clock,
                                                  PersonaRuntimeProperties properties) {
        return new SceneTransitionService(repository, clock, properties.getSceneTtl(),
                properties.getSceneCooldown());
    }

    @Bean
    PersonaStateReducer personaStateReducer() {
        return new PersonaStateReducer();
    }

    @Bean
    PersonaRuntimeFacade personaRuntimeFacade(PersonaProfileRepository profiles,
                                              UserProfileRepository userProfiles,
                                              RelationshipTransitionService relationships,
                                              SceneTransitionService scenes,
                                              RuntimeOverrideRepository overrides,
                                              InteractionStateRepository interactions,
                                              PersonaStateReducer reducer,
                                              Clock clock,
                                              PersonaRuntimeProperties properties) {
        return new PersonaRuntimeFacade(profiles, userProfiles, relationships, scenes, overrides, interactions, reducer,
                clock, properties.getPolicyRevision(), properties.getDefaultTemporalMode());
    }

    @Bean
    PromptPolicyComposer promptPolicyComposer() {
        return new PromptPolicyComposer();
    }

    @Bean
    PresentationResolver presentationResolver() {
        return new PresentationResolver();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "meguri.persona.mode", havingValue = "in-memory", matchIfMissing = true)
    static class InMemoryPersistenceConfiguration {
        @Bean
        PersonaProfileRepository personaProfileRepository() {
            return PersonaRuntimeDefaults.profiles();
        }

        @Bean
        UserProfileRepository userProfileRepository() {
            return new InMemoryUserProfileRepository();
        }

        @Bean
        RelationshipStateRepository relationshipStateRepository() {
            return PersonaRuntimeDefaults.relationships();
        }

        @Bean
        SceneStateRepository sceneStateRepository() {
            return PersonaRuntimeDefaults.scenes();
        }

        @Bean
        InteractionStateRepository interactionStateRepository() {
            return PersonaRuntimeDefaults.interactions();
        }

        @Bean
        RuntimeOverrideRepository runtimeOverrideRepository() {
            return PersonaRuntimeDefaults.overrides();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "meguri.persona.mode", havingValue = "postgres")
    static class PostgresPersistenceConfiguration {
        @Bean
        PostgresPersonaRuntimeRepository postgresPersonaRuntimeRepository(JdbcTemplate jdbcTemplate,
                                                                          ObjectMapper objectMapper,
                                                                          TransactionTemplate transactions) {
            return new PostgresPersonaRuntimeRepository(jdbcTemplate, objectMapper, transactions);
        }
    }
}
