DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM build_record
        GROUP BY project_id, provider, pipeline, build_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'BUILD_AUTHORITY_PRECONDITION_FAILED' USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        WITH legacy_edges AS (
            SELECT edge_id, project_id, 'ISSUE_COMMIT'::varchar(40) AS edge_type,
                   issue_id AS from_entity_id, commit_id AS to_entity_id
            FROM issue_commit_edge_revision
            UNION ALL
            SELECT edge_id, project_id, 'COMMIT_BUILD'::varchar(40), commit_id, build_id
            FROM commit_build_edge_revision
            UNION ALL
            SELECT edge_id, project_id, 'BUILD_ARTIFACT'::varchar(40), build_id, artifact_id
            FROM build_artifact_edge_revision
        )
        SELECT 1
        FROM legacy_edges
        GROUP BY edge_id
        HAVING count(DISTINCT ROW(project_id, edge_type, from_entity_id, to_entity_id)) > 1
    ) OR EXISTS (
        WITH legacy_edges AS (
            SELECT edge_id, project_id, 'ISSUE_COMMIT'::varchar(40) AS edge_type,
                   issue_id AS from_entity_id, commit_id AS to_entity_id
            FROM issue_commit_edge_revision
            UNION ALL
            SELECT edge_id, project_id, 'COMMIT_BUILD'::varchar(40), commit_id, build_id
            FROM commit_build_edge_revision
            UNION ALL
            SELECT edge_id, project_id, 'BUILD_ARTIFACT'::varchar(40), build_id, artifact_id
            FROM build_artifact_edge_revision
        )
        SELECT 1
        FROM legacy_edges
        GROUP BY project_id, edge_type, from_entity_id, to_entity_id
        HAVING count(DISTINCT edge_id) > 1
    ) THEN
        RAISE EXCEPTION 'TRACEABILITY_EDGE_PRECONDITION_FAILED' USING ERRCODE = '23514';
    END IF;
END;
$$;

CREATE TABLE traceability_edge_identity (
    edge_id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    edge_type varchar(40) NOT NULL CHECK (edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT')),
    from_entity_id varchar(40) NOT NULL,
    to_entity_id varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_traceability_edge_project FOREIGN KEY (project_id)
        REFERENCES project(id) ON DELETE RESTRICT,
    UNIQUE (edge_id, project_id),
    UNIQUE (project_id, edge_type, from_entity_id, to_entity_id)
);

INSERT INTO traceability_edge_identity(
    edge_id, project_id, edge_type, from_entity_id, to_entity_id, created_at
)
SELECT edge_id, project_id, edge_type, from_entity_id, to_entity_id, min(created_at)
FROM (
    SELECT edge_id, project_id, 'ISSUE_COMMIT'::varchar(40) AS edge_type,
           issue_id AS from_entity_id, commit_id AS to_entity_id, created_at
    FROM issue_commit_edge_revision
    UNION ALL
    SELECT edge_id, project_id, 'COMMIT_BUILD'::varchar(40), commit_id, build_id, created_at
    FROM commit_build_edge_revision
    UNION ALL
    SELECT edge_id, project_id, 'BUILD_ARTIFACT'::varchar(40), build_id, artifact_id, created_at
    FROM build_artifact_edge_revision
) legacy_edges
GROUP BY edge_id, project_id, edge_type, from_entity_id, to_entity_id;

ALTER TABLE build_record
    ADD COLUMN repository varchar(512),
    ADD COLUMN build_attempt integer,
    ADD CONSTRAINT ck_build_record_v2_authority CHECK (
        (repository IS NULL AND build_attempt IS NULL)
        OR (repository IS NOT NULL AND build_attempt >= 1)
    );

ALTER TABLE build_record DROP CONSTRAINT uq_build_record_identity;

CREATE UNIQUE INDEX uq_build_record_attempt_authority
    ON build_record(project_id, provider, pipeline, build_id, build_attempt)
    WHERE repository IS NOT NULL AND build_attempt IS NOT NULL;

CREATE TABLE build_provenance_receipt (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    provider varchar(40) NOT NULL,
    pipeline varchar(255) NOT NULL,
    provider_build_id varchar(255) NOT NULL,
    build_attempt integer NOT NULL CHECK (build_attempt >= 1),
    envelope_digest varchar(71) NOT NULL CHECK (envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    release_issue_snapshot_id varchar(40) NOT NULL,
    source_commit_id varchar(40) NOT NULL,
    build_record_id varchar(40) NOT NULL,
    validator_version varchar(80) NOT NULL,
    verification_status varchar(20) NOT NULL
        CHECK (verification_status IN ('VALID', 'INVALID', 'CONFLICT', 'ERROR')),
    confidence varchar(20) NOT NULL CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNKNOWN')),
    issue_count integer NOT NULL CHECK (issue_count BETWEEN 1 AND 20),
    artifact_count integer NOT NULL CHECK (artifact_count BETWEEN 1 AND 20),
    edge_count integer NOT NULL CHECK (edge_count BETWEEN 3 AND 100),
    response_body jsonb NOT NULL,
    actor_id varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (project_id, provider, pipeline, provider_build_id, build_attempt),
    CONSTRAINT fk_build_provenance_receipt_project FOREIGN KEY (project_id)
        REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_provenance_receipt_actor FOREIGN KEY (actor_id)
        REFERENCES principal(id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_provenance_receipt_snapshot_project
        FOREIGN KEY (release_issue_snapshot_id, project_id)
        REFERENCES release_issue_snapshot(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_provenance_receipt_commit_project
        FOREIGN KEY (source_commit_id, project_id)
        REFERENCES source_commit(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_provenance_receipt_build_project
        FOREIGN KEY (build_record_id, project_id)
        REFERENCES build_record(id, project_id) ON DELETE RESTRICT
);

CREATE TABLE build_provenance_rejected_receipt (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    accepted_receipt_id varchar(40) NOT NULL,
    rejected_envelope_digest varchar(71) NOT NULL
        CHECK (rejected_envelope_digest ~ '^sha256:[0-9a-f]{64}$'),
    diagnostic_code varchar(80) NOT NULL CHECK (diagnostic_code = 'BUILD_PROVENANCE_CONFLICT'),
    actor_id varchar(40) NOT NULL,
    attempted_at timestamptz NOT NULL,
    UNIQUE (accepted_receipt_id, rejected_envelope_digest),
    CONSTRAINT fk_build_provenance_rejected_project FOREIGN KEY (project_id)
        REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_provenance_rejected_receipt FOREIGN KEY (accepted_receipt_id)
        REFERENCES build_provenance_receipt(id) ON DELETE RESTRICT,
    CONSTRAINT fk_build_provenance_rejected_actor FOREIGN KEY (actor_id)
        REFERENCES principal(id) ON DELETE RESTRICT
);

ALTER TABLE issue_commit_edge_revision
    ADD COLUMN proof_reference varchar(512),
    ADD COLUMN proof_digest varchar(71),
    ADD COLUMN reason_code varchar(80),
    ADD CONSTRAINT ck_issue_commit_proof_digest
        CHECK (proof_digest IS NULL OR proof_digest ~ '^sha256:[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_issue_commit_github_proof CHECK (
        validator_version <> 'github-actions-provenance/v1'
        OR (proof_reference IS NOT NULL AND proof_digest IS NOT NULL AND reason_code IS NOT NULL)
    ),
    ADD CONSTRAINT fk_issue_commit_edge_header_project
        FOREIGN KEY (edge_id, project_id)
        REFERENCES traceability_edge_identity(edge_id, project_id) ON DELETE RESTRICT;

ALTER TABLE commit_build_edge_revision
    ADD COLUMN proof_reference varchar(512),
    ADD COLUMN proof_digest varchar(71),
    ADD COLUMN reason_code varchar(80),
    ADD CONSTRAINT ck_commit_build_proof_digest
        CHECK (proof_digest IS NULL OR proof_digest ~ '^sha256:[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_commit_build_github_proof CHECK (
        validator_version <> 'github-actions-provenance/v1'
        OR (proof_reference IS NOT NULL AND proof_digest IS NOT NULL AND reason_code IS NOT NULL)
    ),
    ADD CONSTRAINT fk_commit_build_edge_header_project
        FOREIGN KEY (edge_id, project_id)
        REFERENCES traceability_edge_identity(edge_id, project_id) ON DELETE RESTRICT;

ALTER TABLE build_artifact_edge_revision
    ADD COLUMN proof_reference varchar(512),
    ADD COLUMN proof_digest varchar(71),
    ADD COLUMN reason_code varchar(80),
    ADD CONSTRAINT ck_build_artifact_proof_digest
        CHECK (proof_digest IS NULL OR proof_digest ~ '^sha256:[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_build_artifact_github_proof CHECK (
        validator_version <> 'github-actions-provenance/v1'
        OR (proof_reference IS NOT NULL AND proof_digest IS NOT NULL AND reason_code IS NOT NULL)
    ),
    ADD CONSTRAINT fk_build_artifact_edge_header_project
        FOREIGN KEY (edge_id, project_id)
        REFERENCES traceability_edge_identity(edge_id, project_id) ON DELETE RESTRICT;

CREATE OR REPLACE FUNCTION enforce_issue_commit_edge_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE identity_changed boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.issue_commit_edge_revision existing
            WHERE existing.edge_id = $1 AND existing.id <> $2
              AND (existing.project_id IS DISTINCT FROM $3 OR existing.issue_id IS DISTINCT FROM $4
                   OR existing.commit_id IS DISTINCT FROM $5)
        )',
        TG_TABLE_SCHEMA
    ) INTO identity_changed
    USING NEW.edge_id, NEW.id, NEW.project_id, NEW.issue_id, NEW.commit_id;
    IF identity_changed THEN
        RAISE EXCEPTION 'issue_commit edge cannot change project or endpoints' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_commit_build_edge_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE identity_changed boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.commit_build_edge_revision existing
            WHERE existing.edge_id = $1 AND existing.id <> $2
              AND (existing.project_id IS DISTINCT FROM $3 OR existing.commit_id IS DISTINCT FROM $4
                   OR existing.build_id IS DISTINCT FROM $5)
        )',
        TG_TABLE_SCHEMA
    ) INTO identity_changed
    USING NEW.edge_id, NEW.id, NEW.project_id, NEW.commit_id, NEW.build_id;
    IF identity_changed THEN
        RAISE EXCEPTION 'commit_build edge cannot change project or endpoints' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_build_artifact_edge_identity() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE identity_changed boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.build_artifact_edge_revision existing
            WHERE existing.edge_id = $1 AND existing.id <> $2
              AND (existing.project_id IS DISTINCT FROM $3 OR existing.build_id IS DISTINCT FROM $4
                   OR existing.artifact_id IS DISTINCT FROM $5)
        )',
        TG_TABLE_SCHEMA
    ) INTO identity_changed
    USING NEW.edge_id, NEW.id, NEW.project_id, NEW.build_id, NEW.artifact_id;
    IF identity_changed THEN
        RAISE EXCEPTION 'build_artifact edge cannot change project or endpoints' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_issue_commit_edge_header() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE header_matches boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.traceability_edge_identity header
            WHERE header.edge_id = $1 AND header.project_id = $2
              AND header.edge_type = ''ISSUE_COMMIT''
              AND header.from_entity_id = $3 AND header.to_entity_id = $4
        )',
        TG_TABLE_SCHEMA
    ) INTO header_matches USING NEW.edge_id, NEW.project_id, NEW.issue_id, NEW.commit_id;
    IF NOT header_matches THEN
        RAISE EXCEPTION 'TYPED_EDGE_HEADER_MISMATCH' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_commit_build_edge_header() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE header_matches boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.traceability_edge_identity header
            WHERE header.edge_id = $1 AND header.project_id = $2
              AND header.edge_type = ''COMMIT_BUILD''
              AND header.from_entity_id = $3 AND header.to_entity_id = $4
        )',
        TG_TABLE_SCHEMA
    ) INTO header_matches USING NEW.edge_id, NEW.project_id, NEW.commit_id, NEW.build_id;
    IF NOT header_matches THEN
        RAISE EXCEPTION 'TYPED_EDGE_HEADER_MISMATCH' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION validate_build_artifact_edge_header() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE header_matches boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
            SELECT 1 FROM %I.traceability_edge_identity header
            WHERE header.edge_id = $1 AND header.project_id = $2
              AND header.edge_type = ''BUILD_ARTIFACT''
              AND header.from_entity_id = $3 AND header.to_entity_id = $4
        )',
        TG_TABLE_SCHEMA
    ) INTO header_matches USING NEW.edge_id, NEW.project_id, NEW.build_id, NEW.artifact_id;
    IF NOT header_matches THEN
        RAISE EXCEPTION 'TYPED_EDGE_HEADER_MISMATCH' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER valid_issue_commit_edge_header
    AFTER INSERT ON issue_commit_edge_revision DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_issue_commit_edge_header();

CREATE CONSTRAINT TRIGGER valid_commit_build_edge_header
    AFTER INSERT ON commit_build_edge_revision DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_commit_build_edge_header();

CREATE CONSTRAINT TRIGGER valid_build_artifact_edge_header
    AFTER INSERT ON build_artifact_edge_revision DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_build_artifact_edge_header();

CREATE TRIGGER immutable_traceability_edge_identity
    BEFORE UPDATE OR DELETE ON traceability_edge_identity
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE TRIGGER immutable_build_provenance_receipt
    BEFORE UPDATE OR DELETE ON build_provenance_receipt
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE TRIGGER immutable_build_provenance_rejected_receipt
    BEFORE UPDATE OR DELETE ON build_provenance_rejected_receipt
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
