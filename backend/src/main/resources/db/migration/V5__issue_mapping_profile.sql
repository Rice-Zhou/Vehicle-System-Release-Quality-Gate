CREATE TABLE issue_mapping_profile (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    source_id varchar(40) NOT NULL,
    schema_version varchar(80) NOT NULL,
    mapping_version varchar(80) NOT NULL,
    definition jsonb NOT NULL,
    created_by varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_mapping_profile_source_version UNIQUE (source_id, mapping_version),
    CONSTRAINT fk_mapping_profile_source_project FOREIGN KEY (source_id, project_id)
        REFERENCES issue_source(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mapping_profile_creator FOREIGN KEY (created_by)
        REFERENCES principal(id) ON DELETE RESTRICT,
    CONSTRAINT ck_mapping_profile_version
        CHECK (mapping_version ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_mapping_profile_definition_object
        CHECK (jsonb_typeof(definition) = 'object')
);

CREATE INDEX ix_mapping_profile_project_source_created
    ON issue_mapping_profile(project_id, source_id, created_at DESC);

CREATE TRIGGER immutable_issue_mapping_profile
    BEFORE UPDATE OR DELETE ON issue_mapping_profile
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
