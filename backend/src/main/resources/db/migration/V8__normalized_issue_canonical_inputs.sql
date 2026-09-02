ALTER TABLE normalized_issue
    ADD COLUMN raw_severity_token varchar(120),
    ADD COLUMN mapping_warnings varchar(40),
    ADD CONSTRAINT ck_normalized_issue_v1_canonical_inputs
        CHECK (
            fact_digest_version IS NULL OR (
                raw_status_token IS NOT NULL AND
                raw_severity_token IS NOT NULL AND
                mapping_warnings IN (
                    '',
                    'UNKNOWN_SEVERITY',
                    'UNKNOWN_STATUS',
                    'UNKNOWN_SEVERITY,UNKNOWN_STATUS'
                )
            )
        ) NOT VALID;

COMMENT ON COLUMN normalized_issue.raw_severity_token IS
    'Canonical fact input for revisions created after V8; historical rows remain NULL and are snapshot-ineligible.';
COMMENT ON COLUMN normalized_issue.mapping_warnings IS
    'Sorted comma-separated IssueMappingWarning tokens; historical rows remain NULL and are snapshot-ineligible.';
