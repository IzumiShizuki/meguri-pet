package com.meguri.core.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Named migration pack for incrementally moving legacy Gateways behind Capability Runtime. */
public final class LegacyGatewayCapabilityPack {
    public static final String WEATHER = "legacy.weather.read";
    public static final String WEB = "legacy.web.search";
    public static final String RAG = "legacy.rag.search";
    public static final String MEMORY_READ = "legacy.memory.recall";
    public static final String MEMORY_WRITE = "legacy.memory.write";
    public static final String BILLING = "legacy.billing.read";
    public static final String BILIBILI_READ = "legacy.bilibili.read";
    public static final String BILIBILI_WRITE = "legacy.bilibili.generate";

    private final List<CapabilityRuntimeFacade.CapabilityRegistration> registrations = new ArrayList<>();

    public LegacyGatewayCapabilityPack addRead(
            String capabilityId,
            String provider,
            boolean network,
            ExistingGatewayAdapter.GatewayInvocation invocation) {
        return add(capabilityId, provider, CapabilityDescriptor.Kind.READ_TOOL,
                CapabilityDescriptor.SideEffect.READ, CapabilityDescriptor.ApprovalRequirement.NONE,
                network, false, invocation);
    }

    public LegacyGatewayCapabilityPack addWrite(
            String capabilityId,
            String provider,
            boolean network,
            ExistingGatewayAdapter.GatewayInvocation invocation) {
        return add(capabilityId, provider, CapabilityDescriptor.Kind.WRITE_TOOL,
                CapabilityDescriptor.SideEffect.WRITE, CapabilityDescriptor.ApprovalRequirement.ALWAYS,
                network, true, invocation);
    }

    public LegacyGatewayCapabilityPack weather(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addRead(WEATHER, "weather-gateway", true, operation);
    }

    public LegacyGatewayCapabilityPack web(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addRead(WEB, "web-search-gateway", true, operation);
    }

    public LegacyGatewayCapabilityPack rag(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addRead(RAG, "rag-gateway", false, operation);
    }

    public LegacyGatewayCapabilityPack memoryRead(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addRead(MEMORY_READ, "memory-gateway", true, operation);
    }

    public LegacyGatewayCapabilityPack memoryWrite(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addWrite(MEMORY_WRITE, "memory-gateway", true, operation);
    }

    public LegacyGatewayCapabilityPack billing(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addRead(BILLING, "billing-gateway", false, operation);
    }

    public LegacyGatewayCapabilityPack bilibiliRead(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addRead(BILIBILI_READ, "bilibili-gateway", true, operation);
    }

    public LegacyGatewayCapabilityPack bilibiliWrite(ExistingGatewayAdapter.GatewayInvocation operation) {
        return addWrite(BILIBILI_WRITE, "bilibili-gateway", true, operation);
    }

    public void registerInto(CapabilityRuntimeFacade runtime) {
        Objects.requireNonNull(runtime, "runtime").registerBatch(List.copyOf(registrations));
    }

    public List<CapabilityRuntimeFacade.CapabilityRegistration> registrations() {
        return List.copyOf(registrations);
    }

    private LegacyGatewayCapabilityPack add(
            String id,
            String provider,
            CapabilityDescriptor.Kind kind,
            CapabilityDescriptor.SideEffect sideEffect,
            CapabilityDescriptor.ApprovalRequirement approval,
            boolean network,
            boolean idempotentWrite,
            ExistingGatewayAdapter.GatewayInvocation invocation) {
        CapabilityDescriptor.ResultTrust trust = network
                ? CapabilityDescriptor.ResultTrust.UNTRUSTED_EXTERNAL
                : CapabilityDescriptor.ResultTrust.TRUSTED_LOCAL;
        CapabilityDescriptor descriptor = ExistingGatewayAdapter.descriptor(
                id, "1", kind, sideEffect,
                new CapabilityDescriptor.Schema(Map.of(), java.util.Set.of(), true, java.util.Set.of()),
                new CapabilityDescriptor.Schema(Map.of("data", CapabilityDescriptor.ValueType.OBJECT),
                        java.util.Set.of("data"), true, java.util.Set.of()),
                approval, trust, network, idempotentWrite);
        registrations.add(new CapabilityRuntimeFacade.CapabilityRegistration(
                descriptor, new ExistingGatewayAdapter(provider, trust, invocation)));
        return this;
    }
}
