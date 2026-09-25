package com.meguri.core.skill;

import java.util.List;
import java.util.Optional;

public interface SkillCatalogRepository {
    void saveManifest(SkillManifest manifest);
    void saveRevision(SkillRevision revision);
    void saveBinding(SkillBinding binding);
    Optional<SkillCatalogEntry> find(String skillId);
    Optional<SkillCatalogEntry> findBySource(String sourceId, String externalId);
    List<SkillCatalogEntry> list();
    void appendAudit(SkillAuditEvent event);
    List<SkillAuditEvent> audit();
}
