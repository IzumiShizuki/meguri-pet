package com.meguri.core.persona.relationship;

import com.meguri.core.dto.Relationship;
import com.meguri.core.persona.PersonaAuditEvent;
import com.meguri.core.persona.PersonaAuditRepository;
import com.meguri.core.persona.PersonaMutation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The only supported authority for relationship-stage changes. */
public final class RelationshipTransitionService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final RelationshipStateRepository repository;
    private final Clock clock;
    public RelationshipTransitionService(RelationshipStateRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    public RelationshipState current(String userId) {
        return repository.findRelationship(userId).orElse(new RelationshipState(userId, Relationship.SIBLING, 0, 0, 0,
                Set.of(), 0, Instant.EPOCH, RelationshipState.Source.SYSTEM));
    }

    public RelationshipState transition(String userId, long expectedVersion, Relationship target,
                                        RelationshipState.Source source, String triggerId, String policyRevision) {
        if (source == RelationshipState.Source.SYSTEM) throw new SecurityException("relationship transition requires user approval or approved event");
        RelationshipState old = current(userId);
        if (old.version() != expectedVersion) throw new java.util.ConcurrentModificationException("relationship version conflict");
        RelationshipState next = new RelationshipState(userId, target, old.familiarity(), old.trust(), old.recentTension(),
                old.boundaryFlags(), old.version() + 1, clock.instant(), source);
        repository.saveTransition(old, next, expectedVersion,
                new PersonaMutation(source.name(), triggerId, policyRevision, userId, clock.instant()));
        return next;
    }

    public List<Audit> auditLog(String userId) {
        if (!(repository instanceof PersonaAuditRepository audits)) return List.of();
        return audits.auditLog(userId, PersonaAuditEvent.StateType.RELATIONSHIP).stream()
                .map(event -> new Audit(userId, relationship(event.oldValueJson()).stage(),
                        relationship(event.newValueJson()).stage(), event.mutation().triggerId(),
                        event.mutation().policyRevision(), relationship(event.newValueJson()).source(),
                        event.createdAt(), event.version()))
                .toList();
    }
    private static RelationshipState relationship(String value) {
        try { return JSON.readValue(value, RelationshipState.class); }
        catch (Exception error) { throw new IllegalStateException("invalid relationship audit payload", error); }
    }
    public record Audit(String userId, Relationship oldStage, Relationship newStage, String triggerId,
                        String policyRevision, RelationshipState.Source source, Instant createdAt, long version) { }
}
