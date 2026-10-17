CREATE TABLE policy_ai_review_history (
    id UUID PRIMARY KEY,
    theme_code VARCHAR(80) NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    evidence_hash VARCHAR(64) NOT NULL,
    provider VARCHAR(120) NOT NULL,
    model VARCHAR(240) NOT NULL,
    prompt_version VARCHAR(80) NOT NULL,
    review_json JSONB NOT NULL
);

CREATE INDEX idx_policy_ai_review_theme_time
    ON policy_ai_review_history (theme_code, reviewed_at DESC);

CREATE INDEX idx_policy_ai_review_cache
    ON policy_ai_review_history (theme_code, evidence_hash, provider, model, prompt_version, reviewed_at DESC);
