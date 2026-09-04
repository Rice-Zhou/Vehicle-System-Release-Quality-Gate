ALTER TABLE release_issue_snapshot
    ADD CONSTRAINT uq_issue_snapshot_id_release_project UNIQUE (id, release_id, project_id);

ALTER TABLE manifest_revision
    ADD CONSTRAINT uq_manifest_revision_id_release UNIQUE (id, release_id);

ALTER TABLE traceability_verification_run
    ADD COLUMN issue_snapshot_id varchar(40),
    ADD COLUMN manifest_revision_id varchar(40),
    ADD COLUMN validator_version varchar(80),
    ADD COLUMN input_digest varchar(71),
    ADD COLUMN result_snapshot_id varchar(40),
    ADD COLUMN requested_by varchar(40),
    ADD COLUMN request_id varchar(80),
    ADD COLUMN creation_transaction_id bigint NOT NULL
        DEFAULT (pg_catalog.pg_current_xact_id()::text::bigint),
    ADD COLUMN input_edge_count integer,
    ADD CONSTRAINT uq_verification_run_id_project UNIQUE (id, project_id),
    ADD CONSTRAINT uq_verification_run_request UNIQUE (project_id, request_id),
    ADD CONSTRAINT ck_verification_run_input_digest CHECK (
        input_digest IS NULL OR input_digest ~ '^sha256:[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT ck_verification_run_input_edge_count CHECK (
        input_edge_count IS NULL OR input_edge_count >= 0
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
            AND input_edge_count IS NULL
        )
        OR (
            issue_snapshot_id IS NOT NULL
            AND manifest_revision_id IS NOT NULL
            AND validator_version IS NOT NULL
            AND input_digest IS NOT NULL
            AND requested_by IS NOT NULL
            AND request_id IS NOT NULL
            AND input_edge_count IS NOT NULL
        )
    ),
    ADD CONSTRAINT ck_verification_run_m25_policy_shape CHECK (
        policy_version NOT LIKE 'm2.5-traceability-policy/%'
        OR issue_snapshot_id IS NOT NULL
    ) NOT VALID,
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
    CONSTRAINT ck_snapshot_issue_result_flags CHECK (NOT included OR fixed),
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
    CONSTRAINT uq_snapshot_issue_path_edge UNIQUE (snapshot_id, issue_ordinal, snapshot_edge_ordinal),
    CONSTRAINT ck_snapshot_issue_path_issue_ordinal CHECK (issue_ordinal >= 0),
    CONSTRAINT ck_snapshot_issue_path_ordinal CHECK (path_ordinal BETWEEN 0 AND 3),
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
    ADD COLUMN predecessor_edge_revision integer;

ALTER TABLE traceability_snapshot_gap
    ADD COLUMN break_entity_type varchar(40),
    ADD COLUMN break_entity_id varchar(40),
    ADD COLUMN predecessor_edge_type varchar(40),
    ADD COLUMN predecessor_edge_id varchar(40),
    ADD COLUMN predecessor_edge_revision integer;

CREATE FUNCTION lock_traceability_edge_authority() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(
            NEW.project_id || chr(31) || TG_ARGV[0] || chr(31) || NEW.edge_id,
            0
        )
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER lock_issue_commit_edge_authority
    BEFORE INSERT ON issue_commit_edge_revision
    FOR EACH ROW EXECUTE FUNCTION lock_traceability_edge_authority('ISSUE_COMMIT');
CREATE TRIGGER lock_commit_build_edge_authority
    BEFORE INSERT ON commit_build_edge_revision
    FOR EACH ROW EXECUTE FUNCTION lock_traceability_edge_authority('COMMIT_BUILD');
CREATE TRIGGER lock_build_artifact_edge_authority
    BEFORE INSERT ON build_artifact_edge_revision
    FOR EACH ROW EXECUTE FUNCTION lock_traceability_edge_authority('BUILD_ARTIFACT');

CREATE FUNCTION validate_traceability_verification_run() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    old_is_v11 boolean;
    new_is_v11 boolean;
    fixed_edge_count integer;
    first_edge_ordinal integer;
    last_edge_ordinal integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
            RAISE EXCEPTION 'terminal traceability verification run is immutable' USING ERRCODE = '55000';
        END IF;
        RETURN OLD;
    END IF;

    new_is_v11 := NEW.issue_snapshot_id IS NOT NULL
        OR NEW.policy_version LIKE 'm2.5-traceability-policy/%';
    IF TG_OP = 'INSERT' THEN
        IF NOT new_is_v11 THEN
            RETURN NEW;
        END IF;
        IF NEW.creation_transaction_id IS DISTINCT FROM pg_catalog.pg_current_xact_id()::text::bigint
            OR NEW.status <> 'QUEUED'
            OR NEW.started_at IS NOT NULL
            OR NEW.completed_at IS NOT NULL
            OR NEW.result_snapshot_id IS NOT NULL
            OR NEW.diagnostic_code IS NOT NULL THEN
            RAISE EXCEPTION 'new traceability verification run must start queued in its creation transaction'
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'terminal traceability verification run is immutable' USING ERRCODE = '55000';
    END IF;

    old_is_v11 := OLD.issue_snapshot_id IS NOT NULL
        OR OLD.policy_version LIKE 'm2.5-traceability-policy/%';
    IF old_is_v11 IS DISTINCT FROM new_is_v11 THEN
        RAISE EXCEPTION 'traceability verification input identity is immutable' USING ERRCODE = '55000';
    END IF;
    IF NOT new_is_v11 THEN
        RETURN NEW;
    END IF;
    IF OLD.issue_snapshot_id IS DISTINCT FROM NEW.issue_snapshot_id
        OR OLD.manifest_revision_id IS DISTINCT FROM NEW.manifest_revision_id
        OR OLD.policy_version IS DISTINCT FROM NEW.policy_version
        OR OLD.validator_version IS DISTINCT FROM NEW.validator_version
        OR OLD.input_digest IS DISTINCT FROM NEW.input_digest
        OR OLD.input_edge_count IS DISTINCT FROM NEW.input_edge_count
        OR OLD.creation_transaction_id IS DISTINCT FROM NEW.creation_transaction_id
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

    IF OLD.status = 'QUEUED' AND NEW.status = 'RUNNING' THEN
        EXECUTE format(
            'SELECT count(*), min(ordinal), max(ordinal)
             FROM %I.traceability_verification_run_edge_input WHERE verification_run_id = $1',
            TG_TABLE_SCHEMA
        ) INTO fixed_edge_count, first_edge_ordinal, last_edge_ordinal USING NEW.id;
        IF fixed_edge_count IS DISTINCT FROM NEW.input_edge_count
            OR (fixed_edge_count > 0 AND (
                first_edge_ordinal IS DISTINCT FROM 0
                OR last_edge_ordinal IS DISTINCT FROM fixed_edge_count - 1
            )) THEN
            RAISE EXCEPTION 'verification fixed input ledger is not sealed to its declared count and ordinal range'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.status = 'QUEUED' AND (
        NEW.started_at IS NOT NULL OR NEW.completed_at IS NOT NULL
        OR NEW.result_snapshot_id IS NOT NULL OR NEW.diagnostic_code IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'queued traceability verification run has terminal fields' USING ERRCODE = '23514';
    ELSIF NEW.status = 'RUNNING' AND (
        NEW.started_at IS NULL OR NEW.completed_at IS NOT NULL
        OR NEW.result_snapshot_id IS NOT NULL OR NEW.diagnostic_code IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'running traceability verification run has invalid lifecycle fields' USING ERRCODE = '23514';
    ELSIF NEW.status = 'SUCCEEDED' AND (
        NEW.started_at IS NULL OR NEW.completed_at IS NULL
        OR NEW.result_snapshot_id IS NULL OR NEW.diagnostic_code IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'succeeded traceability verification run requires its result snapshot' USING ERRCODE = '23514';
    ELSIF NEW.status = 'FAILED' AND (
        NEW.started_at IS NULL OR NEW.completed_at IS NULL
        OR NEW.result_snapshot_id IS NOT NULL OR NEW.diagnostic_code IS NULL
    ) THEN
        RAISE EXCEPTION 'failed traceability verification run requires fixed diagnostic' USING ERRCODE = '23514';
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
    parent_creation_transaction_id bigint;
    authoritative_input boolean := false;
BEGIN
    EXECUTE format(
        'SELECT verification_run.status, verification_run.manifest_revision_id,
                verification_run.creation_transaction_id
         FROM %I.traceability_verification_run verification_run
         WHERE verification_run.id = $1 AND verification_run.project_id = $2',
        TG_TABLE_SCHEMA
    ) INTO parent_status, parent_manifest_revision_id, parent_creation_transaction_id
    USING NEW.verification_run_id, NEW.project_id;

    IF parent_status IS DISTINCT FROM 'QUEUED'
        OR parent_manifest_revision_id IS NULL
        OR parent_creation_transaction_id IS DISTINCT FROM pg_catalog.pg_current_xact_id()::text::bigint THEN
        RAISE EXCEPTION 'fixed input must be written in its queued run creation transaction'
            USING ERRCODE = '23514';
    END IF;

    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(
            NEW.project_id || chr(31) || NEW.edge_type || chr(31) || NEW.source_edge_id,
            0
        )
    );

    IF NEW.edge_type = 'ISSUE_COMMIT' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.issue_commit_edge_revision edge
               WHERE edge.project_id = $1 AND edge.edge_id = $2 AND edge.revision = $3
                 AND edge.content_digest = $4 AND edge.verification_status = ''VALID''
                 AND edge.revision = (
                   SELECT max(current_edge.revision) FROM %I.issue_commit_edge_revision current_edge
                   WHERE current_edge.project_id = $1 AND current_edge.edge_id = $2
                 )
             )',
            TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO authoritative_input
        USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.fact_digest;
    ELSIF NEW.edge_type = 'COMMIT_BUILD' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.commit_build_edge_revision edge
               WHERE edge.project_id = $1 AND edge.edge_id = $2 AND edge.revision = $3
                 AND edge.content_digest = $4 AND edge.verification_status = ''VALID''
                 AND edge.revision = (
                   SELECT max(current_edge.revision) FROM %I.commit_build_edge_revision current_edge
                   WHERE current_edge.project_id = $1 AND current_edge.edge_id = $2
                 )
             )',
            TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO authoritative_input
        USING NEW.project_id, NEW.source_edge_id, NEW.source_edge_revision, NEW.fact_digest;
    ELSIF NEW.edge_type = 'BUILD_ARTIFACT' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.build_artifact_edge_revision edge
               WHERE edge.project_id = $1 AND edge.edge_id = $2 AND edge.revision = $3
                 AND edge.content_digest = $4 AND edge.verification_status = ''VALID''
                 AND edge.revision = (
                   SELECT max(current_edge.revision) FROM %I.build_artifact_edge_revision current_edge
                   WHERE current_edge.project_id = $1 AND current_edge.edge_id = $2
                 )
             )',
            TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
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
        RAISE EXCEPTION 'verification input is not the current authoritative VALID edge revision'
            USING ERRCODE = '23514';
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

CREATE FUNCTION validate_snapshot_edge_fixed_input() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    producer_is_v11 boolean;
    input_matches boolean;
BEGIN
    EXECUTE format(
        'SELECT verification_run.issue_snapshot_id IS NOT NULL
                OR verification_run.policy_version LIKE ''m2.5-traceability-policy/%%''
         FROM %I.traceability_snapshot snapshot
         JOIN %I.traceability_verification_run verification_run
           ON verification_run.id = snapshot.verification_run_id
         WHERE snapshot.id = $1 AND snapshot.project_id = $2',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO producer_is_v11 USING NEW.snapshot_id, NEW.project_id;

    IF NOT coalesce(producer_is_v11, false) THEN
        RETURN NEW;
    END IF;

    EXECUTE format(
        'SELECT EXISTS (
           SELECT 1
           FROM %I.traceability_snapshot snapshot
           JOIN %I.traceability_verification_run_edge_input edge_input
             ON edge_input.verification_run_id = snapshot.verification_run_id
            AND edge_input.project_id = snapshot.project_id
            AND edge_input.edge_type = $3
            AND edge_input.source_edge_id = $4
            AND edge_input.source_edge_revision = $5
            AND edge_input.fact_digest = $6
           WHERE snapshot.id = $1 AND snapshot.project_id = $2
         )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO input_matches
    USING NEW.snapshot_id, NEW.project_id, NEW.edge_type, NEW.source_edge_id,
          NEW.source_edge_revision, NEW.fact_digest;

    IF NOT input_matches THEN
        RAISE EXCEPTION 'snapshot edge is outside the producer fixed input' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_snapshot_edge_fixed_input
    BEFORE INSERT ON traceability_snapshot_edge
    FOR EACH ROW EXECUTE FUNCTION validate_snapshot_edge_fixed_input();

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
            AND issue_item.ordinal = $5
            AND issue_item.issue_id = $3
            AND issue_item.source_issue_id = $4
           WHERE snapshot.id = $1 AND snapshot.project_id = $2
             AND snapshot.creation_transaction_id = pg_catalog.pg_current_xact_id()::text::bigint
             AND verification_run.status = ''RUNNING''
         )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO authoritative_issue
    USING NEW.snapshot_id, NEW.project_id, NEW.issue_id, NEW.source_issue_id, NEW.ordinal;

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
    expected_edge_type varchar(40);
    path_is_valid boolean;
BEGIN
    expected_edge_type := CASE NEW.path_ordinal
        WHEN 0 THEN 'ISSUE_COMMIT'
        WHEN 1 THEN 'COMMIT_BUILD'
        WHEN 2 THEN 'BUILD_ARTIFACT'
        WHEN 3 THEN 'ARTIFACT_RELEASE'
    END;

    EXECUTE format(
        'SELECT EXISTS (
           SELECT 1
           FROM %I.traceability_snapshot_issue_result issue_result
           JOIN %I.traceability_snapshot snapshot ON snapshot.id = issue_result.snapshot_id
           JOIN %I.traceability_verification_run verification_run
             ON verification_run.id = snapshot.verification_run_id
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
           LEFT JOIN %I.traceability_snapshot_issue_path_edge prior_path
             ON prior_path.snapshot_id = issue_result.snapshot_id
            AND prior_path.issue_ordinal = issue_result.ordinal
            AND prior_path.path_ordinal = $4 - 1
           LEFT JOIN %I.traceability_snapshot_edge prior_edge
             ON prior_edge.snapshot_id = prior_path.snapshot_id
            AND prior_edge.ordinal = prior_path.snapshot_edge_ordinal
           WHERE issue_result.snapshot_id = $1 AND issue_result.ordinal = $2
             AND snapshot.creation_transaction_id = pg_catalog.pg_current_xact_id()::text::bigint
             AND verification_run.status = ''RUNNING''
             AND snapshot_edge.edge_type = $5
             AND (
               ($4 = 0 AND snapshot_edge.from_entity_type = ''ISSUE''
                       AND snapshot_edge.from_entity_id = issue_result.issue_id)
               OR
               ($4 > 0 AND prior_path.path_ordinal IS NOT NULL
                        AND prior_edge.to_entity_type = snapshot_edge.from_entity_type
                        AND prior_edge.to_entity_id = snapshot_edge.from_entity_id)
             )
             AND (
               $4 <> 3
               OR (snapshot_edge.to_entity_type = ''RELEASE''
                   AND snapshot_edge.to_entity_id = snapshot.release_id)
             )
         )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO path_is_valid
    USING NEW.snapshot_id, NEW.issue_ordinal, NEW.snapshot_edge_ordinal,
          NEW.path_ordinal, expected_edge_type;

    IF NOT path_is_valid THEN
        RAISE EXCEPTION 'snapshot issue path violates the frozen four-segment chain'
            USING ERRCODE = '23514';
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

CREATE FUNCTION verification_predecessor_is_issue_path(
    authority_schema text,
    authority_run_id varchar,
    authority_project_id varchar,
    authority_issue_id varchar,
    predecessor_type varchar,
    predecessor_id varchar,
    predecessor_revision integer,
    break_type varchar,
    break_id varchar
) RETURNS boolean
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    predecessor_matches boolean;
BEGIN
    EXECUTE format(
        'WITH RECURSIVE fixed_edges AS (
           SELECT input.edge_type, input.source_edge_id, input.source_edge_revision,
                  ''ISSUE''::varchar AS from_type, edge.issue_id AS from_id,
                  ''COMMIT''::varchar AS to_type, edge.commit_id AS to_id
           FROM %I.traceability_verification_run_edge_input input
           JOIN %I.issue_commit_edge_revision edge
             ON edge.project_id = input.project_id AND edge.edge_id = input.source_edge_id
            AND edge.revision = input.source_edge_revision AND edge.content_digest = input.fact_digest
           WHERE input.verification_run_id = $1 AND input.project_id = $2
             AND input.edge_type = ''ISSUE_COMMIT''
           UNION ALL
           SELECT input.edge_type, input.source_edge_id, input.source_edge_revision,
                  ''COMMIT''::varchar, edge.commit_id, ''BUILD''::varchar, edge.build_id
           FROM %I.traceability_verification_run_edge_input input
           JOIN %I.commit_build_edge_revision edge
             ON edge.project_id = input.project_id AND edge.edge_id = input.source_edge_id
            AND edge.revision = input.source_edge_revision AND edge.content_digest = input.fact_digest
           WHERE input.verification_run_id = $1 AND input.project_id = $2
             AND input.edge_type = ''COMMIT_BUILD''
           UNION ALL
           SELECT input.edge_type, input.source_edge_id, input.source_edge_revision,
                  ''BUILD''::varchar, edge.build_id, ''ARTIFACT''::varchar, edge.artifact_id
           FROM %I.traceability_verification_run_edge_input input
           JOIN %I.build_artifact_edge_revision edge
             ON edge.project_id = input.project_id AND edge.edge_id = input.source_edge_id
            AND edge.revision = input.source_edge_revision AND edge.content_digest = input.fact_digest
           WHERE input.verification_run_id = $1 AND input.project_id = $2
             AND input.edge_type = ''BUILD_ARTIFACT''
           UNION ALL
           SELECT input.edge_type, input.source_edge_id, input.source_edge_revision,
                  ''ARTIFACT''::varchar, edge.artifact_id, ''RELEASE''::varchar, edge.release_id
           FROM %I.traceability_verification_run_edge_input input
           JOIN %I.artifact_release_edge_v edge
             ON edge.project_id = input.project_id AND edge.source_edge_id = input.source_edge_id
            AND edge.source_edge_revision = input.source_edge_revision AND edge.fact_digest = input.fact_digest
           WHERE input.verification_run_id = $1 AND input.project_id = $2
             AND input.edge_type = ''ARTIFACT_RELEASE''
         ), issue_path AS (
           SELECT 0 AS depth, edge.* FROM fixed_edges edge
           WHERE edge.edge_type = ''ISSUE_COMMIT''
             AND edge.from_type = ''ISSUE'' AND edge.from_id = $3
           UNION ALL
           SELECT path.depth + 1, edge.*
           FROM issue_path path
           JOIN fixed_edges edge
             ON edge.from_type = path.to_type AND edge.from_id = path.to_id
            AND edge.edge_type = CASE path.depth
              WHEN 0 THEN ''COMMIT_BUILD''
              WHEN 1 THEN ''BUILD_ARTIFACT''
              WHEN 2 THEN ''ARTIFACT_RELEASE''
            END
           WHERE path.depth < 3
         )
         SELECT EXISTS (
           SELECT 1 FROM issue_path
           WHERE edge_type = $4 AND source_edge_id = $5 AND source_edge_revision = $6
             AND to_type = $7 AND to_id = $8
         )',
        authority_schema, authority_schema, authority_schema, authority_schema,
        authority_schema, authority_schema, authority_schema, authority_schema
    ) INTO predecessor_matches
    USING authority_run_id, authority_project_id, authority_issue_id,
          predecessor_type, predecessor_id, predecessor_revision, break_type, break_id;
    RETURN predecessor_matches;
END;
$$;

CREATE FUNCTION validate_traceability_gap_break() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    producer_is_v11 boolean;
    producer_status varchar(20);
    producer_release_id varchar(40);
    result_transaction_open boolean;
    expected_type varchar(40);
    expected_break_type varchar(40);
    expected_predecessor_type varchar(40);
    issue_is_fixed boolean;
    predecessor_is_fixed boolean;
    expected_edge_exists boolean := false;
BEGIN
    EXECUTE format(
        'SELECT verification_run.issue_snapshot_id IS NOT NULL
                OR verification_run.policy_version LIKE ''m2.5-traceability-policy/%%'',
                verification_run.status,
                verification_run.release_id,
                EXISTS (
                  SELECT 1 FROM %I.traceability_snapshot snapshot
                  WHERE snapshot.verification_run_id = verification_run.id
                    AND snapshot.creation_transaction_id = pg_catalog.pg_current_xact_id()::text::bigint
                )
         FROM %I.traceability_verification_run verification_run
         WHERE verification_run.id = $1 AND verification_run.project_id = $2',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO producer_is_v11, producer_status, producer_release_id, result_transaction_open
    USING NEW.verification_run_id, NEW.project_id;
    IF NOT coalesce(producer_is_v11, false) THEN
        RETURN NEW;
    END IF;
    IF producer_status IS DISTINCT FROM 'RUNNING' OR NOT coalesce(result_transaction_open, false) THEN
        RAISE EXCEPTION 'V11 run gaps require a running producer and its current result transaction'
            USING ERRCODE = '23514';
    END IF;

    expected_type := CASE NEW.diagnostic_code
        WHEN 'ISSUE_COMMIT_MISSING' THEN 'ISSUE_COMMIT'
        WHEN 'COMMIT_BUILD_MISSING' THEN 'COMMIT_BUILD'
        WHEN 'BUILD_ARTIFACT_MISSING' THEN 'BUILD_ARTIFACT'
        WHEN 'ARTIFACT_RELEASE_MISSING' THEN 'ARTIFACT_RELEASE'
        WHEN 'TEST_RESULT_EVIDENCE_MISSING' THEN 'TEST_EVIDENCE'
    END;
    expected_break_type := CASE NEW.diagnostic_code
        WHEN 'ISSUE_COMMIT_MISSING' THEN 'ISSUE'
        WHEN 'COMMIT_BUILD_MISSING' THEN 'COMMIT'
        WHEN 'BUILD_ARTIFACT_MISSING' THEN 'BUILD'
        WHEN 'ARTIFACT_RELEASE_MISSING' THEN 'ARTIFACT'
        WHEN 'TEST_RESULT_EVIDENCE_MISSING' THEN 'RELEASE'
    END;
    expected_predecessor_type := CASE NEW.diagnostic_code
        WHEN 'COMMIT_BUILD_MISSING' THEN 'ISSUE_COMMIT'
        WHEN 'BUILD_ARTIFACT_MISSING' THEN 'COMMIT_BUILD'
        WHEN 'ARTIFACT_RELEASE_MISSING' THEN 'BUILD_ARTIFACT'
        WHEN 'TEST_RESULT_EVIDENCE_MISSING' THEN 'ARTIFACT_RELEASE'
    END;

    IF expected_type IS NULL
        OR NEW.issue_id IS NULL
        OR NEW.expected_edge_type IS DISTINCT FROM expected_type
        OR NEW.break_entity_type IS DISTINCT FROM expected_break_type
        OR NEW.break_entity_id IS NULL
        OR NEW.predecessor_edge_type IS DISTINCT FROM expected_predecessor_type
        OR (expected_predecessor_type IS NULL AND (
            NEW.predecessor_edge_id IS NOT NULL OR NEW.predecessor_edge_revision IS NOT NULL
        ))
        OR (expected_predecessor_type IS NOT NULL AND (
            NEW.predecessor_edge_id IS NULL OR NEW.predecessor_edge_revision IS NULL
            OR NEW.predecessor_edge_revision <= 0
        )) THEN
        RAISE EXCEPTION 'gap does not match the frozen diagnostic break mapping' USING ERRCODE = '23514';
    END IF;

    EXECUTE format(
        'SELECT EXISTS (
           SELECT 1 FROM %I.traceability_verification_run verification_run
           JOIN %I.release_issue_snapshot_item issue_item
             ON issue_item.snapshot_id = verification_run.issue_snapshot_id
            AND issue_item.project_id = verification_run.project_id
           WHERE verification_run.id = $1 AND verification_run.project_id = $2
             AND issue_item.issue_id = $3
         )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO issue_is_fixed USING NEW.verification_run_id, NEW.project_id, NEW.issue_id;
    IF NOT issue_is_fixed THEN
        RAISE EXCEPTION 'gap issue is outside the fixed issue input' USING ERRCODE = '23514';
    END IF;

    IF expected_predecessor_type IS NOT NULL THEN
        EXECUTE format(
            'SELECT %I.verification_predecessor_is_issue_path($1, $2, $3, $4, $5, $6, $7, $8, $9)',
            TG_TABLE_SCHEMA
        ) INTO predecessor_is_fixed
        USING TG_TABLE_SCHEMA, NEW.verification_run_id, NEW.project_id, NEW.issue_id,
              NEW.predecessor_edge_type, NEW.predecessor_edge_id, NEW.predecessor_edge_revision,
              NEW.break_entity_type, NEW.break_entity_id;
        IF NOT predecessor_is_fixed THEN
            RAISE EXCEPTION 'gap predecessor is not the fixed edge ending at the break entity'
                USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.break_entity_id IS DISTINCT FROM NEW.issue_id THEN
        RAISE EXCEPTION 'issue-commit gap must break at its issue' USING ERRCODE = '23514';
    END IF;

    IF NEW.diagnostic_code = 'ISSUE_COMMIT_MISSING' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.traceability_verification_run_edge_input input
               JOIN %I.issue_commit_edge_revision edge
                 ON edge.project_id = input.project_id AND edge.edge_id = input.source_edge_id
                AND edge.revision = input.source_edge_revision AND edge.content_digest = input.fact_digest
               WHERE input.verification_run_id = $1 AND input.project_id = $2
                 AND input.edge_type = ''ISSUE_COMMIT'' AND edge.issue_id = $3
             )', TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO expected_edge_exists
        USING NEW.verification_run_id, NEW.project_id, NEW.issue_id;
    ELSIF NEW.diagnostic_code = 'COMMIT_BUILD_MISSING' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.traceability_verification_run_edge_input input
               JOIN %I.commit_build_edge_revision edge
                 ON edge.project_id = input.project_id AND edge.edge_id = input.source_edge_id
                AND edge.revision = input.source_edge_revision AND edge.content_digest = input.fact_digest
               WHERE input.verification_run_id = $1 AND input.project_id = $2
                 AND input.edge_type = ''COMMIT_BUILD'' AND edge.commit_id = $3
             )', TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO expected_edge_exists
        USING NEW.verification_run_id, NEW.project_id, NEW.break_entity_id;
    ELSIF NEW.diagnostic_code = 'BUILD_ARTIFACT_MISSING' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.traceability_verification_run_edge_input input
               JOIN %I.build_artifact_edge_revision edge
                 ON edge.project_id = input.project_id AND edge.edge_id = input.source_edge_id
                AND edge.revision = input.source_edge_revision AND edge.content_digest = input.fact_digest
               WHERE input.verification_run_id = $1 AND input.project_id = $2
                 AND input.edge_type = ''BUILD_ARTIFACT'' AND edge.build_id = $3
             )', TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO expected_edge_exists
        USING NEW.verification_run_id, NEW.project_id, NEW.break_entity_id;
    ELSIF NEW.diagnostic_code = 'ARTIFACT_RELEASE_MISSING' THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.traceability_verification_run_edge_input input
               JOIN %I.artifact_release_edge_v edge
                 ON edge.project_id = input.project_id AND edge.source_edge_id = input.source_edge_id
                AND edge.source_edge_revision = input.source_edge_revision AND edge.fact_digest = input.fact_digest
               WHERE input.verification_run_id = $1 AND input.project_id = $2
                 AND input.edge_type = ''ARTIFACT_RELEASE'' AND edge.artifact_id = $3
                 AND edge.release_id = $4
             )', TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO expected_edge_exists
        USING NEW.verification_run_id, NEW.project_id, NEW.break_entity_id, producer_release_id;
    END IF;

    IF expected_edge_exists THEN
        RAISE EXCEPTION 'run gap reports an edge that exists in the fixed input'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_traceability_gap_break
    BEFORE INSERT ON traceability_gap
    FOR EACH ROW EXECUTE FUNCTION validate_traceability_gap_break();

CREATE FUNCTION complete_traceability_gap_write() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    producer_is_v11 boolean;
    result_is_current boolean;
BEGIN
    EXECUTE format(
        'SELECT verification_run.issue_snapshot_id IS NOT NULL
                OR verification_run.policy_version LIKE ''m2.5-traceability-policy/%%'',
                verification_run.status = ''SUCCEEDED''
                AND EXISTS (
                  SELECT 1 FROM %I.traceability_snapshot snapshot
                  WHERE snapshot.id = verification_run.result_snapshot_id
                    AND snapshot.verification_run_id = verification_run.id
                    AND snapshot.creation_transaction_id = pg_catalog.pg_current_xact_id()::text::bigint
                )
         FROM %I.traceability_verification_run verification_run
         WHERE verification_run.id = $1 AND verification_run.project_id = $2',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO producer_is_v11, result_is_current
    USING NEW.verification_run_id, NEW.project_id;

    IF coalesce(producer_is_v11, false) AND NOT coalesce(result_is_current, false) THEN
        RAISE EXCEPTION 'V11 run gap must commit with its producer result snapshot transaction'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER complete_traceability_gap_write
    AFTER INSERT ON traceability_gap
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION complete_traceability_gap_write();

CREATE FUNCTION validate_traceability_snapshot_gap_break() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    producer_is_v11 boolean;
    expected_type varchar(40);
    expected_break_type varchar(40);
    expected_predecessor_type varchar(40);
    predecessor_is_path_edge boolean;
BEGIN
    EXECUTE format(
        'SELECT verification_run.issue_snapshot_id IS NOT NULL
                OR verification_run.policy_version LIKE ''m2.5-traceability-policy/%%''
         FROM %I.traceability_snapshot snapshot
         JOIN %I.traceability_verification_run verification_run
           ON verification_run.id = snapshot.verification_run_id
         WHERE snapshot.id = $1 AND snapshot.project_id = $2',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO producer_is_v11 USING NEW.snapshot_id, NEW.project_id;
    IF NOT coalesce(producer_is_v11, false) THEN
        RETURN NEW;
    END IF;

    expected_type := CASE NEW.diagnostic_code
        WHEN 'ISSUE_COMMIT_MISSING' THEN 'ISSUE_COMMIT'
        WHEN 'COMMIT_BUILD_MISSING' THEN 'COMMIT_BUILD'
        WHEN 'BUILD_ARTIFACT_MISSING' THEN 'BUILD_ARTIFACT'
        WHEN 'ARTIFACT_RELEASE_MISSING' THEN 'ARTIFACT_RELEASE'
        WHEN 'TEST_RESULT_EVIDENCE_MISSING' THEN 'TEST_EVIDENCE'
    END;
    expected_break_type := CASE NEW.diagnostic_code
        WHEN 'ISSUE_COMMIT_MISSING' THEN 'ISSUE'
        WHEN 'COMMIT_BUILD_MISSING' THEN 'COMMIT'
        WHEN 'BUILD_ARTIFACT_MISSING' THEN 'BUILD'
        WHEN 'ARTIFACT_RELEASE_MISSING' THEN 'ARTIFACT'
        WHEN 'TEST_RESULT_EVIDENCE_MISSING' THEN 'RELEASE'
    END;
    expected_predecessor_type := CASE NEW.diagnostic_code
        WHEN 'COMMIT_BUILD_MISSING' THEN 'ISSUE_COMMIT'
        WHEN 'BUILD_ARTIFACT_MISSING' THEN 'COMMIT_BUILD'
        WHEN 'ARTIFACT_RELEASE_MISSING' THEN 'BUILD_ARTIFACT'
        WHEN 'TEST_RESULT_EVIDENCE_MISSING' THEN 'ARTIFACT_RELEASE'
    END;

    IF expected_type IS NULL
        OR NEW.issue_id IS NULL
        OR NEW.expected_edge_type IS DISTINCT FROM expected_type
        OR NEW.break_entity_type IS DISTINCT FROM expected_break_type
        OR NEW.break_entity_id IS NULL
        OR NEW.predecessor_edge_type IS DISTINCT FROM expected_predecessor_type
        OR (expected_predecessor_type IS NULL AND (
            NEW.predecessor_edge_id IS NOT NULL OR NEW.predecessor_edge_revision IS NOT NULL
        ))
        OR (expected_predecessor_type IS NOT NULL AND (
            NEW.predecessor_edge_id IS NULL OR NEW.predecessor_edge_revision IS NULL
            OR NEW.predecessor_edge_revision <= 0
        )) THEN
        RAISE EXCEPTION 'snapshot gap does not match the frozen diagnostic break mapping'
            USING ERRCODE = '23514';
    END IF;

    IF expected_predecessor_type IS NULL THEN
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1 FROM %I.traceability_snapshot_issue_result issue_result
               WHERE issue_result.snapshot_id = $1 AND issue_result.issue_id = $2
                 AND issue_result.issue_id = $3
             )', TG_TABLE_SCHEMA
        ) INTO predecessor_is_path_edge USING NEW.snapshot_id, NEW.issue_id, NEW.break_entity_id;
    ELSE
        EXECUTE format(
            'SELECT EXISTS (
               SELECT 1
               FROM %I.traceability_snapshot_issue_result issue_result
               JOIN %I.traceability_snapshot_issue_path_edge path_edge
                 ON path_edge.snapshot_id = issue_result.snapshot_id
                AND path_edge.issue_ordinal = issue_result.ordinal
               JOIN %I.traceability_snapshot_edge snapshot_edge
                 ON snapshot_edge.snapshot_id = path_edge.snapshot_id
                AND snapshot_edge.ordinal = path_edge.snapshot_edge_ordinal
               WHERE issue_result.snapshot_id = $1 AND issue_result.issue_id = $2
                 AND snapshot_edge.edge_type = $3
                 AND snapshot_edge.source_edge_id = $4
                 AND snapshot_edge.source_edge_revision = $5
                 AND snapshot_edge.to_entity_type = $6
                 AND snapshot_edge.to_entity_id = $7
             )',
            TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
        ) INTO predecessor_is_path_edge
        USING NEW.snapshot_id, NEW.issue_id, NEW.predecessor_edge_type,
              NEW.predecessor_edge_id, NEW.predecessor_edge_revision,
              NEW.break_entity_type, NEW.break_entity_id;
    END IF;

    IF NOT predecessor_is_path_edge THEN
        RAISE EXCEPTION 'snapshot gap predecessor is not in the issue path at the break entity'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_traceability_snapshot_gap_break
    BEFORE INSERT ON traceability_snapshot_gap
    FOR EACH ROW EXECUTE FUNCTION validate_traceability_snapshot_gap_break();

CREATE FUNCTION complete_traceability_verification_run() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
DECLARE
    result_is_own_atomic boolean;
    result_is_compatible_reuse boolean;
    result_is_incomplete boolean;
BEGIN
    IF (
        NEW.issue_snapshot_id IS NULL
        AND NEW.policy_version NOT LIKE 'm2.5-traceability-policy/%'
    ) OR NEW.status <> 'SUCCEEDED' THEN
        RETURN NULL;
    END IF;

    EXECUTE format(
        'SELECT
           snapshot.verification_run_id = $9
             AND snapshot.creation_transaction_id = pg_catalog.pg_current_xact_id()::text::bigint,
           snapshot.verification_run_id <> $9
             AND producer.status = ''SUCCEEDED''
             AND producer.result_snapshot_id = snapshot.id
             AND producer.issue_snapshot_id = $4
             AND producer.manifest_revision_id = $5
             AND producer.policy_version = $6
             AND producer.validator_version = $7
             AND producer.input_digest = $8
             AND producer.input_edge_count = $10
             AND NOT EXISTS (
               SELECT 1 FROM %I.traceability_verification_run_edge_input producer_input
               WHERE producer_input.verification_run_id = producer.id
                 AND NOT EXISTS (
                   SELECT 1 FROM %I.traceability_verification_run_edge_input consumer_input
                   WHERE consumer_input.verification_run_id = $9
                     AND consumer_input.ordinal = producer_input.ordinal
                     AND consumer_input.project_id = producer_input.project_id
                     AND consumer_input.edge_type = producer_input.edge_type
                     AND consumer_input.source_edge_id = producer_input.source_edge_id
                     AND consumer_input.source_edge_revision = producer_input.source_edge_revision
                     AND consumer_input.fact_digest = producer_input.fact_digest
                 )
             )
             AND NOT EXISTS (
               SELECT 1 FROM %I.traceability_verification_run_edge_input consumer_input
               WHERE consumer_input.verification_run_id = $9
                 AND NOT EXISTS (
                   SELECT 1 FROM %I.traceability_verification_run_edge_input producer_input
                   WHERE producer_input.verification_run_id = producer.id
                     AND producer_input.ordinal = consumer_input.ordinal
                     AND producer_input.project_id = consumer_input.project_id
                     AND producer_input.edge_type = consumer_input.edge_type
                     AND producer_input.source_edge_id = consumer_input.source_edge_id
                     AND producer_input.source_edge_revision = consumer_input.source_edge_revision
                     AND producer_input.fact_digest = consumer_input.fact_digest
                 )
             )
         FROM %I.traceability_snapshot snapshot
         JOIN %I.traceability_verification_run producer
           ON producer.id = snapshot.verification_run_id
          AND producer.release_id = snapshot.release_id
          AND producer.project_id = snapshot.project_id
         WHERE snapshot.id = $1 AND snapshot.release_id = $2 AND snapshot.project_id = $3
           AND snapshot.policy_version = $6',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO result_is_own_atomic, result_is_compatible_reuse
    USING NEW.result_snapshot_id, NEW.release_id, NEW.project_id,
          NEW.issue_snapshot_id, NEW.manifest_revision_id, NEW.policy_version,
          NEW.validator_version, NEW.input_digest, NEW.id, NEW.input_edge_count;

    IF NOT coalesce(result_is_own_atomic, false)
        AND NOT coalesce(result_is_compatible_reuse, false) THEN
        RAISE EXCEPTION 'result snapshot is neither an atomic own result nor compatible successful reuse'
            USING ERRCODE = '23514';
    END IF;

    IF result_is_compatible_reuse THEN
        RETURN NULL;
    END IF;

    EXECUTE format(
        'SELECT
           EXISTS (
             SELECT 1
             FROM %I.release_issue_snapshot_item issue_item
             WHERE issue_item.snapshot_id = $2
               AND NOT EXISTS (
                 SELECT 1 FROM %I.traceability_snapshot_issue_result issue_result
                 WHERE issue_result.snapshot_id = $1
                   AND issue_result.ordinal = issue_item.ordinal
                   AND issue_result.project_id = issue_item.project_id
                   AND issue_result.issue_id = issue_item.issue_id
                   AND issue_result.source_issue_id = issue_item.source_issue_id
               )
           )
           OR EXISTS (
             SELECT 1 FROM %I.traceability_snapshot_issue_result issue_result
             WHERE issue_result.snapshot_id = $1
               AND NOT EXISTS (
                 SELECT 1 FROM %I.release_issue_snapshot_item issue_item
                 WHERE issue_item.snapshot_id = $2
                   AND issue_item.ordinal = issue_result.ordinal
                   AND issue_item.project_id = issue_result.project_id
                   AND issue_item.issue_id = issue_result.issue_id
                   AND issue_item.source_issue_id = issue_result.source_issue_id
               )
           )
           OR EXISTS (
             SELECT 1 FROM %I.traceability_verification_run_edge_input edge_input
             WHERE edge_input.verification_run_id = $3
               AND NOT EXISTS (
                 SELECT 1 FROM %I.traceability_snapshot_edge snapshot_edge
                 WHERE snapshot_edge.snapshot_id = $1
                   AND snapshot_edge.project_id = edge_input.project_id
                   AND snapshot_edge.edge_type = edge_input.edge_type
                   AND snapshot_edge.source_edge_id = edge_input.source_edge_id
                   AND snapshot_edge.source_edge_revision = edge_input.source_edge_revision
                   AND snapshot_edge.fact_digest = edge_input.fact_digest
               )
           )
           OR EXISTS (
             SELECT 1 FROM %I.traceability_snapshot_edge snapshot_edge
             WHERE snapshot_edge.snapshot_id = $1
               AND NOT EXISTS (
                 SELECT 1 FROM %I.traceability_verification_run_edge_input edge_input
                 WHERE edge_input.verification_run_id = $3
                   AND edge_input.project_id = snapshot_edge.project_id
                   AND edge_input.edge_type = snapshot_edge.edge_type
                   AND edge_input.source_edge_id = snapshot_edge.source_edge_id
                   AND edge_input.source_edge_revision = snapshot_edge.source_edge_revision
                   AND edge_input.fact_digest = snapshot_edge.fact_digest
               )
           )
           OR EXISTS (
             SELECT 1
             FROM %I.traceability_snapshot_issue_result issue_result
             CROSS JOIN LATERAL (
               SELECT count(*)::integer AS path_count,
                      bool_or(path_edge.path_ordinal = 0) AS has_issue_commit
               FROM %I.traceability_snapshot_issue_path_edge path_edge
               WHERE path_edge.snapshot_id = issue_result.snapshot_id
                 AND path_edge.issue_ordinal = issue_result.ordinal
             ) path_state
             WHERE issue_result.snapshot_id = $1
               AND (
                 issue_result.fixed IS DISTINCT FROM coalesce(path_state.has_issue_commit, false)
                 OR issue_result.included IS DISTINCT FROM (path_state.path_count = 4)
                 OR (
                   issue_result.included AND (
                     SELECT count(*) FROM %I.traceability_snapshot_gap snapshot_gap
                     WHERE snapshot_gap.snapshot_id = issue_result.snapshot_id
                       AND snapshot_gap.issue_id = issue_result.issue_id
                       AND snapshot_gap.diagnostic_code = ''TEST_RESULT_EVIDENCE_MISSING''
                   ) <> 1
                 )
                 OR (
                   NOT issue_result.included AND (
                     SELECT count(*) FROM %I.traceability_snapshot_gap snapshot_gap
                     WHERE snapshot_gap.snapshot_id = issue_result.snapshot_id
                       AND snapshot_gap.issue_id = issue_result.issue_id
                       AND snapshot_gap.diagnostic_code = CASE path_state.path_count
                         WHEN 0 THEN ''ISSUE_COMMIT_MISSING''
                         WHEN 1 THEN ''COMMIT_BUILD_MISSING''
                         WHEN 2 THEN ''BUILD_ARTIFACT_MISSING''
                         WHEN 3 THEN ''ARTIFACT_RELEASE_MISSING''
                       END
                   ) <> 1
                 )
                 OR (
                   SELECT count(*) FROM %I.traceability_snapshot_gap snapshot_gap
                   WHERE snapshot_gap.snapshot_id = issue_result.snapshot_id
                     AND snapshot_gap.issue_id = issue_result.issue_id
                 ) <> 1
               )
           )
           OR EXISTS (
             SELECT 1 FROM %I.traceability_snapshot_gap snapshot_gap
             WHERE snapshot_gap.snapshot_id = $1
               AND NOT EXISTS (
                 SELECT 1 FROM %I.traceability_snapshot_issue_result issue_result
                 WHERE issue_result.snapshot_id = snapshot_gap.snapshot_id
                   AND issue_result.issue_id = snapshot_gap.issue_id
               )
           )',
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA,
        TG_TABLE_SCHEMA, TG_TABLE_SCHEMA, TG_TABLE_SCHEMA
    ) INTO result_is_incomplete
    USING NEW.result_snapshot_id, NEW.issue_snapshot_id, NEW.id;

    IF result_is_incomplete THEN
        RAISE EXCEPTION 'new result snapshot is incomplete or differs from the fixed input'
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER complete_traceability_verification_run
    AFTER UPDATE OF status, result_snapshot_id ON traceability_verification_run
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION complete_traceability_verification_run();

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
