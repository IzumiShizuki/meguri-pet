package com.meguri.core.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRuntimeSpringConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AgentRuntimeSpringConfiguration.class);

    @Test
    void missingRemoteGatewayFailsClosedInsteadOfUsingSyntheticResults() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgentRuntimeAssembly.class);
            assertThat(context).hasSingleBean(AgentRuntime.class);
            assertThat(context).hasSingleBean(AgentExecutionStores.RuntimeStore.class);
            assertThat(context).hasSingleBean(RemoteAgentGateway.class);
            AgentRuntimeAssembly assembly = context.getBean(AgentRuntimeAssembly.class);
            assertThat(assembly.usesInMemoryGateway()).isFalse();
            assertThat(context).doesNotHaveBean(InMemoryRemoteAgentGateway.class);
            assertThatThrownBy(() -> assembly.remoteGateway()
                    .poll("never-submitted")
                    .block())
                    .isInstanceOf(AgentUnavailableException.class)
                    .hasMessage("remote agent gateway is not configured");
        });
    }

    @Test
    void explicitInMemoryModeEnablesDeterministicDevelopmentGateway() {
        contextRunner.withPropertyValues("meguri.agent.gateway-mode=in-memory")
                .run(context -> {
                    AgentRuntimeAssembly assembly = context.getBean(AgentRuntimeAssembly.class);
                    assertThat(assembly.usesInMemoryGateway()).isTrue();
                    assertThat(assembly.remoteGateway())
                            .isSameAs(context.getBean(InMemoryRemoteAgentGateway.class));
                });
    }

    @Test
    void externallyProvidedGatewayReplacesFallback() {
        contextRunner.withBean(RemoteAgentGateway.class, StubGateway::new)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(InMemoryRemoteAgentGateway.class);
                    AgentRuntimeAssembly assembly = context.getBean(AgentRuntimeAssembly.class);
                    assertThat(assembly.usesInMemoryGateway()).isFalse();
                    assertThat(assembly.remoteGateway()).isInstanceOf(StubGateway.class);
                });
    }

    private static final class StubGateway implements RemoteAgentGateway {
        @Override
        public Mono<RemoteSubmission> submit(AgentTask task, InvokeAgentProposal proposal) {
            return Mono.just(new RemoteSubmission("external-1"));
        }

        @Override
        public Mono<RemoteAgentStatus> poll(String remoteTaskId) {
            return Mono.just(RemoteAgentStatus.WAITING_EXTERNAL);
        }

        @Override
        public Mono<Void> cancel(String remoteTaskId) {
            return Mono.empty();
        }

        @Override
        public Mono<AgentResult> result(String remoteTaskId) {
            return Mono.error(new IllegalStateException("no result"));
        }
    }
}
