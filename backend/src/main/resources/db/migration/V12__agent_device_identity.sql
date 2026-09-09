CREATE TABLE device (
    id varchar(128) PRIMARY KEY,
    project_id varchar(40) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    disabled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    UNIQUE (id, project_id),
    CHECK (length(id) > 0)
);

CREATE TABLE agent (
    id varchar(40) PRIMARY KEY,
    principal_id varchar(40) NOT NULL REFERENCES principal(id) ON DELETE RESTRICT,
    project_id varchar(40) NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    device_id varchar(128) NOT NULL,
    certificate_sha256 varchar(64) NOT NULL UNIQUE CHECK (certificate_sha256 ~ '^[0-9a-f]{64}$'),
    revoked boolean NOT NULL DEFAULT false,
    agent_version varchar(128),
    negotiated_protocol varchar(20) CHECK (negotiated_protocol = '1.0'),
    capabilities jsonb,
    collector_versions jsonb,
    registered_at timestamptz,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (device_id, project_id) REFERENCES device(id, project_id) ON DELETE RESTRICT,
    UNIQUE (id, project_id, device_id)
);
CREATE INDEX ix_agent_principal ON agent(principal_id);
CREATE INDEX ix_agent_device ON agent(device_id, project_id);

-- Rotation or reassignment requires a newly provisioned binding, preserving old identity history.
CREATE FUNCTION immutable_agent_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.id, NEW.principal_id, NEW.project_id, NEW.device_id, NEW.certificate_sha256)
        IS DISTINCT FROM ROW(OLD.id, OLD.principal_id, OLD.project_id, OLD.device_id, OLD.certificate_sha256) THEN
        RAISE EXCEPTION 'Agent identity binding is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_agent_identity BEFORE UPDATE ON agent
    FOR EACH ROW EXECUTE FUNCTION immutable_agent_identity();
