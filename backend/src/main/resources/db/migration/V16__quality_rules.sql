CREATE TABLE quality_rule_set_versions (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    rule_set_id varchar(128) NOT NULL,
    rule_set_version bigint NOT NULL CHECK (rule_set_version > 0),
    row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    state varchar(20) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED')),
    definition jsonb NOT NULL,
    catalog_version integer NOT NULL CHECK (catalog_version = 2),
    engine_version varchar(80) NOT NULL,
    required_issue_refs jsonb NOT NULL,
    selected_case_refs jsonb NOT NULL,
    content_digest varchar(71) NOT NULL CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    author_id varchar(40) NOT NULL REFERENCES principal(id) ON DELETE RESTRICT,
    reviewer_id varchar(40) REFERENCES principal(id) ON DELETE RESTRICT,
    review_reason varchar(1000),
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    UNIQUE (rule_set_id, rule_set_version),
    CHECK ((state = 'DRAFT' AND reviewer_id IS NULL AND published_at IS NULL)
        OR (state = 'PUBLISHED' AND reviewer_id IS NOT NULL AND published_at IS NOT NULL
            AND reviewer_id <> author_id AND review_reason IS NOT NULL))
);
CREATE UNIQUE INDEX uq_quality_rule_set_draft ON quality_rule_set_versions(rule_set_id)
    WHERE state = 'DRAFT';
CREATE INDEX ix_quality_rule_set_project ON quality_rule_set_versions(project_id, rule_set_id, rule_set_version);

CREATE TABLE quality_rule_versions (
    id varchar(40) PRIMARY KEY,
    rule_set_version_id varchar(40) NOT NULL REFERENCES quality_rule_set_versions(id) ON DELETE RESTRICT,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    rule_id varchar(64) NOT NULL,
    rule_version bigint NOT NULL CHECK (rule_version > 0),
    source_yaml text,
    validated_ast jsonb NOT NULL,
    source_path varchar(500),
    source_commit char(40),
    source_digest varchar(71) CHECK (source_digest ~ '^sha256:[0-9a-f]{64}$'),
    golden_fixture varchar(500),
    golden_digest varchar(71),
    CHECK ((source_yaml IS NULL AND source_path IS NULL AND source_commit IS NULL
        AND source_digest IS NULL AND golden_fixture IS NULL AND golden_digest IS NULL)
        OR (source_yaml IS NOT NULL AND source_path IS NOT NULL AND source_commit IS NOT NULL
        AND source_digest IS NOT NULL AND golden_fixture IS NOT NULL AND golden_digest IS NOT NULL)),
    UNIQUE (rule_set_version_id, ordinal),
    UNIQUE (rule_set_version_id, rule_id)
);

CREATE FUNCTION guard_quality_rule_set_write() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'PUBLISHED' THEN
        RAISE EXCEPTION 'Published Rule Set is immutable' USING ERRCODE = '55000';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Rule Set versions are retained' USING ERRCODE = '55000';
    END IF;
    IF NEW.state <> 'PUBLISHED' OR NEW.row_version <> OLD.row_version + 1
       OR ROW(NEW.id, NEW.project_id, NEW.rule_set_id, NEW.rule_set_version,
              NEW.definition, NEW.catalog_version, NEW.engine_version,
              NEW.required_issue_refs, NEW.selected_case_refs, NEW.content_digest,
              NEW.author_id, NEW.created_at)
          IS DISTINCT FROM
          ROW(OLD.id, OLD.project_id, OLD.rule_set_id, OLD.rule_set_version,
              OLD.definition, OLD.catalog_version, OLD.engine_version,
              OLD.required_issue_refs, OLD.selected_case_refs, OLD.content_digest,
              OLD.author_id, OLD.created_at) THEN
        RAISE EXCEPTION 'Draft Rule Set content is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_quality_rule_set_write BEFORE UPDATE OR DELETE ON quality_rule_set_versions
    FOR EACH ROW EXECUTE FUNCTION guard_quality_rule_set_write();

CREATE FUNCTION guard_quality_rule_version_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM quality_rule_set_versions
        WHERE id = NEW.rule_set_version_id AND state = 'DRAFT' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Published Rule Set cannot accept rules' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_quality_rule_version_insert BEFORE INSERT ON quality_rule_versions
    FOR EACH ROW EXECUTE FUNCTION guard_quality_rule_version_insert();
CREATE TRIGGER immutable_quality_rule_version BEFORE UPDATE OR DELETE ON quality_rule_versions
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
