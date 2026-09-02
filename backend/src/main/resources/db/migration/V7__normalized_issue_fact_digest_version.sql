ALTER TABLE normalized_issue
    ADD COLUMN fact_digest_version varchar(40),
    ADD CONSTRAINT ck_normalized_issue_fact_digest_version
        CHECK (
            fact_digest_version IS NULL
            OR fact_digest_version = 'normalized-issue-facts/v1'
        );
