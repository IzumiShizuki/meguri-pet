package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.MeguriCoreApplication;
import com.meguri.core.memory.job.JdbcPostReplyMemoryJobStore;
import com.meguri.core.memory.job.PostReplyMemoryJobStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** A full, offline Core in its own JVM; only the parent's unique test schema is accessible. */
public final class PostgresCoreProcessProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].matches("meguri_p0_core_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("isolated Core schema required");
        }
        Path fixture = Path.of(args[1]).toAbsolutePath();
        var uri = URI.create(Files.readString(Path.of(System.getenv("MEGURI_RECOVERY_TEST_DSN_FILE"))).trim()
                .replace("postgresql+asyncpg://", "postgresql://"));
        String[] auth = uri.getRawUserInfo().split(":", 2);
        String host = uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost();
        String jdbcUrl = "jdbc:postgresql://" + host + ":" + (uri.getPort() < 0 ? 5432 : uri.getPort())
                + uri.getRawPath() + "?currentSchema=" + args[0];
        var properties = new LinkedHashMap<String, Object>();
        properties.put("server.port", 0);
        properties.put("server.address", "127.0.0.1");
        properties.put("spring.datasource.url", jdbcUrl);
        properties.put("spring.datasource.username", URLDecoder.decode(auth[0], StandardCharsets.UTF_8));
        properties.put("spring.datasource.password", URLDecoder.decode(auth[1], StandardCharsets.UTF_8));
        properties.put("spring.datasource.hikari.maximum-pool-size", 4);
        properties.put("spring.datasource.hikari.minimum-idle", 1);
        // Resolve the installed canonical build, as the normal launcher does.
        properties.put("meguri.build-id", "");
        properties.put("meguri.turn-journal.mode", "postgres");
        properties.put("meguri.llm.provider", "mock");
        properties.put("meguri.memory.desktop-token-file", fixture.resolve("token.txt").toString());
        properties.put("meguri.memory.desktop-user-id", "core-fixture-user");
        properties.put("meguri.memory.bridge-token", "");
        properties.put("meguri.memory.bridge-token-file", "");
        properties.put("meguri.security.auth-required", true);
        properties.put("meguri.security.shared-token-file", fixture.resolve("token.txt").toString());
        properties.put("meguri.security.tenant-id", "meguri-local");
        properties.put("meguri.training-feedback.file", fixture.resolve("feedback.jsonl").toString());
        properties.put("meguri.training-feedback.training-mode-probability", 1);
        properties.put("meguri.training-feedback.normal-mode-probability", 0);
        properties.put("meguri.background-workers.memory.store-mode", "postgres");
        properties.put("meguri.background-workers.memory.enabled", Boolean.parseBoolean(args[2]));
        properties.put("meguri.background-workers.memory.poll-interval", "100ms");
        properties.put("meguri.background-workers.outbox.poll-interval", "100ms");
        properties.put("meguri.background-workers.context.enabled", false);
        for (String feature : new String[]{"weather", "web-search", "ai-news", "sleep-memory",
                "knowledge.notion", "bilibili-report", "resources.everything", "shizuki"}) {
            properties.put("meguri." + feature + ".enabled", false);
        }
        properties.put("meguri.prompt-cache-metrics.file", fixture.resolve("prompt-cache.json").toString());
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "WARN");
        var environment = new StandardEnvironment();
        environment.setActiveProfiles("postgres");
        environment.getPropertySources().addFirst(new MapPropertySource("isolated-recovery-fixture", properties));
        var application = new SpringApplication(MeguriCoreApplication.class);
        application.setEnvironment(environment);
        try (var context = application.run()) {
            var jdbc = context.getBean(JdbcTemplate.class);
            require(args[0].equals(jdbc.queryForObject("SELECT current_schema()", String.class)), "isolated search path");
            require(context.getBean(TurnJournal.class) instanceof PostgresTurnJournal, "durable Turn journal");
            require(context.getBean(SessionContextPersistence.class) instanceof PostgresSessionContextPersistence,
                    "durable context");
            require(context.getBean(com.meguri.core.context.ContextRuntimePersistence.class)
                    == context.getBean(SessionContextPersistence.class), "unambiguous shared durable context");
            require(context.getBean(PostReplyMemoryJobStore.class) instanceof JdbcPostReplyMemoryJobStore,
                    "durable background queue");
            int recent = context.getBean(SessionContextStore.class)
                    .recent("core-fixture-user", "desktop_pet", "core-fixture-session").size();
            var state = Map.of("port", ((ReactiveWebServerApplicationContext) context).getWebServer().getPort(),
                    "pid", ProcessHandle.current().pid(), "restored_context_messages", recent);
            new ObjectMapper().writeValue(fixture.resolve("ready.tmp").toFile(), state);
            Files.move(fixture.resolve("ready.tmp"), fixture.resolve("ready.json"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            // The parent terminates this process to exercise an actual JVM exit/restart.
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException(check);
    }
}
