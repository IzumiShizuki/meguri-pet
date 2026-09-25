package com.meguri.core.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCapabilityPersistence implements CapabilityPersistence {
    private final Map<String, CapabilityDescriptor> definitions = new LinkedHashMap<>();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final List<Execution> executions = new ArrayList<>();
    private final Map<String, ApprovalService.Approval> approvals = new LinkedHashMap<>();
    private final List<CapabilityAudit.Event> audits = new ArrayList<>();

    public synchronized void saveDefinition(CapabilityDescriptor value) {
        definitions.put(value.id() + "@" + value.version(), value);
    }
    public synchronized void saveBinding(Binding value) {
        bindings.put(value.tenantId() + ":" + value.capabilityId(), value);
    }
    public synchronized void saveExecution(Execution value) { executions.add(value); }
    public synchronized void saveApproval(ApprovalService.Approval value) { approvals.put(value.approvalId(), value); }
    public synchronized void saveAudit(CapabilityAudit.Event value) { audits.add(value); }

    public synchronized Optional<Execution> findOperation(
            String tenantId, String capabilityId, String idempotencyKey) {
        return executions.stream().filter(value -> value.tenantId().equals(tenantId)
                && value.capabilityId().equals(capabilityId)
                && java.util.Objects.equals(value.idempotencyKey(), idempotencyKey)).findFirst();
    }

    public synchronized List<CapabilityAudit.Event> auditEvents(String traceId) {
        return audits.stream().filter(value -> value.traceId().equals(traceId)).toList();
    }
}
