CREATE UNIQUE INDEX uq_quality_rule_set_version_project
    ON quality_rule_set_versions(id, project_id);

CREATE TABLE quality_evaluations (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    release_id varchar(40) NOT NULL,
    rule_set_version_id varchar(40) NOT NULL,
    job_id varchar(40) NOT NULL UNIQUE REFERENCES background_job(id) ON DELETE RESTRICT,
    requested_by varchar(40) NOT NULL REFERENCES principal(id) ON DELETE RESTRICT,
    request_id varchar(128) NOT NULL,
    request_body jsonb NOT NULL,
    request_digest varchar(71) NOT NULL CHECK (request_digest ~ '^sha256:[0-9a-f]{64}$'),
    state varchar(20) NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'COMPLETED', 'ERROR')),
    error_code varchar(80),
    created_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    FOREIGN KEY (release_id, project_id) REFERENCES release_record(id, project_id) ON DELETE RESTRICT,
    FOREIGN KEY (rule_set_version_id, project_id)
        REFERENCES quality_rule_set_versions(id, project_id) ON DELETE RESTRICT,
    CHECK ((state IN ('QUEUED', 'RUNNING') AND completed_at IS NULL AND error_code IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND error_code IS NULL)
        OR (state = 'ERROR' AND completed_at IS NOT NULL AND error_code IS NOT NULL))
);
CREATE INDEX ix_quality_evaluations_release_created
    ON quality_evaluations(project_id, release_id, created_at DESC, id DESC);

CREATE TABLE quality_input_snapshots (
    id varchar(40) PRIMARY KEY,
    evaluation_id varchar(40) NOT NULL UNIQUE REFERENCES quality_evaluations(id) ON DELETE RESTRICT,
    project_id varchar(40) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    release_id varchar(40) NOT NULL,
    content jsonb NOT NULL,
    input_digest varchar(71) NOT NULL CHECK (input_digest ~ '^sha256:[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    FOREIGN KEY (release_id, project_id) REFERENCES release_record(id, project_id) ON DELETE RESTRICT
);
CREATE TRIGGER immutable_quality_input_snapshot BEFORE UPDATE OR DELETE ON quality_input_snapshots
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE TABLE quality_rule_results (
    id varchar(40) PRIMARY KEY,
    evaluation_id varchar(40) NOT NULL REFERENCES quality_evaluations(id) ON DELETE RESTRICT,
    ordinal integer NOT NULL CHECK (ordinal >= 0 AND ordinal < 32),
    rule_id varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('PASS', 'WARNING', 'BLOCK', 'ERROR', 'NOT_APPLICABLE')),
    content jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (evaluation_id, ordinal),
    UNIQUE (evaluation_id, rule_id)
);
CREATE TRIGGER immutable_quality_rule_result BEFORE UPDATE OR DELETE ON quality_rule_results
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE TABLE quality_results (
    id varchar(40) PRIMARY KEY,
    evaluation_id varchar(40) NOT NULL UNIQUE REFERENCES quality_evaluations(id) ON DELETE RESTRICT,
    action varchar(20) NOT NULL CHECK (action IN ('PASS', 'WARNING', 'BLOCK')),
    result_digest varchar(71) NOT NULL CHECK (result_digest ~ '^sha256:[0-9a-f]{64}$'),
    content jsonb NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE TRIGGER immutable_quality_result BEFORE UPDATE OR DELETE ON quality_results
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE FUNCTION guard_quality_evaluation_write() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Quality Evaluation is retained' USING ERRCODE = '55000';
    END IF;
    IF ROW(NEW.id, NEW.project_id, NEW.release_id, NEW.rule_set_version_id, NEW.job_id,
           NEW.requested_by, NEW.request_id, NEW.request_body, NEW.request_digest, NEW.created_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.project_id, OLD.release_id, OLD.rule_set_version_id, OLD.job_id,
           OLD.requested_by, OLD.request_id, OLD.request_body, OLD.request_digest, OLD.created_at) THEN
        RAISE EXCEPTION 'Quality Evaluation identity is immutable' USING ERRCODE = '55000';
    END IF;
    IF OLD.state IN ('COMPLETED', 'ERROR') OR
       (OLD.state = 'QUEUED' AND NEW.state NOT IN ('RUNNING', 'ERROR')) OR
       (OLD.state = 'RUNNING' AND NEW.state NOT IN ('RUNNING', 'COMPLETED', 'ERROR')) THEN
        RAISE EXCEPTION 'Quality Evaluation state transition is invalid' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER guard_quality_evaluation_write BEFORE UPDATE OR DELETE ON quality_evaluations
    FOR EACH ROW EXECUTE FUNCTION guard_quality_evaluation_write();