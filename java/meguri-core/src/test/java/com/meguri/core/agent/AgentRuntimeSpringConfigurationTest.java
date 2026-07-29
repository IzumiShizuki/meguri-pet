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
            assertThat(context).hasSingleBean(AgentDurableRecoveryLifecycle.class);
            assertThat(context).hasSingleBean(AgentExecutionStores.RuntimeStore.class);
            assertThat(context).hasSingleBean(RemoteAgentGateway.class);
            AgentRuntimeAssembly assembly = context.getBean(AgentRuntimeAssembly.class);
            assertThat(context.getBean(AgentDurableRecoveryLifecycle.class).isRunning()).isTrue();
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

    @Test
    void httpModeBuildsRealGatewayWhileDefaultRemainsFailClosed() {
        contextRunner.withPropertyValues(
                        "meguri.agent.gateway-mode=http",
                        "meguri.agent.remote.endpoint=http://127.0.0.1:9876/a2a/",
                        "meguri.agent.remote.allow-insecure-localhost=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RemoteAgentGateway.class);
                    assertThat(context.getBean(RemoteAgentGateway.class))
                            .isInstanceOf(HttpRemoteAgentGateway.class);
                    assertThat(context).doesNotHaveBean(InMemoryRemoteAgentGateway.class);
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
