ALTER TABLE release_record
    ADD CONSTRAINT uq_release_id_project UNIQUE (id, project_id);

CREATE TABLE background_job (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40),
    outbox_event_id varchar(40),
    job_type varchar(80) NOT NULL,
    idempotency_key varchar(255) NOT NULL,
    status varchar(20) NOT NULL,
    payload jsonb NOT NULL,
    result_summary jsonb,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    available_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_background_job_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT fk_background_job_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_background_job_outbox FOREIGN KEY (outbox_event_id) REFERENCES outbox_event(id) ON DELETE RESTRICT,
    UNIQUE (job_type, idempotency_key)
);
CREATE INDEX ix_background_job_dispatch ON background_job(status, available_at, created_at);
CREATE INDEX ix_background_job_project ON background_job(project_id);
CREATE INDEX ix_background_job_outbox ON background_job(outbox_event_id);

CREATE TABLE issue_source (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    source_key varchar(120) NOT NULL,
    source_type varchar(20) NOT NULL,
    adapter_version varchar(80) NOT NULL,
    mapping_version varchar(80) NOT NULL,
    credential_reference varchar(255),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_issue_source_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT uq_issue_source_project_key UNIQUE (project_id, source_key),
    CONSTRAINT uq_issue_source_id_project UNIQUE (id, project_id),
    CONSTRAINT ck_issue_source_type CHECK (source_type IN ('JIRA', 'INTERNAL', 'FIXTURE'))
);

CREATE TABLE issue_sync_run (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    source_id varchar(40) NOT NULL,
    sync_run_id varchar(40) NOT NULL,
    status varchar(20) NOT NULL,
    cursor_before text,
    cursor_after text,
    source_watermark text,
    adapter_version varchar(80) NOT NULL,
    mapping_version varchar(80) NOT NULL,
    issue_count integer NOT NULL DEFAULT 0 CHECK (issue_count >= 0),
    warning_count integer NOT NULL DEFAULT 0 CHECK (warning_count >= 0),
    diagnostic_code varchar(80),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_sync_run_source_project FOREIGN KEY (source_id, project_id)
        REFERENCES issue_source(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_sync_run_source_identity UNIQUE (source_id, sync_run_id),
    CONSTRAINT uq_sync_run_id_project UNIQUE (id, project_id),
    CONSTRAINT uq_sync_run_id_source_project UNIQUE (id, source_id, project_id),
    CONSTRAINT ck_sync_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX ix_issue_sync_run_source_created ON issue_sync_run(source_id, created_at DESC);

CREATE TABLE issue_sync_cursor (
    source_id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    cursor_value text,
    source_watermark text,
    last_successful_sync_run_id varchar(40),
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_sync_cursor_source_project FOREIGN KEY (source_id, project_id)
        REFERENCES issue_source(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_cursor_run_source_project FOREIGN KEY (last_successful_sync_run_id, source_id, project_id)
        REFERENCES issue_sync_run(id, source_id, project_id) ON DELETE RESTRICT
);
CREATE INDEX ix_issue_sync_cursor_run ON issue_sync_cursor(last_successful_sync_run_id);

CREATE TABLE normalized_issue (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    source_id varchar(40) NOT NULL,
    source_issue_id varchar(255) NOT NULL,
    title text NOT NULL,
    severity varchar(40) NOT NULL,
    status varchar(20) NOT NULL,
    raw_status_token varchar(120),
    source_version varchar(255) NOT NULL,
    source_reference varchar(512) NOT NULL,
    observed_at timestamptz NOT NULL,
    mapping_version varchar(80) NOT NULL,
    tombstone boolean NOT NULL DEFAULT false,
    fact_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_normalized_issue_source_project FOREIGN KEY (source_id, project_id)
        REFERENCES issue_source(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_normalized_issue_source_version_mapping
        UNIQUE (source_id, source_issue_id, source_version, mapping_version),
    CONSTRAINT uq_normalized_issue_id_project UNIQUE (id, project_id),
    CONSTRAINT ck_normalized_issue_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'UNKNOWN')),
    CONSTRAINT ck_normalized_issue_digest CHECK (fact_digest ~ '^sha256:[0-9a-f]{64}$')
);
CREATE INDEX ix_normalized_issue_source_observed
    ON normalized_issue(source_id, source_issue_id, observed_at DESC, created_at DESC);

CREATE TABLE release_issue_snapshot (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    release_id varchar(40) NOT NULL,
    sync_run_id varchar(40) NOT NULL,
    snapshot_version integer NOT NULL CHECK (snapshot_version > 0),
    filter_reference varchar(255) NOT NULL,
    content_digest varchar(71) NOT NULL,
    creation_transaction_id bigint NOT NULL DEFAULT (pg_catalog.pg_current_xact_id()::text::bigint),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_issue_snapshot_release_project FOREIGN KEY (release_id, project_id)
        REFERENCES release_record(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_issue_snapshot_run_project FOREIGN KEY (sync_run_id, project_id)
        REFERENCES issue_sync_run(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_issue_snapshot_release_version UNIQUE (release_id, snapshot_version),
    CONSTRAINT uq_issue_snapshot_digest UNIQUE (content_digest),
    CONSTRAINT uq_issue_snapshot_id_project UNIQUE (id, project_id),
    CONSTRAINT ck_issue_snapshot_digest CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$')
);
CREATE INDEX ix_issue_snapshot_release_version ON release_issue_snapshot(release_id, snapshot_version DESC);
CREATE INDEX ix_issue_snapshot_sync_run ON release_issue_snapshot(sync_run_id);

CREATE TABLE release_issue_snapshot_item (
    snapshot_id varchar(40) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    project_id varchar(40) NOT NULL,
    issue_id varchar(40) NOT NULL,
    source_issue_id varchar(255) NOT NULL,
    title text NOT NULL,
    severity varchar(40) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'UNKNOWN')),
    raw_status_token varchar(120),
    source_version varchar(255) NOT NULL,
    source_reference varchar(512) NOT NULL,
    observed_at timestamptz NOT NULL,
    mapping_version varchar(80) NOT NULL,
    fact_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (snapshot_id, ordinal),
    CONSTRAINT fk_issue_snapshot_item_snapshot_project FOREIGN KEY (snapshot_id, project_id)
        REFERENCES release_issue_snapshot(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_issue_snapshot_item_issue_project FOREIGN KEY (issue_id, project_id)
        REFERENCES normalized_issue(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_issue_snapshot_item_issue UNIQUE (snapshot_id, issue_id),
    CONSTRAINT ck_issue_snapshot_item_digest CHECK (fact_digest ~ '^sha256:[0-9a-f]{64}$')
);
CREATE INDEX ix_issue_snapshot_item_issue ON release_issue_snapshot_item(issue_id);

CREATE TABLE source_commit (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    repository varchar(512) NOT NULL,
    commit_id varchar(255) NOT NULL,
    branch varchar(255),
    author_reference varchar(255),
    committed_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_source_commit_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT uq_source_commit_identity UNIQUE (project_id, repository, commit_id),
    CONSTRAINT uq_source_commit_id_project UNIQUE (id, project_id)
);
CREATE INDEX ix_source_commit_project ON source_commit(project_id, committed_at DESC);

CREATE TABLE build_record (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    provider varchar(255) NOT NULL,
    build_id varchar(255) NOT NULL,
    pipeline varchar(255),
    source_revision varchar(255) NOT NULL,
    branch varchar(255),
    built_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_build_record_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT uq_build_record_identity UNIQUE (project_id, provider, build_id),
    CONSTRAINT uq_build_record_id_project UNIQUE (id, project_id)
);
CREATE INDEX ix_build_record_project ON build_record(project_id, built_at DESC);

CREATE TABLE issue_commit_edge_revision (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    edge_id varchar(40) NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    issue_id varchar(40) NOT NULL,
    commit_id varchar(40) NOT NULL,
    source_type varchar(40) NOT NULL,
    source_reference varchar(512) NOT NULL,
    evidence_id varchar(40),
    confidence varchar(20) NOT NULL,
    verification_status varchar(20) NOT NULL,
    verified_at timestamptz,
    verified_by varchar(40),
    reason text,
    validator_version varchar(80) NOT NULL,
    previous_revision_id varchar(40),
    previous_revision integer,
    content_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_issue_commit_issue_project FOREIGN KEY (issue_id, project_id)
        REFERENCES normalized_issue(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_issue_commit_commit_project FOREIGN KEY (commit_id, project_id)
        REFERENCES source_commit(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_issue_commit_verified_by FOREIGN KEY (verified_by) REFERENCES principal(id) ON DELETE RESTRICT,
    CONSTRAINT uq_issue_commit_edge_revision UNIQUE (edge_id, revision),
    CONSTRAINT uq_issue_commit_revision_identity UNIQUE (id, edge_id, revision),
    CONSTRAINT fk_issue_commit_previous FOREIGN KEY (previous_revision_id, edge_id, previous_revision)
        REFERENCES issue_commit_edge_revision(id, edge_id, revision) ON DELETE RESTRICT DEFERRABLE,
    CONSTRAINT ck_issue_commit_revision_chain CHECK (
        (revision = 1 AND previous_revision_id IS NULL AND previous_revision IS NULL)
        OR (revision > 1 AND previous_revision_id IS NOT NULL AND previous_revision = revision - 1)
    ),
    CONSTRAINT ck_issue_commit_digest CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_issue_commit_confidence CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    CONSTRAINT ck_issue_commit_status CHECK (verification_status IN ('VALID', 'INVALID', 'CONFLICT', 'ERROR'))
);
CREATE INDEX ix_issue_commit_edge ON issue_commit_edge_revision(edge_id, revision DESC);
CREATE INDEX ix_issue_commit_endpoints ON issue_commit_edge_revision(issue_id, commit_id);
CREATE INDEX ix_issue_commit_commit ON issue_commit_edge_revision(commit_id);
CREATE INDEX ix_issue_commit_verified_by ON issue_commit_edge_revision(verified_by);
CREATE INDEX ix_issue_commit_status_confidence ON issue_commit_edge_revision(verification_status, confidence);

CREATE TABLE commit_build_edge_revision (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    edge_id varchar(40) NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    commit_id varchar(40) NOT NULL,
    build_id varchar(40) NOT NULL,
    source_type varchar(40) NOT NULL,
    source_reference varchar(512) NOT NULL,
    evidence_id varchar(40),
    confidence varchar(20) NOT NULL,
    verification_status varchar(20) NOT NULL,
    verified_at timestamptz,
    verified_by varchar(40),
    reason text,
    validator_version varchar(80) NOT NULL,
    previous_revision_id varchar(40),
    previous_revision integer,
    content_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_commit_build_commit_project FOREIGN KEY (commit_id, project_id)
        REFERENCES source_commit(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_commit_build_build_project FOREIGN KEY (build_id, project_id)
        REFERENCES build_record(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_commit_build_verified_by FOREIGN KEY (verified_by) REFERENCES principal(id) ON DELETE RESTRICT,
    CONSTRAINT uq_commit_build_edge_revision UNIQUE (edge_id, revision),
    CONSTRAINT uq_commit_build_revision_identity UNIQUE (id, edge_id, revision),
    CONSTRAINT fk_commit_build_previous FOREIGN KEY (previous_revision_id, edge_id, previous_revision)
        REFERENCES commit_build_edge_revision(id, edge_id, revision) ON DELETE RESTRICT DEFERRABLE,
    CONSTRAINT ck_commit_build_revision_chain CHECK (
        (revision = 1 AND previous_revision_id IS NULL AND previous_revision IS NULL)
        OR (revision > 1 AND previous_revision_id IS NOT NULL AND previous_revision = revision - 1)
    ),
    CONSTRAINT ck_commit_build_digest CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_commit_build_confidence CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    CONSTRAINT ck_commit_build_status CHECK (verification_status IN ('VALID', 'INVALID', 'CONFLICT', 'ERROR'))
);
CREATE INDEX ix_commit_build_edge ON commit_build_edge_revision(edge_id, revision DESC);
CREATE INDEX ix_commit_build_endpoints ON commit_build_edge_revision(commit_id, build_id);
CREATE INDEX ix_commit_build_build ON commit_build_edge_revision(build_id);
CREATE INDEX ix_commit_build_verified_by ON commit_build_edge_revision(verified_by);
CREATE INDEX ix_commit_build_status_confidence ON commit_build_edge_revision(verification_status, confidence);

CREATE TABLE build_artifact_edge_revision (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    edge_id varchar(40) NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    build_id varchar(40) NOT NULL,
    artifact_id varchar(40) NOT NULL,
    source_type varchar(40) NOT NULL,
    source_reference varchar(512) NOT NULL,
    evidence_id varchar(40),
    confidence varchar(20) NOT NULL,
    verification_status varchar(20) NOT NULL,
    verified_at timestamptz,
    verified_by varchar(40),
    reason text,
    validator_version varchar(80) NOT NULL,
    previous_revision_id varchar(40),
    previous_revision integer,
    content_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_build_artifact_build_project FOREIGN KEY (build_id, project_id)
        REFERENCES build_record(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_artifact_artifact FOREIGN KEY (artifact_id) REFERENCES artifact(id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_artifact_verified_by FOREIGN KEY (verified_by) REFERENCES principal(id) ON DELETE RESTRICT,
    CONSTRAINT uq_build_artifact_edge_revision UNIQUE (edge_id, revision),
    CONSTRAINT uq_build_artifact_revision_identity UNIQUE (id, edge_id, revision),
    CONSTRAINT fk_build_artifact_previous FOREIGN KEY (previous_revision_id, edge_id, previous_revision)
        REFERENCES build_artifact_edge_revision(id, edge_id, revision) ON DELETE RESTRICT DEFERRABLE,
    CONSTRAINT ck_build_artifact_revision_chain CHECK (
        (revision = 1 AND previous_revision_id IS NULL AND previous_revision IS NULL)
        OR (revision > 1 AND previous_revision_id IS NOT NULL AND previous_revision = revision - 1)
    ),
    CONSTRAINT ck_build_artifact_digest CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_build_artifact_confidence CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    CONSTRAINT ck_build_artifact_status CHECK (verification_status IN ('VALID', 'INVALID', 'CONFLICT', 'ERROR'))
);
CREATE INDEX ix_build_artifact_edge ON build_artifact_edge_revision(edge_id, revision DESC);
CREATE INDEX ix_build_artifact_endpoints ON build_artifact_edge_revision(build_id, artifact_id);
CREATE INDEX ix_build_artifact_artifact ON build_artifact_edge_revision(artifact_id);
CREATE INDEX ix_build_artifact_verified_by ON build_artifact_edge_revision(verified_by);
CREATE INDEX ix_build_artifact_status_confidence ON build_artifact_edge_revision(verification_status, confidence);

CREATE TABLE traceability_verification_run (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    release_id varchar(40) NOT NULL,
    verification_run_id varchar(40) NOT NULL,
    status varchar(20) NOT NULL,
    policy_version varchar(80) NOT NULL,
    diagnostic_code varchar(80),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_verification_run_release_project FOREIGN KEY (release_id, project_id)
        REFERENCES release_record(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_verification_run_identity UNIQUE (verification_run_id),
    CONSTRAINT uq_verification_run_id_release_project UNIQUE (id, release_id, project_id),
    CONSTRAINT ck_verification_run_status CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX ix_verification_run_release_created ON traceability_verification_run(release_id, created_at DESC);

CREATE TABLE traceability_gap (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    verification_run_id varchar(40) NOT NULL,
    release_id varchar(40) NOT NULL,
    issue_id varchar(40),
    expected_edge_type varchar(40) NOT NULL CHECK (expected_edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT', 'ARTIFACT_RELEASE', 'RELEASE_TEST', 'TEST_EVIDENCE')),
    reason text NOT NULL,
    diagnostic_code varchar(80) NOT NULL,
    gap_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_gap_run_release_project FOREIGN KEY (verification_run_id, release_id, project_id)
        REFERENCES traceability_verification_run(id, release_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_gap_issue_project FOREIGN KEY (issue_id, project_id)
        REFERENCES normalized_issue(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_gap_run_digest UNIQUE (verification_run_id, gap_digest),
    CONSTRAINT ck_gap_digest CHECK (gap_digest ~ '^sha256:[0-9a-f]{64}$')
);
CREATE INDEX ix_gap_run ON traceability_gap(verification_run_id, created_at);
CREATE INDEX ix_gap_issue ON traceability_gap(issue_id);

CREATE TABLE traceability_snapshot (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    release_id varchar(40) NOT NULL,
    verification_run_id varchar(40) NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    schema_version varchar(40) NOT NULL,
    policy_version varchar(80) NOT NULL,
    content_digest varchar(71) NOT NULL,
    creation_transaction_id bigint NOT NULL DEFAULT (pg_catalog.pg_current_xact_id()::text::bigint),
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_trace_snapshot_release_project FOREIGN KEY (release_id, project_id)
        REFERENCES release_record(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_trace_snapshot_run_release_project FOREIGN KEY (verification_run_id, release_id, project_id)
        REFERENCES traceability_verification_run(id, release_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_trace_snapshot_release_version UNIQUE (release_id, version),
    CONSTRAINT uq_trace_snapshot_digest UNIQUE (content_digest),
    CONSTRAINT uq_trace_snapshot_id_project UNIQUE (id, project_id),
    CONSTRAINT uq_trace_snapshot_id_release_project UNIQUE (id, release_id, project_id),
    CONSTRAINT ck_trace_snapshot_digest CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$')
);
CREATE INDEX ix_trace_snapshot_release_version ON traceability_snapshot(release_id, version DESC);
CREATE INDEX ix_trace_snapshot_verification_run ON traceability_snapshot(verification_run_id);

CREATE TABLE traceability_snapshot_edge (
    snapshot_id varchar(40) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    project_id varchar(40) NOT NULL,
    edge_type varchar(40) NOT NULL CHECK (edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT', 'ARTIFACT_RELEASE')),
    from_entity_type varchar(40) NOT NULL,
    from_entity_id varchar(40) NOT NULL,
    to_entity_type varchar(40) NOT NULL,
    to_entity_id varchar(40) NOT NULL,
    source_edge_id varchar(40) NOT NULL,
    source_edge_revision integer NOT NULL CHECK (source_edge_revision > 0),
    source_type varchar(40) NOT NULL,
    source_reference varchar(512) NOT NULL,
    confidence varchar(20) NOT NULL,
    verification_status varchar(20) NOT NULL,
    verified_at timestamptz,
    validator_version varchar(80) NOT NULL,
    reason text,
    evidence_id varchar(40),
    fact_digest varchar(71) NOT NULL,
    manifest_revision_id varchar(40),
    manifest_digest varchar(71),
    manifest_artifact_ordinal integer,
    manifest_artifact_required boolean,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (snapshot_id, ordinal),
    CONSTRAINT fk_snapshot_edge_snapshot_project FOREIGN KEY (snapshot_id, project_id)
        REFERENCES traceability_snapshot(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_snapshot_edge_digest UNIQUE (snapshot_id, fact_digest),
    CONSTRAINT ck_snapshot_edge_digest CHECK (fact_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_snapshot_edge_confidence CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    CONSTRAINT ck_snapshot_edge_status CHECK (verification_status IN ('VALID', 'INVALID', 'CONFLICT', 'ERROR')),
    CONSTRAINT ck_snapshot_edge_manifest_authority CHECK (
        (
            edge_type = 'ARTIFACT_RELEASE'
            AND manifest_revision_id IS NOT NULL
            AND manifest_digest ~ '^sha256:[0-9a-f]{64}$'
            AND manifest_artifact_ordinal IS NOT NULL
            AND manifest_artifact_ordinal >= 0
            AND manifest_artifact_required IS NOT NULL
        )
        OR (
            edge_type <> 'ARTIFACT_RELEASE'
            AND manifest_revision_id IS NULL
            AND manifest_digest IS NULL
            AND manifest_artifact_ordinal IS NULL
            AND manifest_artifact_required IS NULL
        )
    )
);
CREATE INDEX ix_snapshot_edge_source ON traceability_snapshot_edge(source_edge_id, source_edge_revision);

CREATE TABLE traceability_snapshot_gap (
    snapshot_id varchar(40) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    project_id varchar(40) NOT NULL,
    issue_id varchar(40),
    release_id varchar(40) NOT NULL,
    expected_edge_type varchar(40) NOT NULL CHECK (expected_edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT', 'ARTIFACT_RELEASE', 'RELEASE_TEST', 'TEST_EVIDENCE')),
    reason text NOT NULL,
    diagnostic_code varchar(80) NOT NULL,
    gap_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (snapshot_id, ordinal),
    CONSTRAINT fk_snapshot_gap_snapshot_release_project FOREIGN KEY (snapshot_id, release_id, project_id)
        REFERENCES traceability_snapshot(id, release_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_snapshot_gap_issue_project FOREIGN KEY (issue_id, project_id)
        REFERENCES normalized_issue(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_snapshot_gap_digest UNIQUE (snapshot_id, gap_digest),
    CONSTRAINT ck_snapshot_gap_digest CHECK (gap_digest ~ '^sha256:[0-9a-f]{64}$')
);
CREATE INDEX ix_snapshot_gap_issue ON traceability_snapshot_gap(issue_id);
CREATE INDEX ix_snapshot_gap_release ON traceability_snapshot_gap(release_id);

CREATE FUNCTION enforce_issue_commit_edge_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE identity_changed boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.issue_commit_edge_revision existing
            WHERE existing.edge_id = $1 AND existing.id <> $2
              AND (existing.project_id IS DISTINCT FROM $3 OR existing.issue_id IS DISTINCT FROM $4
                   OR existing.commit_id IS DISTINCT FROM $5 OR existing.source_type IS DISTINCT FROM $6
                   OR existing.source_reference IS DISTINCT FROM $7)
        )',
        TG_TABLE_SCHEMA
    ) INTO identity_changed
    USING NEW.edge_id, NEW.id, NEW.project_id, NEW.issue_id, NEW.commit_id, NEW.source_type, NEW.source_reference;
    IF identity_changed THEN
        RAISE EXCEPTION 'issue_commit edge % cannot change endpoints or source identity', NEW.edge_id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_snapshot_child_creation_transaction() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE parent_created_in_current_transaction boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.%I snapshot
            WHERE snapshot.id = $1
              AND snapshot.creation_transaction_id = (pg_catalog.pg_current_xact_id()::text::bigint)
        )',
        TG_TABLE_SCHEMA,
        TG_ARGV[0]
    ) INTO parent_created_in_current_transaction USING NEW.snapshot_id;
    IF NOT parent_created_in_current_transaction THEN
        RAISE EXCEPTION '% can only be inserted in its snapshot creation transaction', TG_TABLE_NAME
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_snapshot_header_creation_transaction() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.creation_transaction_id IS DISTINCT FROM (pg_catalog.pg_current_xact_id()::text::bigint) THEN
        RAISE EXCEPTION 'snapshot creation transaction is database controlled' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_commit_build_edge_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE identity_changed boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.commit_build_edge_revision existing
            WHERE existing.edge_id = $1 AND existing.id <> $2
              AND (existing.project_id IS DISTINCT FROM $3 OR existing.commit_id IS DISTINCT FROM $4
                   OR existing.build_id IS DISTINCT FROM $5 OR existing.source_type IS DISTINCT FROM $6
                   OR existing.source_reference IS DISTINCT FROM $7)
        )',
        TG_TABLE_SCHEMA
    ) INTO identity_changed
    USING NEW.edge_id, NEW.id, NEW.project_id, NEW.commit_id, NEW.build_id, NEW.source_type, NEW.source_reference;
    IF identity_changed THEN
        RAISE EXCEPTION 'commit_build edge % cannot change endpoints or source identity', NEW.edge_id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_build_artifact_edge_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE identity_changed boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.build_artifact_edge_revision existing
            WHERE existing.edge_id = $1 AND existing.id <> $2
              AND (existing.project_id IS DISTINCT FROM $3 OR existing.build_id IS DISTINCT FROM $4
                   OR existing.artifact_id IS DISTINCT FROM $5 OR existing.source_type IS DISTINCT FROM $6
                   OR existing.source_reference IS DISTINCT FROM $7)
        )',
        TG_TABLE_SCHEMA
    ) INTO identity_changed
    USING NEW.edge_id, NEW.id, NEW.project_id, NEW.build_id, NEW.artifact_id, NEW.source_type, NEW.source_reference;
    IF identity_changed THEN
        RAISE EXCEPTION 'build_artifact edge % cannot change endpoints or source identity', NEW.edge_id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_traceability_snapshot_edge_source() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    snapshot_release_id varchar(40);
    source_matches boolean;
BEGIN
    EXECUTE format(
        'SELECT snapshot.release_id FROM %I.traceability_snapshot snapshot
         WHERE snapshot.id = $1 AND snapshot.project_id = $2',
        TG_TABLE_SCHEMA
    ) INTO snapshot_release_id USING NEW.snapshot_id, NEW.project_id;
    IF snapshot_release_id IS NULL THEN
        RAISE EXCEPTION 'snapshot edge has no authoritative snapshot header' USING ERRCODE = '23514';
    END IF;

    CASE NEW.edge_type
        WHEN 'ISSUE_COMMIT' THEN
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.issue_commit_edge_revision source_revision
                 WHERE source_revision.project_id = $1 AND source_revision.edge_id = $2
                   AND source_revision.revision = $3 AND source_revision.issue_id = $4
                   AND source_revision.commit_id = $5 AND source_revision.source_type = $6
                   AND source_revision.source_reference = $7 AND source_revision.confidence = $8
                   AND source_revision.verification_status = $9
                   AND source_revision.verified_at IS NOT DISTINCT FROM $10
                   AND source_revision.validator_version = $11
                   AND source_revision.reason IS NOT DISTINCT FROM $12
                   AND source_revision.evidence_id IS NOT DISTINCT FROM $13)',
                TG_TABLE_SCHEMA
            ) INTO source_matches
            USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.from_entity_id,
                NEW.to_entity_id, NEW.source_type, NEW.source_reference, NEW.confidence,
                NEW.verification_status, NEW.verified_at, NEW.validator_version, NEW.reason, NEW.evidence_id;
            IF NEW.from_entity_type <> 'ISSUE' OR NEW.to_entity_type <> 'COMMIT' OR NOT source_matches THEN
                RAISE EXCEPTION 'snapshot ISSUE_COMMIT edge does not match an authoritative revision'
                    USING ERRCODE = '23514';
            END IF;
        WHEN 'COMMIT_BUILD' THEN
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.commit_build_edge_revision source_revision
                 WHERE source_revision.project_id = $1 AND source_revision.edge_id = $2
                   AND source_revision.revision = $3 AND source_revision.commit_id = $4
                   AND source_revision.build_id = $5 AND source_revision.source_type = $6
                   AND source_revision.source_reference = $7 AND source_revision.confidence = $8
                   AND source_revision.verification_status = $9
                   AND source_revision.verified_at IS NOT DISTINCT FROM $10
                   AND source_revision.validator_version = $11
                   AND source_revision.reason IS NOT DISTINCT FROM $12
                   AND source_revision.evidence_id IS NOT DISTINCT FROM $13)',
                TG_TABLE_SCHEMA
            ) INTO source_matches
            USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.from_entity_id,
                NEW.to_entity_id, NEW.source_type, NEW.source_reference, NEW.confidence,
                NEW.verification_status, NEW.verified_at, NEW.validator_version, NEW.reason, NEW.evidence_id;
            IF NEW.from_entity_type <> 'COMMIT' OR NEW.to_entity_type <> 'BUILD' OR NOT source_matches THEN
                RAISE EXCEPTION 'snapshot COMMIT_BUILD edge does not match an authoritative revision'
                    USING ERRCODE = '23514';
            END IF;
        WHEN 'BUILD_ARTIFACT' THEN
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.build_artifact_edge_revision source_revision
                 WHERE source_revision.project_id = $1 AND source_revision.edge_id = $2
                   AND source_revision.revision = $3 AND source_revision.build_id = $4
                   AND source_revision.artifact_id = $5 AND source_revision.source_type = $6
                   AND source_revision.source_reference = $7 AND source_revision.confidence = $8
                   AND source_revision.verification_status = $9
                   AND source_revision.verified_at IS NOT DISTINCT FROM $10
                   AND source_revision.validator_version = $11
                   AND source_revision.reason IS NOT DISTINCT FROM $12
                   AND source_revision.evidence_id IS NOT DISTINCT FROM $13)',
                TG_TABLE_SCHEMA
            ) INTO source_matches
            USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.from_entity_id,
                NEW.to_entity_id, NEW.source_type, NEW.source_reference, NEW.confidence,
                NEW.verification_status, NEW.verified_at, NEW.validator_version, NEW.reason, NEW.evidence_id;
            IF NEW.from_entity_type <> 'BUILD' OR NEW.to_entity_type <> 'ARTIFACT' OR NOT source_matches THEN
                RAISE EXCEPTION 'snapshot BUILD_ARTIFACT edge does not match an authoritative revision'
                    USING ERRCODE = '23514';
            END IF;
        WHEN 'ARTIFACT_RELEASE' THEN
            EXECUTE format(
                'SELECT EXISTS (SELECT 1 FROM %I.artifact_release_edge_v authority_edge
                 WHERE authority_edge.project_id = $1 AND authority_edge.release_id = $2
                   AND authority_edge.artifact_id = $3 AND authority_edge.source_edge_id = $4
                   AND authority_edge.source_edge_revision = $5 AND authority_edge.source_type = $6
                   AND authority_edge.source_reference = $7 AND authority_edge.confidence = $8
                   AND authority_edge.verification_status = $9
                   AND authority_edge.verified_at IS NOT DISTINCT FROM $10
                   AND authority_edge.validator_version = $11
                   AND authority_edge.reason IS NOT DISTINCT FROM $12
                   AND authority_edge.evidence_id IS NOT DISTINCT FROM $13
                   AND authority_edge.fact_digest = $14 AND authority_edge.manifest_revision_id = $15
                   AND authority_edge.manifest_digest = $16 AND authority_edge.ordinal = $17
                   AND authority_edge.required = $18)',
                TG_TABLE_SCHEMA
            ) INTO source_matches
            USING NEW.project_id, NEW.to_entity_id, NEW.from_entity_id, NEW.source_edge_id,
                NEW.source_edge_revision, NEW.source_type, NEW.source_reference, NEW.confidence,
                NEW.verification_status, NEW.verified_at, NEW.validator_version, NEW.reason,
                NEW.evidence_id, NEW.fact_digest, NEW.manifest_revision_id, NEW.manifest_digest,
                NEW.manifest_artifact_ordinal, NEW.manifest_artifact_required;
            IF NEW.from_entity_type <> 'ARTIFACT'
                OR NEW.to_entity_type <> 'RELEASE'
                OR NEW.to_entity_id <> snapshot_release_id
                OR NOT source_matches THEN
                RAISE EXCEPTION 'snapshot ARTIFACT_RELEASE edge does not match locked Manifest authority'
                    USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'unsupported snapshot edge type %', NEW.edge_type USING ERRCODE = '23514';
    END CASE;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_release_artifact_snapshot_authority() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE invalid_snapshot_exists boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1
            FROM %I.traceability_snapshot_edge snapshot_edge
            JOIN %I.traceability_snapshot snapshot ON snapshot.id = snapshot_edge.snapshot_id
            WHERE snapshot.release_id = $1 AND snapshot.project_id = $2
              AND snapshot_edge.edge_type = ''ARTIFACT_RELEASE''
              AND (
                snapshot_edge.from_entity_type <> ''ARTIFACT''
                OR snapshot_edge.to_entity_type <> ''RELEASE''
                OR snapshot_edge.to_entity_id <> snapshot.release_id
                OR NOT EXISTS (
                  SELECT 1 FROM %I.artifact_release_edge_v authority_edge
                  WHERE authority_edge.project_id = snapshot_edge.project_id
                    AND authority_edge.release_id = snapshot_edge.to_entity_id
                    AND authority_edge.artifact_id = snapshot_edge.from_entity_id
                    AND authority_edge.source_edge_id = snapshot_edge.source_edge_id
                    AND authority_edge.source_edge_revision = snapshot_edge.source_edge_revision
                    AND authority_edge.source_type = snapshot_edge.source_type
                    AND authority_edge.source_reference = snapshot_edge.source_reference
                    AND authority_edge.confidence = snapshot_edge.confidence
                    AND authority_edge.verification_status = snapshot_edge.verification_status
                    AND authority_edge.verified_at IS NOT DISTINCT FROM snapshot_edge.verified_at
                    AND authority_edge.validator_version = snapshot_edge.validator_version
                    AND authority_edge.reason IS NOT DISTINCT FROM snapshot_edge.reason
                    AND authority_edge.evidence_id IS NOT DISTINCT FROM snapshot_edge.evidence_id
                    AND authority_edge.fact_digest = snapshot_edge.fact_digest
                    AND authority_edge.manifest_revision_id = snapshot_edge.manifest_revision_id
                    AND authority_edge.manifest_digest = snapshot_edge.manifest_digest
                    AND authority_edge.ordinal = snapshot_edge.manifest_artifact_ordinal
                    AND authority_edge.required = snapshot_edge.manifest_artifact_required
                )
              )
        )',
        TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA
    ) INTO invalid_snapshot_exists USING NEW.id, NEW.project_id;
    IF invalid_snapshot_exists THEN
        RAISE EXCEPTION 'release % locked Manifest cannot invalidate artifact snapshot authority', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER stable_issue_commit_edge_identity
    AFTER INSERT ON issue_commit_edge_revision DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION enforce_issue_commit_edge_identity();
CREATE CONSTRAINT TRIGGER stable_commit_build_edge_identity
    AFTER INSERT ON commit_build_edge_revision DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION enforce_commit_build_edge_identity();
CREATE CONSTRAINT TRIGGER stable_build_artifact_edge_identity
    AFTER INSERT ON build_artifact_edge_revision DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION enforce_build_artifact_edge_identity();
CREATE CONSTRAINT TRIGGER validate_traceability_snapshot_edge_source
    AFTER INSERT ON traceability_snapshot_edge DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_traceability_snapshot_edge_source();
CREATE CONSTRAINT TRIGGER validate_release_artifact_snapshot_authority
    AFTER UPDATE OF locked_manifest_id ON release_record DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_release_artifact_snapshot_authority();

CREATE TRIGGER atomic_release_issue_snapshot_item
    BEFORE INSERT ON release_issue_snapshot_item
    FOR EACH ROW EXECUTE FUNCTION enforce_snapshot_child_creation_transaction('release_issue_snapshot');
CREATE TRIGGER atomic_traceability_snapshot_edge
    BEFORE INSERT ON traceability_snapshot_edge
    FOR EACH ROW EXECUTE FUNCTION enforce_snapshot_child_creation_transaction('traceability_snapshot');
CREATE TRIGGER atomic_traceability_snapshot_gap
    BEFORE INSERT ON traceability_snapshot_gap
    FOR EACH ROW EXECUTE FUNCTION enforce_snapshot_child_creation_transaction('traceability_snapshot');
CREATE TRIGGER trusted_release_issue_snapshot_transaction
    BEFORE INSERT ON release_issue_snapshot
    FOR EACH ROW EXECUTE FUNCTION enforce_snapshot_header_creation_transaction();
CREATE TRIGGER trusted_traceability_snapshot_transaction
    BEFORE INSERT ON traceability_snapshot
    FOR EACH ROW EXECUTE FUNCTION enforce_snapshot_header_creation_transaction();

CREATE TRIGGER immutable_normalized_issue BEFORE UPDATE OR DELETE ON normalized_issue
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_release_issue_snapshot BEFORE UPDATE OR DELETE ON release_issue_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_release_issue_snapshot_item BEFORE UPDATE OR DELETE ON release_issue_snapshot_item
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_source_commit BEFORE UPDATE OR DELETE ON source_commit
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_build_record BEFORE UPDATE OR DELETE ON build_record
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_issue_commit_edge_revision BEFORE UPDATE OR DELETE ON issue_commit_edge_revision
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_commit_build_edge_revision BEFORE UPDATE OR DELETE ON commit_build_edge_revision
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_build_artifact_edge_revision BEFORE UPDATE OR DELETE ON build_artifact_edge_revision
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_traceability_gap BEFORE UPDATE OR DELETE ON traceability_gap
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_traceability_snapshot BEFORE UPDATE OR DELETE ON traceability_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_traceability_snapshot_edge BEFORE UPDATE OR DELETE ON traceability_snapshot_edge
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_traceability_snapshot_gap BEFORE UPDATE OR DELETE ON traceability_snapshot_gap
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE VIEW artifact_release_edge_v AS
SELECT rr.id AS release_id,
       rr.project_id,
       mr.id AS manifest_revision_id,
       mr.revision AS manifest_revision,
       mr.content_digest AS manifest_digest,
       ma.artifact_id,
       ma.required,
       ma.ordinal,
       substr(
           encode(sha256(convert_to(mr.id || chr(31) || ma.artifact_id, 'UTF8')), 'hex'),
           1,
           40
       )::varchar(40) AS source_edge_id,
       mr.revision AS source_edge_revision,
       'MANIFEST'::varchar(40) AS source_type,
       mr.id::varchar(512) AS source_reference,
       'HIGH'::varchar(20) AS confidence,
       'VALID'::varchar(20) AS verification_status,
       NULL::timestamptz AS verified_at,
       'artifact-release-manifest-v1'::varchar(80) AS validator_version,
       NULL::text AS reason,
       NULL::varchar(40) AS evidence_id,
       (
           'sha256:' || encode(
               sha256(
                   convert_to(
                       concat_ws(
                           chr(31),
                           'ARTIFACT_RELEASE', 'ARTIFACT', ma.artifact_id, 'RELEASE', rr.id,
                           mr.id, mr.revision::text, 'MANIFEST', mr.id, 'HIGH', 'VALID',
                           '<NULL>', 'artifact-release-manifest-v1', '<NULL>', '<NULL>',
                           mr.content_digest, ma.ordinal::text, ma.required::text
                       ),
                       'UTF8'
                   )
               ),
               'hex'
           )
       )::varchar(71) AS fact_digest
FROM release_record rr
JOIN manifest_revision mr ON mr.id = rr.locked_manifest_id
JOIN manifest_artifact ma ON ma.manifest_id = mr.id
WHERE mr.state = 'LOCKED';
