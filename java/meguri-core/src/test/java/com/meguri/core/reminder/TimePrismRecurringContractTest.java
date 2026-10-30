package com.meguri.core.reminder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meguri.core.security.CoreIdentityVerifier;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.assertThat;

class TimePrismRecurringContractTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicInteger posts = new AtomicInteger(), gets = new AtomicInteger(), logins = new AtomicInteger();
    final AtomicBoolean failAfterCommit = new AtomicBoolean(), tamper = new AtomicBoolean();
    final List<JsonNode> rows = new ArrayList<>();
    HttpServer server;
    WebTestClient client;
    JsonNode fixtures;

    @BeforeEach void setup() throws Exception {
        fixtures=mapper.readTree(Path.of("../../contracts/timeprism/recurring-fixtures.json").toFile());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/auth/tokens", e -> {
            logins.incrementAndGet();
            byte[] bytes=mapper.writeValueAsBytes(Map.of("data",Map.of("result_type","TOKEN_ISSUED","access_token","fixture","user_id",42)));
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.createContext("/api/v1/light-apps/todo-recurring-rules", e -> {
            Object result;int status=200;
            if(e.getRequestMethod().equals("GET")){gets.incrementAndGet();result=rows;}
            else {
                posts.incrementAndGet();ObjectNode row=(ObjectNode)mapper.readTree(e.getRequestBody());row.put("rule_id",90+posts.get());
                if(tamper.get())row.put("time_zone_id","UTC");
                rows.add(row);result=row;
                if(failAfterCommit.getAndSet(false))status=500;
            }
            byte[] bytes=mapper.writeValueAsBytes(Map.of("data",result));
            e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.start();
        Files.writeString(temporary.resolve("email"),"fixture@example.invalid");
        Files.writeString(temporary.resolve("password"),"fixture-only");
        reconnect(java.time.Instant.parse("2029-12-30T20:00:00Z"));
    }
    void reconnect(java.time.Instant now) {
        var writer=new TimePrismTodoWriter(mapper,temporary.toString(),42,temporary.resolve("email").toString(),temporary.resolve("password").toString(),"http://127.0.0.1:"+server.getAddress().getPort(),2000,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        client=WebTestClient.bindToController(new TimePrismReminderController(new TimePrismReminderStore(mapper,temporary.toString()),writer,new CoreIdentityVerifier(false,"fixture-tenant","","fixture-shared"),42)).build();
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    ObjectNode request(int i){ObjectNode n=fixtures.get(i).path("schedule").deepCopy();n.put("requestId",UUID.randomUUID().toString());return n;}
    WebTestClient.ResponseSpec send(JsonNode body,String token,String owner){
        return client.post().uri("/v1/reminders/timeprism/recurring-rules").headers(h->{h.setBearerAuth(token);h.set("X-Meguri-Tenant-ID","fixture-tenant");h.set("X-Meguri-User-ID",owner);h.set("X-Meguri-Client-ID","astrbot");h.set("X-Meguri-Session-ID","timeprism-todo-writer");}).bodyValue(body).exchange();
    }
    WebTestClient.ResponseSpec send(JsonNode body){return send(body,"fixture-shared","shizuki-42");}

    @Test void sharedFixturesRoundTripAndRepeatedConfirmationDoesNotWriteAgain() {
        for(int i=0;i<fixtures.size();i++){
            ObjectNode body=request(i);JsonNode schedule=fixtures.get(i).path("schedule");
            var receipt=send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("confirmed")
                .jsonPath("$.cronExpr").isEqualTo(fixtures.get(i).path("cronExpr").asText());
            for(String key:List.of("title","timeZoneId","startAt","endAt","deadlineRemindValue","deadlineRemindUnit"))
                receipt.jsonPath("$."+key).isEqualTo(mapper.convertValue(schedule.get(key),Object.class));
            send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("confirmed");
            assertThat(posts).hasValue(i+1);
        }
    }
    @Test void uncertainCommitReconcilesByMarkerWithoutAnotherPost(){
        var body=request(0);failAfterCommit.set(true);
        send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("uncertain");
        send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("confirmed");
        assertThat(posts).hasValue(1);assertThat(gets).hasValue(1);
    }
    @Test void wrongOwnerAndTokenNeverReachSite(){
        send(request(0),"fixture-shared","shizuki-43").expectStatus().isForbidden();
        send(request(0),"wrong","shizuki-42").expectStatus().isUnauthorized();
        assertThat(logins).hasValue(0);assertThat(posts).hasValue(0);
    }
    @Test void invalidScheduleRejectsBeforeLoginAndMismatchedReceiptStaysUncertain(){
        var invalid=request(0);invalid.put("endAt","2020-01-01T00:00:00");
        send(invalid).expectStatus().isEqualTo(422);assertThat(logins).hasValue(0);
        tamper.set(true);send(request(0)).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("uncertain");
        assertThat(posts).hasValue(1);
    }
    @Test void changedContentForTheSameRequestIdIsConflict(){
        var body=request(0);send(body).expectStatus().isOk();body.put("deadlineRemindValue",25);
        send(body).expectStatus().isEqualTo(409);assertThat(posts).hasValue(1);
    }
    @Test void uncertainReceiptRecoversAfterRestartAndExpiredWindow() {
        var body=request(0);failAfterCommit.set(true);
        send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("uncertain");
        reconnect(java.time.Instant.parse("2031-01-01T00:00:00Z"));
        send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("confirmed");
        reconnect(java.time.Instant.parse("2031-01-02T00:00:00Z"));
        send(body).expectStatus().isOk().expectBody().jsonPath("$.ruleId").isEqualTo(91);
        assertThat(posts).hasValue(1);assertThat(gets).hasValue(1);
        send(request(0)).expectStatus().isEqualTo(422);
    }
    @Test void changingRequestIdDoesNotRepeatAnUncertainEquivalentWrite() {
        var body=request(0);failAfterCommit.set(true);
        send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("uncertain");
        var duplicate=body.deepCopy().put("requestId",UUID.randomUUID().toString());
        send(duplicate).expectStatus().isEqualTo(409);assertThat(posts).hasValue(1);
    }
    @Test void invalidTimezoneAdvanceAndFractionalReceiptFailClosed() {
        for (int amount : List.of(-1,43201)) {
            var body=request(0).put("deadlineRemindValue",amount);
            send(body).expectStatus().isEqualTo(422);
        }
        send(request(0).put("timeZoneId","Unknown/Zone")).expectStatus().isEqualTo(422);
        send(request(0).put("startAt","2030-02-30T00:00:00")).expectStatus().isEqualTo(422);
        assertThat(logins).hasValue(0);
        var body=request(0);failAfterCommit.set(true);
        send(body).expectStatus().isOk();((ObjectNode)rows.getFirst()).put("rule_id",91.5);
        send(body).expectStatus().isOk().expectBody().jsonPath("$.state").isEqualTo("uncertain");
        assertThat(posts).hasValue(1);
    }

}
