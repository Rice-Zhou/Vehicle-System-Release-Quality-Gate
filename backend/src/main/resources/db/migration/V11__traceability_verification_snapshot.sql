ALTER TABLE release_issue_snapshot
    ADD CONSTRAINT uq_issue_snapshot_id_release_project
        UNIQUE (id, release_id, project_id);

ALTER TABLE manifest_revision
    ADD CONSTRAINT uq_manifest_revision_id_release
        UNIQUE (id, release_id);

ALTER TABLE traceability_verification_run
    ADD COLUMN issue_snapshot_id varchar(40),
    ADD COLUMN manifest_revision_id varchar(40),
    ADD COLUMN validator_version varchar(80),
    ADD COLUMN input_digest varchar(71),
    ADD COLUMN result_snapshot_id varchar(40),
    ADD COLUMN requested_by varchar(40),
    ADD COLUMN request_id varchar(80),
    ADD CONSTRAINT uq_verification_run_id_project UNIQUE (id, project_id),
    ADD CONSTRAINT uq_verification_run_request UNIQUE (project_id, request_id),
    ADD CONSTRAINT ck_verification_run_input_digest CHECK (
        input_digest IS NULL OR input_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_verification_run_diagnostic_code CHECK (
        diagnostic_code IS NULL OR diagnostic_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
    ) NOT VALID,
    ADD CONSTRAINT ck_verification_run_v11_fixed_input CHECK (
        (
            issue_snapshot_id IS NULL
            AND manifest_revision_id IS NULL
            AND validator_version IS NULL
            AND input_digest IS NULL
            AND requested_by IS NULL
            AND request_id IS NULL
        )
        OR (
            issue_snapshot_id IS NOT NULL
            AND manifest_revision_id IS NOT NULL
            AND validator_version IS NOT NULL
            AND input_digest IS NOT NULL
            AND requested_by IS NOT NULL
            AND request_id IS NOT NULL
        )
    ),
    ADD CONSTRAINT fk_verification_run_issue_snapshot_release_project
        FOREIGN KEY (issue_snapshot_id, release_id, project_id)
        REFERENCES release_issue_snapshot(id, release_id, project_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_verification_run_manifest_release
        FOREIGN KEY (manifest_revision_id, release_id)
        REFERENCES manifest_revision(id, release_id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_verification_run_requested_by
        FOREIGN KEY (requested_by) REFERENCES principal(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_verification_run_result_snapshot_release_project
        FOREIGN KEY (result_snapshot_id, release_id, project_id)
        REFERENCES traceability_snapshot(id, release_id, project_id)
        ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE traceability_verification_run_edge_input (
    verification_run_id varchar(40) NOT NULL,
    ordinal integer NOT NULL,
    project_id varchar(40) NOT NULL,
    edge_type varchar(40) NOT NULL,
    source_edge_id varchar(40) NOT NULL,
    source_edge_revision integer NOT NULL,
    fact_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (verification_run_id, ordinal),
    CONSTRAINT uq_verification_input_source
        UNIQUE (verification_run_id, edge_type, source_edge_id, source_edge_revision),
    CONSTRAINT ck_verification_input_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_verification_input_edge_type CHECK (
        edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT', 'ARTIFACT_RELEASE')
    ),
    CONSTRAINT ck_verification_input_revision CHECK (source_edge_revision > 0),
    CONSTRAINT ck_verification_input_digest CHECK (fact_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT fk_verification_input_run_project
        FOREIGN KEY (verification_run_id, project_id)
        REFERENCES traceability_verification_run(id, project_id) ON DELETE RESTRICT
);

CREATE TABLE traceability_snapshot_issue_result (
    snapshot_id varchar(40) NOT NULL,
    ordinal integer NOT NULL,
    project_id varchar(40) NOT NULL,
    issue_id varchar(40) NOT NULL,
    source_issue_id varchar(255) NOT NULL,
    fixed boolean NOT NULL,
    included boolean NOT NULL,
    verified boolean NOT NULL DEFAULT false,
    result_digest varchar(71) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (snapshot_id, ordinal),
    CONSTRAINT uq_snapshot_issue_result_issue UNIQUE (snapshot_id, issue_id),
    CONSTRAINT uq_snapshot_issue_result_digest UNIQUE (snapshot_id, result_digest),
    CONSTRAINT ck_snapshot_issue_result_ordinal CHECK (ordinal >= 0),
    CONSTRAINT ck_snapshot_issue_result_verified CHECK (verified = false),
    CONSTRAINT ck_snapshot_issue_result_digest CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT fk_snapshot_issue_result_snapshot_project
        FOREIGN KEY (snapshot_id, project_id)
        REFERENCES traceability_snapshot(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_snapshot_issue_result_issue_project
        FOREIGN KEY (issue_id, project_id)
        REFERENCES normalized_issue(id, project_id) ON DELETE RESTRICT
);

CREATE TABLE traceability_snapshot_issue_path_edge (
    snapshot_id varchar(40) NOT NULL,
    issue_ordinal integer NOT NULL,
    path_ordinal integer NOT NULL,
    snapshot_edge_ordinal integer NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (snapshot_id, issue_ordinal, path_ordinal),
    CONSTRAINT uq_snapshot_issue_path_edge
        UNIQUE (snapshot_id, issue_ordinal, snapshot_edge_ordinal),
    CONSTRAINT ck_snapshot_issue_path_issue_ordinal CHECK (issue_ordinal >= 0),
    CONSTRAINT ck_snapshot_issue_path_ordinal CHECK (path_ordinal >= 0),
    CONSTRAINT ck_snapshot_issue_path_edge_ordinal CHECK (snapshot_edge_ordinal >= 0),
    CONSTRAINT fk_snapshot_issue_path_result
        FOREIGN KEY (snapshot_id, issue_ordinal)
        REFERENCES traceability_snapshot_issue_result(snapshot_id, ordinal) ON DELETE RESTRICT,
    CONSTRAINT fk_snapshot_issue_path_edge
        FOREIGN KEY (snapshot_id, snapshot_edge_ordinal)
        REFERENCES traceability_snapshot_edge(snapshot_id, ordinal) ON DELETE RESTRICT
);

ALTER TABLE traceability_gap
    ADD COLUMN break_entity_type varchar(40),
    ADD COLUMN break_entity_id varchar(40),
    ADD COLUMN predecessor_edge_type varchar(40),
    ADD COLUMN predecessor_edge_id varchar(40),
    ADD COLUMN predecessor_edge_revision integer,
    ADD CONSTRAINT ck_gap_diagnostic_v11 CHECK (
        diagnostic_code IN (
            'ISSUE_COMMIT_MISSING',
            'COMMIT_BUILD_MISSING',
            'BUILD_ARTIFACT_MISSING',
            'ARTIFACT_RELEASE_MISSING',
            'TEST_RESULT_EVIDENCE_MISSING'
        )
    ) NOT VALID,
    ADD CONSTRAINT ck_gap_break_entity_v11 CHECK (
        break_entity_type IS NOT NULL
        AND break_entity_type IN ('ISSUE', 'COMMIT', 'BUILD', 'ARTIFACT', 'RELEASE')
        AND break_entity_id IS NOT NULL
    ) NOT VALID,
    ADD CONSTRAINT ck_gap_predecessor_v11 CHECK (
        (
            predecessor_edge_type IS NULL
            AND predecessor_edge_id IS NULL
            AND predecessor_edge_revision IS NULL
        )
        OR (
            predecessor_edge_type IS NOT NULL
            AND predecessor_edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT', 'ARTIFACT_RELEASE')
            AND predecessor_edge_id IS NOT NULL
            AND predecessor_edge_revision IS NOT NULL
            AND predecessor_edge_revision > 0
        )
    ) NOT VALID;

ALTER TABLE traceability_snapshot_gap
    ADD COLUMN break_entity_type varchar(40),
    ADD COLUMN break_entity_id varchar(40),
    ADD COLUMN predecessor_edge_type varchar(40),
    ADD COLUMN predecessor_edge_id varchar(40),
    ADD COLUMN predecessor_edge_revision integer,
    ADD CONSTRAINT ck_snapshot_gap_diagnostic_v11 CHECK (
        diagnostic_code IN (
            'ISSUE_COMMIT_MISSING',
            'COMMIT_BUILD_MISSING',
            'BUILD_ARTIFACT_MISSING',
            'ARTIFACT_RELEASE_MISSING',
            'TEST_RESULT_EVIDENCE_MISSING'
        )
    ) NOT VALID,
    ADD CONSTRAINT ck_snapshot_gap_break_entity_v11 CHECK (
        break_entity_type IS NOT NULL
        AND break_entity_type IN ('ISSUE', 'COMMIT', 'BUILD', 'ARTIFACT', 'RELEASE')
        AND break_entity_id IS NOT NULL
    ) NOT VALID,
    ADD CONSTRAINT ck_snapshot_gap_predecessor_v11 CHECK (
        (
            predecessor_edge_type IS NULL
            AND predecessor_edge_id IS NULL
            AND predecessor_edge_revision IS NULL
        )
        OR (
            predecessor_edge_type IS NOT NULL
            AND predecessor_edge_type IN ('ISSUE_COMMIT', 'COMMIT_BUILD', 'BUILD_ARTIFACT', 'ARTIFACT_RELEASE')
            AND predecessor_edge_id IS NOT NULL
            AND predecessor_edge_revision IS NOT NULL
            AND predecessor_edge_revision > 0
        )
    ) NOT VALID;

CREATE FUNCTION validate_traceability_verification_run() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    old_is_v11 boolean;
    new_is_v11 boolean;
    result_matches_input boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
            RAISE EXCEPTION 'terminal traceability verification run is immutable' USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    new_is_v11 := NEW.issue_snapshot_id IS NOT NULL;

    IF TG_OP = 'INSERT' THEN
        IF NOT new_is_v11 THEN
            RETURN NEW;
        END IF;
        IF NEW.status <> 'QUEUED'
            OR NEW.started_at IS NOT NULL
            OR NEW.completed_at IS NOT NULL
            OR NEW.result_snapshot_id IS NOT NULL
            OR NEW.diagnostic_code IS NOT NULL THEN
            RAISE EXCEPTION 'new traceability verification run must start queued' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal traceability verification run is immutable' USING ERRCODE = '55000';
    END IF;

    old_is_v11 := OLD.issue_snapshot_id IS NOT NULL;
    IF old_is_v11 IS DISTINCT FROM new_is_v11
        OR OLD.issue_snapshot_id IS DISTINCT FROM NEW.issue_snapshot_id
        OR OLD.manifest_revision_id IS DISTINCT FROM NEW.manifest_revision_id
        OR OLD.validator_version IS DISTINCT FROM NEW.validator_version
        OR OLD.input_digest IS DISTINCT FROM NEW.input_digest
        OR OLD.requested_by IS DISTINCT FROM NEW.requested_by
        OR OLD.request_id IS DISTINCT FROM NEW.request_id THEN
        RAISE EXCEPTION 'traceability verification input identity is immutable' USING ERRCODE = '55000';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status
        AND NOT (
            (OLD.status = 'QUEUED' AND NEW.status = 'RUNNING')
            OR (OLD.status = 'RUNNING' AND NEW.status IN ('SUCCEEDED', 'FAILED'))
        ) THEN
        RAISE EXCEPTION 'illegal traceability verification status transition' USING ERRCODE = '23514';
    END IF;

    IF NOT new_is_v11 THEN
        RETURN NEW;
    END IF;

    IF NEW.status = 'QUEUED' AND (
        NEW.started_at IS NOT NULL
        OR NEW.completed_at IS NOT NULL
        OR NEW.result_snapshot_id IS NOT NULL
        OR NEW.diagnostic_code IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'queued traceability verification run has terminal fields' USING ERRCODE = '23514';
    ELSIF NEW.status = 'RUNNING' AND (
        NEW.started_at IS NULL
        OR NEW.completed_at IS NOT NULL
        OR NEW.result_snapshot_id IS NOT NULL
        OR NEW.diagnostic_code IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'running traceability verification run has invalid lifecycle fields' USING ERRCODE = '23514';
    ELSIF NEW.status = 'SUCCEEDED' AND (
        NEW.started_at IS NULL
        OR NEW.completed_at IS NULL
        OR NEW.result_snapshot_id IS NULL
        OR NEW.diagnostic_code IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'succeeded traceability verification run requires its result snapshot' USING ERRCODE = '23514';
    ELSIF NEW.status = 'FAILED' AND (
        NEW.started_at IS NULL
        OR NEW.completed_at IS NULL
        OR NEW.result_snapshot_id IS NOT NULL
        OR NEW.diagnostic_code IS NULL
    ) THEN
        RAISE EXCEPTION 'failed traceability verification run requires fixed diagnostic' USING ERRCODE = '23514';
    END IF;

    IF NEW.status = 'SUCCEEDED' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1
               FROM %I.traceability_snapshot snapshot
               JOIN %I.traceability_verification_run producer
                 ON producer.id = snapshot.verification_run_id
                AND producer.release_id = snapshot.release_id
                AND producer.project_id = snapshot.project_id
               WHERE snapshot.id = $1
                 AND snapshot.release_id = $2
                 AND snapshot.project_id = $3
                 AND producer.issue_snapshot_id = $4
                 AND producer.manifest_revision_id = $5
                 AND producer.policy_version = $6
                 AND producer.validator_version = $7
                 AND producer.input_digest = $8
                 AND (producer.id = $9 OR producer.status = ''SUCCEEDED'')
             )',
            TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO result_matches_input
        USING NEW.result_snapshot_id, NEW.release_id, NEW.project_id,
              NEW.issue_snapshot_id, NEW.manifest_revision_id, NEW.policy_version,
              NEW.validator_version, NEW.input_digest, NEW.id;
        IF NOT result_matches_input THEN
            RAISE EXCEPTION 'result snapshot does not match verification fixed input' USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_traceability_verification_run
    BEFORE INSERT OR UPDATE OR DELETE ON traceability_verification_run
    FOR EACH ROW EXECUTE FUNCTION validate_traceability_verification_run();

CREATE FUNCTION validate_verification_run_edge_input() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    parent_status varchar(20);
    parent_manifest_revision_id varchar(40);
    authoritative_input boolean := false;
BEGIN
    EXECUTE format(
        'SELECT verification_run.status, verification_run.manifest_revision_id
         FROM %I.traceability_verification_run verification_run
         WHERE verification_run.id = $1 AND verification_run.project_id = $2',
        TG_TABLE_SCHEMA
    ) INTO parent_status, parent_manifest_revision_id
    USING NEW.verification_run_id, NEW.project_id;

    IF parent_status IS DISTINCT FROM 'QUEUED' OR parent_manifest_revision_id IS NULL THEN
        RAISE EXCEPTION 'fixed input requires an authoritative queued verification run' USING ERRCODE = '23514';
    END IF;

    IF NEW.edge_type = 'ISSUE_COMMIT' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.issue_commit_edge_revision edge
               WHERE edge.project_id = $1 AND edge.edge_id = $2 AND edge.revision = $3
                 AND edge.content_digest = $4 AND edge.verification_status = ''VALID''
             )',
            TG_TABLE_SCHEMA
        ) INTO authoritative_input
        USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.fact_digest;
    ELSIF NEW.edge_type = 'COMMIT_BUILD' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.commit_build_edge_revision edge
               WHERE edge.project_id = $1 AND edge.edge_id = $2 AND edge.revision = $3
                 AND edge.content_digest = $4 AND edge.verification_status = ''VALID''
             )',
            TG_TABLE_SCHEMA
        ) INTO authoritative_input
        USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.fact_digest;
    ELSIF NEW.edge_type = 'BUILD_ARTIFACT' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.build_artifact_edge_revision edge
               WHERE edge.project_id = $1 AND edge.edge_id = $2 AND edge.revision = $3
                 AND edge.content_digest = $4 AND edge.verification_status = ''VALID''
             )',
            TG_TABLE_SCHEMA
        ) INTO authoritative_input
        USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.fact_digest;
    ELSIF NEW.edge_type = 'ARTIFACT_RELEASE' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.artifact_release_edge_v edge
               WHERE edge.project_id = $1 AND edge.source_edge_id = $2
                 AND edge.source_edge_revision = $3 AND edge.fact_digest = $4
                 AND edge.verification_status = ''VALID''
                 AND edge.manifest_revision_id = $5
             )',
            TG_TABLE_SCHEMA
        ) INTO authoritative_input
        USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision,
              NEW.fact_digest, parent_manifest_revision_id;
    END IF;

    IF NOT authoritative_input THEN
        RAISE EXCEPTION 'verification input is not an authoritative VALID edge revision' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_verification_run_edge_input
    BEFORE INSERT ON traceability_verification_run_edge_input
    FOR EACH ROW EXECUTE FUNCTION validate_verification_run_edge_input();

CREATE TRIGGER immutable_verification_run_edge_input
    BEFORE UPDATE OR DELETE ON traceability_verification_run_edge_input
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE FUNCTION validate_snapshot_issue_result() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    authoritative_issue boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
           SELECT 1
           FROM %I.traceability_snapshot snapshot
           JOIN %I.traceability_verification_run verification_run
             ON verification_run.id = snapshot.verification_run_id
            AND verification_run.release_id = snapshot.release_id
            AND verification_run.project_id = snapshot.project_id
           JOIN %I.release_issue_snapshot_item issue_item
             ON issue_item.snapshot_id = verification_run.issue_snapshot_id
            AND issue_item.project_id = snapshot.project_id
            AND issue_item.issue_id = $3
            AND issue_item.source_issue_id = $4
           WHERE snapshot.id = $1 AND snapshot.project_id = $2
             AND snapshot.creation_transaction_id = pg_catalog.pg_current_xact_id()::text::bigint
             AND verification_run.status = ''RUNNING''
         )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO authoritative_issue
    USING NEW.snapshot_id, NEW.project_id, NEW.issue_id, NEW.source_issue_id;

    IF NOT authoritative_issue THEN
        RAISE EXCEPTION 'snapshot issue result is outside the fixed issue input' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_snapshot_issue_result
    BEFORE INSERT ON traceability_snapshot_issue_result
    FOR EACH ROW EXECUTE FUNCTION validate_snapshot_issue_result();

CREATE TRIGGER immutable_snapshot_issue_result
    BEFORE UPDATE OR DELETE ON traceability_snapshot_issue_result
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE FUNCTION validate_snapshot_issue_path_edge() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    authoritative_path_edge boolean;
BEGIN
    EXECUTE format(
        'SELECT EXISTS (
           SELECT 1
           FROM %I.traceability_snapshot_issue_result issue_result
           JOIN %I.traceability_snapshot snapshot ON snapshot.id = issue_result.snapshot_id
           JOIN %I.traceability_verification_run verification_run
             ON verification_run.id = snapshot.verification_run_id
            AND verification_run.release_id = snapshot.release_id
            AND verification_run.project_id = snapshot.project_id
           JOIN %I.traceability_snapshot_edge snapshot_edge
             ON snapshot_edge.snapshot_id = issue_result.snapshot_id
            AND snapshot_edge.ordinal = $3
           JOIN %I.traceability_verification_run_edge_input edge_input
             ON edge_input.verification_run_id = verification_run.id
            AND edge_input.project_id = snapshot.project_id
            AND edge_input.edge_type = snapshot_edge.edge_type
            AND edge_input.source_edge_id = snapshot_edge.source_edge_id
            AND edge_input.source_edge_revision = snapshot_edge.source_edge_revision
            AND edge_input.fact_digest = snapshot_edge.fact_digest
           WHERE issue_result.snapshot_id = $1 AND issue_result.ordinal = $2
             AND snapshot.creation_transaction_id = pg_catalog.pg_current_xact_id()::text::bigint
             AND verification_run.status = ''RUNNING''
         )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO authoritative_path_edge
    USING NEW.snapshot_id, NEW.issue_ordinal, NEW.snapshot_edge_ordinal;

    IF NOT authoritative_path_edge THEN
        RAISE EXCEPTION 'snapshot issue path edge is outside the fixed edge input' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_snapshot_issue_path_edge
    BEFORE INSERT ON traceability_snapshot_issue_path_edge
    FOR EACH ROW EXECUTE FUNCTION validate_snapshot_issue_path_edge();

CREATE TRIGGER immutable_snapshot_issue_path_edge
    BEFORE UPDATE OR DELETE ON traceability_snapshot_issue_path_edge
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE FUNCTION validate_traceability_gap_break() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    authoritative_predecessor boolean;
BEGIN
    IF NEW.predecessor_edge_id IS NULL THEN
        RETURN NEW;
    END IF;
    EXECUTE format(
        'SELECT EXISTS (
           SELECT 1 FROM %I.traceability_verification_run_edge_input edge_input
           WHERE edge_input.verification_run_id = $1
             AND edge_input.project_id = $2
             AND edge_input.edge_type = $3
             AND edge_input.source_edge_id = $4
             AND edge_input.source_edge_revision = $5
         )',
        TG_TABLE_SCHEMA
    ) INTO authoritative_predecessor
    USING NEW.verification_run_id, NEW.project_id, NEW.predecessor_edge_type,
          NEW.predecessor_edge_id, NEW.predecessor_edge_revision;
    IF NOT authoritative_predecessor THEN
        RAISE EXCEPTION 'gap predecessor is outside the fixed edge input' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_traceability_gap_break
    BEFORE INSERT ON traceability_gap
    FOR EACH ROW EXECUTE FUNCTION validate_traceability_gap_break();

CREATE FUNCTION validate_traceability_snapshot_gap_break() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    authoritative_predecessor boolean;
BEGIN
    IF NEW.predecessor_edge_id IS NULL THEN
        RETURN NEW;
    END IF;
    EXECUTE format(
        'SELECT EXISTS (
           SELECT 1
           FROM %I.traceability_snapshot snapshot
           JOIN %I.traceability_snapshot_edge snapshot_edge
             ON snapshot_edge.snapshot_id = snapshot.id
            AND snapshot_edge.edge_type = $2
            AND snapshot_edge.source_edge_id = $3
            AND snapshot_edge.source_edge_revision = $4
           JOIN %I.traceability_verification_run_edge_input edge_input
             ON edge_input.verification_run_id = snapshot.verification_run_id
            AND edge_input.project_id = snapshot.project_id
            AND edge_input.edge_type = snapshot_edge.edge_type
            AND edge_input.source_edge_id = snapshot_edge.source_edge_id
            AND edge_input.source_edge_revision = snapshot_edge.source_edge_revision
            AND edge_input.fact_digest = snapshot_edge.fact_digest
           WHERE snapshot.id = $1
         )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO authoritative_predecessor
    USING NEW.snapshot_id, NEW.predecessor_edge_type,
          NEW.predecessor_edge_id, NEW.predecessor_edge_revision;
    IF NOT authoritative_predecessor THEN
        RAISE EXCEPTION 'snapshot gap predecessor is outside the fixed edge input' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_traceability_snapshot_gap_break
    BEFORE INSERT ON traceability_snapshot_gap
    FOR EACH ROW EXECUTE FUNCTION validate_traceability_snapshot_gap_break();

CREATE INDEX ix_verification_run_input_digest
    ON traceability_verification_run(input_digest)
    WHERE input_digest IS NOT NULL;
CREATE INDEX ix_verification_run_result_snapshot
    ON traceability_verification_run(result_snapshot_id)
    WHERE result_snapshot_id IS NOT NULL;
CREATE INDEX ix_verification_run_dispatch
    ON traceability_verification_run(status, created_at)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_snapshot_issue_result_issue
    ON traceability_snapshot_issue_result(issue_id, snapshot_id);
