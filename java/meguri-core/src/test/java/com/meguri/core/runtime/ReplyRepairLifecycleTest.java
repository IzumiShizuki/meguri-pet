package com.meguri.core.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meguri.core.dto.TurnRequest;
import com.meguri.core.harness.TurnCommand;
import com.meguri.core.llm.LangChain4jLlmProvider;
import com.meguri.core.memory.NoopMemoryGateway;
import com.meguri.core.training.TrainingFeedbackService;
import com.meguri.core.weather.WeatherConversationService;
import com.meguri.core.websearch.NoopWebSearchGateway;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ReplyRepairLifecycleTest {
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static TurnRequest request(String session, String text) {
        return new TurnRequest("synthetic-review", "website", session, text, List.of(),
                new com.meguri.core.dto.ClientCapabilities(), null, null, false);
    }
    static TurnOrchestrator runtime(ChatModel model, TurnJournal journal) {
        var provider=new LangChain4jLlmProvider(model, JSON, "Current resolved persona", "json_schema", 1, Map.of());
        return new TurnOrchestrator(provider, null, new RuntimeStateMachine(), new ExpressionResolver(),
                Duration.ofMillis(1), JSON, new NoopMemoryGateway(), new NoopWebSearchGateway(),
                TrainingFeedbackService.disabled(JSON), WeatherConversationService.disabled(), journal);
    }
    static ChatResponse reply(String text) {
        try {
            return ChatResponse.builder().aiMessage(AiMessage.from(JSON.writeValueAsString(Map.of(
                    "reply",text,"expression_tag","neutral","expression_intensity","low",
                    "voice_style","neutral","memory_candidates",List.of())))).build();
        } catch (Exception e) { throw new AssertionError(e); }
    }

    @Test void exhaustedValidationIsNotReportedAsInterruptedStreamAndNextTurnRecovers() throws Exception {
        var calls=new AtomicInteger();
        ChatModel model=new ChatModel() {
            public ChatResponse doChat(ChatRequest request) { return reply(calls.incrementAndGet() <= 2 ? "多余前缀OK" : "恢复"); }
        };
        var journal=new InMemoryTurnJournal(JSON);
        var runtime=runtime(model,journal);
        try {
            var first=runtime.start(request("validation-code","只回复：OK"));
            first.getDone().get(5,TimeUnit.SECONDS);
            assertEquals(TurnStatus.FAILED,first.getStatus());
            assertEquals("REPLY_CONSTRAINT_VIOLATION",first.getFailureCode());
            assertTrue(journal.events("validation-code").stream().noneMatch(e->e.type().equals("text.delta")));
            var next=runtime.start(request("validation-code","只回复：恢复"));
            next.getDone().get(5,TimeUnit.SECONDS);
            assertEquals(TurnStatus.COMPLETED,next.getStatus());
            assertEquals("恢复",next.getResult().response().reply());
            assertEquals(3,calls.get());
        } finally { runtime.reset(); }
    }

    static class GatedRepair implements ChatModel {
        final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch repairing=new CountDownLatch(1), release=new CountDownLatch(1);
        final AtomicBoolean returned=new AtomicBoolean();
        public ChatResponse doChat(ChatRequest request) {
            int call=calls.incrementAndGet();
            if(call==1)return reply("多余前缀OK");
            if(call==2) {
                repairing.countDown();
                // Simulates a provider that delivers a late response despite interruption.
                boolean done=false;
                while(!done) {
                    try { done=release.await(5,TimeUnit.SECONDS); if(!done)throw new AssertionError("gate not released"); }
                    catch(InterruptedException ignored) { }
                }
                returned.set(true);
                return reply("OK");
            }
            return reply("恢复");
        }
    }

    @Test void cancelDuringRepairDropsLateAnswerAndReleasesSlotForNextTurn() throws Exception {
        exerciseInterruptedRepair(false);
    }

    @Test void deadlineDuringRepairDropsLateAnswerAndReleasesSlotForNextTurn() throws Exception {
        exerciseInterruptedRepair(true);
    }

    private static void exerciseInterruptedRepair(boolean deadline) throws Exception {
        var model=new GatedRepair();
        var journal=new InMemoryTurnJournal(JSON);
        var runtime=runtime(model,journal);
        String session=deadline?"repair-deadline":"repair-cancel";
        try {
            var started=runtime.submit(new TurnCommand.Start(request(session,"只回复：OK"),
                    session,Instant.now().plusMillis(deadline?1500:10000))).block();
            var record=journal.turn(started.turnId());
            assertTrue(model.repairing.await(3,TimeUnit.SECONDS));
            if(!deadline)runtime.cancel(record.getTurnId());
            record.getDone().get(4,TimeUnit.SECONDS);
            assertEquals(deadline?TurnStatus.FAILED:TurnStatus.CANCELLED,record.getStatus());
            if(deadline)assertEquals("TURN_DEADLINE_EXCEEDED",record.getFailureCode());
            model.release.countDown();
            await().atMost(Duration.ofSeconds(2)).untilTrue(model.returned);
            var next=runtime.start(request(session,"只回复：恢复"));
            next.getDone().get(5,TimeUnit.SECONDS);
            assertEquals(TurnStatus.COMPLETED,next.getStatus());
            assertEquals("恢复",next.getResult().response().reply());
            var firstEvents=journal.events(session).stream().filter(e->e.getTurnId().equals(record.getTurnId())).toList();
            assertTrue(firstEvents.stream().noneMatch(e->e.type().equals("text.delta")));
            assertEquals(1,firstEvents.stream().filter(e->Set.of("turn.cancelled","turn.failed","turn.completed").contains(e.type())).count());
            assertEquals(3,model.calls.get());
        } finally { model.release.countDown(); runtime.reset(); }
    }
}
