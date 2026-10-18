CREATE TABLE gov_policy_archive (
    source_key VARCHAR(64) NOT NULL,
    article_key VARCHAR(64) NOT NULL,
    published_on DATE,
    planning_baseline BOOLEAN NOT NULL DEFAULT FALSE,
    verified_exclusion BOOLEAN NOT NULL DEFAULT FALSE,
    item_json JSONB NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_key, article_key),
    CONSTRAINT gov_policy_archive_json_object CHECK (jsonb_typeof(item_json) = 'object')
);
CREATE INDEX gov_policy_archive_source_date ON gov_policy_archive (source_key, published_on DESC);
