package com.aistock.research.configuration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "runtime_config_section")
public class RuntimeConfigSectionEntity {

    @Id
    @Column(name = "section_key", nullable = false, length = 64)
    private String sectionKey;

    @Column(name = "payload_json", nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(name = "revision", nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RuntimeConfigSectionEntity() {
    }

    public RuntimeConfigSectionEntity(
            String sectionKey,
            String payloadJson,
            long revision,
            Instant updatedAt
    ) {
        this.sectionKey = sectionKey;
        this.payloadJson = payloadJson;
        this.revision = revision;
        this.updatedAt = updatedAt;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public String getPayloadJson() {
        return payloadJson;
    }

    public long getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void replacePayload(String payloadJson, long revision, Instant updatedAt) {
        this.payloadJson = payloadJson;
        this.revision = revision;
        this.updatedAt = updatedAt;
    }
}
