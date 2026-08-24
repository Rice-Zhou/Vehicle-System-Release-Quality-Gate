CREATE TABLE project (
    id varchar(40) PRIMARY KEY,
    project_key varchar(100) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    archived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL
);

CREATE TABLE principal (
    id varchar(40) PRIMARY KEY,
    issuer varchar(255) NOT NULL,
    subject varchar(255) NOT NULL,
    principal_type varchar(20) NOT NULL CHECK (principal_type IN ('USER', 'SERVICE', 'AGENT')),
    disabled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    UNIQUE (issuer, subject)
);

CREATE TABLE project_assignment (
    project_id varchar(40) NOT NULL,
    principal_id varchar(40) NOT NULL,
    role varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, principal_id),
    CONSTRAINT fk_assignment_project FOREIGN KEY (project_id)
        REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_assignment_principal FOREIGN KEY (principal_id)
        REFERENCES principal(id) ON DELETE RESTRICT
);
CREATE INDEX ix_project_assignment_principal ON project_assignment(principal_id);

CREATE TABLE release_record (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    vehicle varchar(120) NOT NULL,
    platform varchar(120) NOT NULL,
    system_version varchar(255) NOT NULL,
    build_id varchar(255) NOT NULL,
    status varchar(40) NOT NULL CHECK (status IN (
        'DRAFT', 'REGISTERED', 'READY_FOR_TEST', 'TESTING', 'QUALITY_EVALUATED', 'COMPLETED'
    )),
    locked_manifest_id varchar(40),
    row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_release_project FOREIGN KEY (project_id)
        REFERENCES project(id) ON DELETE RESTRICT,
    UNIQUE (project_id, vehicle, platform, system_version, build_id)
);
CREATE INDEX ix_release_project_created ON release_record(project_id, created_at DESC);

CREATE TABLE release_state_history (
    id varchar(40) PRIMARY KEY,
    release_id varchar(40) NOT NULL,
    previous_status varchar(40),
    new_status varchar(40) NOT NULL,
    actor_id varchar(40) NOT NULL,
    reason text,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_release_history_release FOREIGN KEY (release_id)
        REFERENCES release_record(id) ON DELETE RESTRICT,
    CONSTRAINT fk_release_history_actor FOREIGN KEY (actor_id)
        REFERENCES principal(id) ON DELETE RESTRICT
);
CREATE INDEX ix_release_history_release ON release_state_history(release_id, occurred_at);
CREATE INDEX ix_release_history_actor ON release_state_history(actor_id);

CREATE TABLE manifest_revision (
    id varchar(40) PRIMARY KEY,
    release_id varchar(40) NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    content_digest varchar(71) NOT NULL CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    raw_manifest jsonb NOT NULL,
    canonical_bytes bytea NOT NULL,
    schema_version varchar(40) NOT NULL,
    state varchar(20) NOT NULL CHECK (state IN ('DRAFT', 'VALIDATED', 'REGISTERED', 'LOCKED', 'REJECTED')),
    row_version bigint NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT fk_manifest_release FOREIGN KEY (release_id)
        REFERENCES release_record(id) ON DELETE RESTRICT,
    UNIQUE (release_id, revision),
    UNIQUE (release_id, content_digest)
);
CREATE UNIQUE INDEX uq_one_locked_manifest_per_release
    ON manifest_revision(release_id) WHERE state = 'LOCKED';
CREATE INDEX ix_manifest_release ON manifest_revision(release_id, revision DESC);

ALTER TABLE release_record ADD CONSTRAINT fk_release_locked_manifest
    FOREIGN KEY (locked_manifest_id) REFERENCES manifest_revision(id)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX ix_release_locked_manifest ON release_record(locked_manifest_id);

CREATE TABLE artifact (
    id varchar(40) PRIMARY KEY,
    identity_digest varchar(71) NOT NULL UNIQUE CHECK (identity_digest ~ '^sha256:[0-9a-f]{64}$'),
    artifact_type varchar(40) NOT NULL,
    locator jsonb NOT NULL,
    checksum_algorithm varchar(20) NOT NULL CHECK (checksum_algorithm = 'SHA-256'),
    checksum_value varchar(64) NOT NULL CHECK (checksum_value ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL
);

CREATE TABLE manifest_artifact (
    manifest_id varchar(40) NOT NULL,
    artifact_id varchar(40) NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    required boolean NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (manifest_id, artifact_id),
    CONSTRAINT fk_manifest_artifact_manifest FOREIGN KEY (manifest_id)
        REFERENCES manifest_revision(id) ON DELETE RESTRICT,
    CONSTRAINT fk_manifest_artifact_artifact FOREIGN KEY (artifact_id)
        REFERENCES artifact(id) ON DELETE RESTRICT,
    UNIQUE (manifest_id, ordinal)
);
CREATE INDEX ix_manifest_artifact_artifact ON manifest_artifact(artifact_id);

CREATE TABLE manifest_validation (
    id varchar(40) PRIMARY KEY,
    manifest_id varchar(40) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('VALID', 'INVALID', 'INCOMPLETE')),
    content_digest varchar(71) NOT NULL CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$'),
    schema_version varchar(40) NOT NULL,
    validator_version varchar(80) NOT NULL,
    report jsonb NOT NULL,
    validated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_manifest_validation_manifest FOREIGN KEY (manifest_id)
        REFERENCES manifest_revision(id) ON DELETE RESTRICT,
    UNIQUE (manifest_id, validator_version)
);
CREATE INDEX ix_manifest_validation_manifest ON manifest_validation(manifest_id, validated_at DESC);

CREATE TABLE audit_event (
    id varchar(40) PRIMARY KEY,
    event_id varchar(80) NOT NULL UNIQUE,
    project_id varchar(40) NOT NULL,
    actor_id varchar(40),
    action varchar(80) NOT NULL,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id varchar(80) NOT NULL,
    before_state jsonb,
    after_state jsonb,
    correlation_id varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_audit_project FOREIGN KEY (project_id)
        REFERENCES project(id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_id)
        REFERENCES principal(id) ON DELETE RESTRICT
);
CREATE INDEX ix_audit_project ON audit_event(project_id, occurred_at DESC);
CREATE INDEX ix_audit_actor ON audit_event(actor_id);
CREATE INDEX ix_audit_aggregate ON audit_event(aggregate_type, aggregate_id, occurred_at);

CREATE TABLE idempotency_record (
    id varchar(40) PRIMARY KEY,
    scope varchar(80) NOT NULL,
    principal_id varchar(40),
    idempotency_key varchar(255) NOT NULL,
    request_hash varchar(71) NOT NULL CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    response_status integer NOT NULL CHECK (response_status BETWEEN 100 AND 599),
    response_body jsonb,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_idempotency_principal FOREIGN KEY (principal_id)
        REFERENCES principal(id) ON DELETE RESTRICT,
    UNIQUE NULLS NOT DISTINCT (scope, principal_id, idempotency_key)
);
CREATE INDEX ix_idempotency_principal ON idempotency_record(principal_id);
CREATE INDEX ix_idempotency_expiry ON idempotency_record(expires_at);

CREATE TABLE outbox_event (
    id varchar(40) PRIMARY KEY,
    event_id varchar(80) NOT NULL UNIQUE,
    aggregate_type varchar(80) NOT NULL,
    aggregate_id varchar(80) NOT NULL,
    event_type varchar(120) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz
);
CREATE INDEX ix_outbox_unpublished ON outbox_event(created_at) WHERE published_at IS NULL;

CREATE FUNCTION reject_immutable_write() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE FUNCTION reject_locked_manifest_write() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.state = 'LOCKED' THEN
        RAISE EXCEPTION 'locked manifest % is immutable', OLD.id USING ERRCODE = '55000';
    END IF;
    IF OLD.state = 'REGISTERED' AND (
        TG_OP = 'DELETE'
        OR NEW.state <> 'LOCKED'
        OR NEW.release_id IS DISTINCT FROM OLD.release_id
        OR NEW.revision IS DISTINCT FROM OLD.revision
        OR NEW.content_digest IS DISTINCT FROM OLD.content_digest
        OR NEW.raw_manifest IS DISTINCT FROM OLD.raw_manifest
        OR NEW.canonical_bytes IS DISTINCT FROM OLD.canonical_bytes
        OR NEW.schema_version IS DISTINCT FROM OLD.schema_version
    ) THEN
        RAISE EXCEPTION 'registered manifest % content is immutable', OLD.id USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE FUNCTION reject_locked_manifest_artifact_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_manifest_id varchar(40);
BEGIN
    target_manifest_id := CASE WHEN TG_OP = 'INSERT' THEN NEW.manifest_id ELSE OLD.manifest_id END;
    IF EXISTS (
        SELECT 1 FROM manifest_revision
        WHERE id = target_manifest_id AND state IN ('REGISTERED', 'LOCKED')
    ) THEN
        RAISE EXCEPTION 'artifacts of locked manifest % are immutable', target_manifest_id
            USING ERRCODE = '55000';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$;

CREATE FUNCTION enforce_locked_manifest_ownership() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.locked_manifest_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM manifest_revision
        WHERE id = NEW.locked_manifest_id AND release_id = NEW.id AND state = 'LOCKED'
    ) THEN
        RAISE EXCEPTION 'locked manifest must be a LOCKED revision of the same release'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER immutable_release_state_history
    BEFORE UPDATE OR DELETE ON release_state_history
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_audit
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_manifest_validation
    BEFORE UPDATE OR DELETE ON manifest_validation
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_artifact_identity
    BEFORE UPDATE OR DELETE ON artifact
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_locked_manifest
    BEFORE UPDATE OR DELETE ON manifest_revision
    FOR EACH ROW EXECUTE FUNCTION reject_locked_manifest_write();
CREATE TRIGGER immutable_locked_manifest_artifact
    BEFORE INSERT OR UPDATE OR DELETE ON manifest_artifact
    FOR EACH ROW EXECUTE FUNCTION reject_locked_manifest_artifact_write();
CREATE CONSTRAINT TRIGGER release_locked_manifest_ownership
    AFTER INSERT OR UPDATE OF locked_manifest_id ON release_record
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
    EXECUTE FUNCTION enforce_locked_manifest_ownership();
