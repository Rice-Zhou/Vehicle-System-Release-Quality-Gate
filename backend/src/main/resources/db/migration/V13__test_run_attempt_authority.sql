ALTER TABLE device ADD COLUMN vehicle varchar(120), ADD COLUMN platform varchar(120);
ALTER TABLE agent ADD COLUMN last_heartbeat_at timestamptz, ADD COLUMN heartbeat jsonb;
ALTER TABLE release_record ADD CONSTRAINT uq_release_project UNIQUE (id, project_id);
ALTER TABLE manifest_revision ADD CONSTRAINT uq_manifest_release_digest UNIQUE (id, release_id, content_digest);

CREATE TABLE test_case_version (
 id varchar(40) PRIMARY KEY, case_id varchar(128) NOT NULL, version integer NOT NULL CHECK(version > 0),
 state varchar(20) NOT NULL CHECK(state IN ('DRAFT','PUBLISHED')), definition jsonb NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(case_id, version)
);
CREATE TABLE test_plan_version (
 id varchar(40) PRIMARY KEY, plan_id varchar(128) NOT NULL, version integer NOT NULL CHECK(version > 0),
 state varchar(20) NOT NULL CHECK(state IN ('DRAFT','PUBLISHED')), max_attempts integer NOT NULL CHECK(max_attempts = 1),
 created_at timestamptz NOT NULL, UNIQUE(plan_id, version)
);
CREATE TABLE test_plan_case (
 plan_version_id varchar(40) NOT NULL REFERENCES test_plan_version(id),
 case_version_id varchar(40) NOT NULL REFERENCES test_case_version(id),
 ordinal integer NOT NULL CHECK(ordinal >= 0), required boolean NOT NULL,
 PRIMARY KEY(plan_version_id, case_version_id), UNIQUE(plan_version_id, ordinal), UNIQUE(plan_version_id)
);
CREATE TRIGGER immutable_test_case BEFORE UPDATE OR DELETE ON test_case_version FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_test_plan BEFORE UPDATE OR DELETE ON test_plan_version FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_test_plan_case BEFORE UPDATE OR DELETE ON test_plan_case FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE TABLE environment_snapshot (
 id varchar(40) PRIMARY KEY, project_id varchar(40) NOT NULL, agent_id varchar(40) NOT NULL, device_id varchar(128) NOT NULL,
 config_bytes bytea NOT NULL, content_digest varchar(71) NOT NULL CHECK(content_digest ~ '^sha256:[0-9a-f]{64}$'),
 environment jsonb NOT NULL, created_at timestamptz NOT NULL,
 FOREIGN KEY(agent_id, project_id, device_id) REFERENCES agent(id, project_id, device_id),
 UNIQUE(id, project_id, agent_id, device_id)
);
CREATE TRIGGER immutable_environment BEFORE UPDATE OR DELETE ON environment_snapshot FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE TABLE test_run (
 id varchar(40) PRIMARY KEY, release_id varchar(40) NOT NULL, project_id varchar(40) NOT NULL,
 manifest_revision_id varchar(40) NOT NULL, manifest_digest varchar(71) NOT NULL,
 plan_version_id varchar(40) NOT NULL REFERENCES test_plan_version(id),
 environment_snapshot_id varchar(40) NOT NULL, agent_id varchar(40) NOT NULL, device_id varchar(128) NOT NULL,
 created_by varchar(40) NOT NULL REFERENCES principal(id),
 state varchar(30) NOT NULL CHECK(state IN ('CREATED','WAITING_FOR_AGENT','RUNNING','COMPLETED','ERROR','TIMEOUT','CANCELLED')),
 allocation_deadline timestamptz NOT NULL, deadline timestamptz NOT NULL,
 started_at timestamptz, finished_at timestamptz, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 FOREIGN KEY(release_id,project_id) REFERENCES release_record(id,project_id),
 FOREIGN KEY(manifest_revision_id,release_id,manifest_digest) REFERENCES manifest_revision(id,release_id,content_digest),
 FOREIGN KEY(environment_snapshot_id,project_id,agent_id,device_id) REFERENCES environment_snapshot(id,project_id,agent_id,device_id),
 UNIQUE(id, release_id), UNIQUE(id, project_id, agent_id, device_id),
 CHECK(deadline > created_at AND allocation_deadline > created_at AND allocation_deadline <= deadline),
 CHECK((state IN ('COMPLETED','ERROR','TIMEOUT','CANCELLED')) = (finished_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_active_run_device ON test_run(device_id) WHERE state IN ('CREATED','WAITING_FOR_AGENT','RUNNING');
CREATE INDEX ix_test_run_deadlines ON test_run(deadline) WHERE finished_at IS NULL;
CREATE INDEX ix_test_run_release ON test_run(release_id,created_at DESC);
CREATE TABLE test_attempt (
 id uuid PRIMARY KEY, test_run_id varchar(40) NOT NULL, project_id varchar(40) NOT NULL,
 agent_id varchar(40) NOT NULL, device_id varchar(128) NOT NULL,
 case_version_id varchar(40) NOT NULL REFERENCES test_case_version(id), attempt_no integer NOT NULL CHECK(attempt_no=1),
 state varchar(30) NOT NULL CHECK(state IN ('QUEUED','DISPATCHED','ACKED','RUNNING','RECOVERY_PENDING','UPLOADING','COMPLETED','ERROR','TIMEOUT','CANCELLED')),
 command_id varchar(40) NOT NULL UNIQUE, lease_id varchar(40) NOT NULL, fencing_token bigint NOT NULL CHECK(fencing_token > 0),
 lease_expires_at timestamptz, case_deadline timestamptz, recovery_deadline timestamptz, recovery_state varchar(30),
 context jsonb NOT NULL, context_digest varchar(71) NOT NULL CHECK(context_digest ~ '^sha256:[0-9a-f]{64}$'),
 started_at timestamptz, finished_at timestamptz, created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 FOREIGN KEY(test_run_id,project_id,agent_id,device_id) REFERENCES test_run(id,project_id,agent_id,device_id),
 UNIQUE(id,test_run_id), UNIQUE(id,command_id,test_run_id,agent_id), UNIQUE(test_run_id,case_version_id,attempt_no),
 CHECK((state IN ('COMPLETED','ERROR','TIMEOUT','CANCELLED')) = (finished_at IS NOT NULL))
);
CREATE INDEX ix_test_attempt_lease ON test_attempt(agent_id,state,lease_expires_at);
CREATE FUNCTION bind_attempt_to_run_plan() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent test_run;
BEGIN
 SELECT * INTO parent FROM test_run WHERE id=NEW.test_run_id;
 IF parent.finished_at IS NOT NULL OR NOT EXISTS(
   SELECT 1 FROM test_plan_case WHERE plan_version_id=parent.plan_version_id AND case_version_id=NEW.case_version_id
 ) OR NEW.context->>'attemptId' IS DISTINCT FROM NEW.id::text
   OR NEW.context->>'commandId' IS DISTINCT FROM NEW.command_id
   OR NEW.context->>'releaseId' IS DISTINCT FROM parent.release_id
   OR NEW.context->>'projectId' IS DISTINCT FROM parent.project_id
   OR NEW.context->>'deviceId' IS DISTINCT FROM parent.device_id
   OR NEW.context->>'manifestId' IS DISTINCT FROM parent.manifest_revision_id
   OR NEW.context->>'manifestDigest' IS DISTINCT FROM parent.manifest_digest THEN
  RAISE EXCEPTION 'Attempt must preserve its active Run, Plan and Context identity' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER attempt_run_plan_binding BEFORE INSERT ON test_attempt FOR EACH ROW EXECUTE FUNCTION bind_attempt_to_run_plan();
CREATE TABLE agent_command (
 id varchar(40) PRIMARY KEY, attempt_id uuid NOT NULL UNIQUE, test_run_id varchar(40) NOT NULL,
 agent_id varchar(40) NOT NULL REFERENCES agent(id), idempotency_key varchar(128) NOT NULL UNIQUE,
 envelope jsonb NOT NULL, ack_request_digest varchar(71), ack_response jsonb,
 created_at timestamptz NOT NULL,
 FOREIGN KEY(attempt_id,id,test_run_id,agent_id) REFERENCES test_attempt(id,command_id,test_run_id,agent_id),
 UNIQUE(id,attempt_id),
 CHECK((ack_request_digest IS NULL) = (ack_response IS NULL))
);
CREATE FUNCTION guard_agent_command() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Command history cannot be deleted' USING ERRCODE='55000'; END IF;
 IF OLD.ack_response IS NOT NULL OR ROW(NEW.id,NEW.attempt_id,NEW.test_run_id,NEW.agent_id,NEW.idempotency_key,NEW.envelope,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.attempt_id,OLD.test_run_id,OLD.agent_id,OLD.idempotency_key,OLD.envelope,OLD.created_at) THEN
  RAISE EXCEPTION 'Command and accepted ACK are immutable' USING ERRCODE='55000';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_agent_command BEFORE UPDATE OR DELETE ON agent_command FOR EACH ROW EXECUTE FUNCTION guard_agent_command();
CREATE TABLE agent_command_event (
 command_id varchar(40) NOT NULL REFERENCES agent_command(id), sequence_no bigint NOT NULL CHECK(sequence_no >= 0),
 attempt_id uuid NOT NULL REFERENCES test_attempt(id), event_digest varchar(71) NOT NULL,
 event jsonb NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY(command_id,sequence_no),
 FOREIGN KEY(command_id,attempt_id) REFERENCES agent_command(id,attempt_id)
);
CREATE TRIGGER immutable_command_event BEFORE UPDATE OR DELETE ON agent_command_event FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TABLE test_result (
 id varchar(40) PRIMARY KEY, attempt_id uuid NOT NULL UNIQUE, test_run_id varchar(40) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN ('PASS','FAIL','BLOCKED','ERROR','SKIPPED','TIMEOUT')),
 origin varchar(20) NOT NULL CHECK(origin IN ('SERVER','AGENT')),
 result_digest varchar(71) NOT NULL CHECK(result_digest ~ '^sha256:[0-9a-f]{64}$'),
 result jsonb NOT NULL, created_at timestamptz NOT NULL,
 FOREIGN KEY(attempt_id,test_run_id) REFERENCES test_attempt(id,test_run_id), UNIQUE(id,test_run_id)
);
CREATE TRIGGER immutable_test_result BEFORE UPDATE OR DELETE ON test_result FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TABLE test_run_state_history (
 test_run_id varchar(40) NOT NULL REFERENCES test_run(id), sequence_no bigint NOT NULL,
 state varchar(30) NOT NULL, occurred_at timestamptz NOT NULL, PRIMARY KEY(test_run_id,sequence_no)
);
CREATE TABLE test_attempt_state_history (
 attempt_id uuid NOT NULL REFERENCES test_attempt(id), sequence_no bigint NOT NULL,
 state varchar(30) NOT NULL, fencing_token bigint NOT NULL, occurred_at timestamptz NOT NULL, PRIMARY KEY(attempt_id,sequence_no)
);
CREATE TRIGGER immutable_run_history BEFORE UPDATE OR DELETE ON test_run_state_history FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TRIGGER immutable_attempt_history BEFORE UPDATE OR DELETE ON test_attempt_state_history FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE FUNCTION guard_test_run() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Test history cannot be deleted' USING ERRCODE='55000'; END IF;
 IF TG_OP='UPDATE' THEN
  IF OLD.finished_at IS NOT NULL OR ROW(NEW.id,NEW.release_id,NEW.project_id,NEW.manifest_revision_id,NEW.manifest_digest,NEW.plan_version_id,NEW.environment_snapshot_id,NEW.agent_id,NEW.device_id,NEW.created_by,NEW.created_at,NEW.allocation_deadline,NEW.deadline)
   IS DISTINCT FROM ROW(OLD.id,OLD.release_id,OLD.project_id,OLD.manifest_revision_id,OLD.manifest_digest,OLD.plan_version_id,OLD.environment_snapshot_id,OLD.agent_id,OLD.device_id,OLD.created_by,OLD.created_at,OLD.allocation_deadline,OLD.deadline)
  THEN RAISE EXCEPTION 'Test Run inputs and terminal facts are immutable' USING ERRCODE='55000'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_test_run BEFORE UPDATE OR DELETE ON test_run FOR EACH ROW EXECUTE FUNCTION guard_test_run();

CREATE FUNCTION guard_test_attempt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Attempt history cannot be deleted' USING ERRCODE='55000'; END IF;
 IF OLD.finished_at IS NOT NULL OR ROW(NEW.id,NEW.test_run_id,NEW.project_id,NEW.agent_id,NEW.device_id,NEW.case_version_id,NEW.attempt_no,NEW.command_id,NEW.lease_id,NEW.context,NEW.context_digest,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.test_run_id,OLD.project_id,OLD.agent_id,OLD.device_id,OLD.case_version_id,OLD.attempt_no,OLD.command_id,OLD.lease_id,OLD.context,OLD.context_digest,OLD.created_at)
   OR NEW.fencing_token < OLD.fencing_token
 THEN RAISE EXCEPTION 'Attempt inputs and terminal facts are immutable' USING ERRCODE='55000'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_test_attempt BEFORE UPDATE OR DELETE ON test_attempt FOR EACH ROW EXECUTE FUNCTION guard_test_attempt();

CREATE FUNCTION record_test_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='test_run' THEN
  IF TG_OP='INSERT' OR NEW.state IS DISTINCT FROM OLD.state THEN
   INSERT INTO test_run_state_history SELECT NEW.id,COALESCE(MAX(sequence_no),0)+1,NEW.state,NEW.updated_at FROM test_run_state_history WHERE test_run_id=NEW.id;
  END IF;
 ELSE
  IF TG_OP='INSERT' OR NEW.state IS DISTINCT FROM OLD.state OR NEW.fencing_token IS DISTINCT FROM OLD.fencing_token THEN
   INSERT INTO test_attempt_state_history SELECT NEW.id,COALESCE(MAX(sequence_no),0)+1,NEW.state,NEW.fencing_token,NEW.updated_at FROM test_attempt_state_history WHERE attempt_id=NEW.id;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER record_run_state AFTER INSERT OR UPDATE ON test_run FOR EACH ROW EXECUTE FUNCTION record_test_state();
CREATE TRIGGER record_attempt_state AFTER INSERT OR UPDATE ON test_attempt FOR EACH ROW EXECUTE FUNCTION record_test_state();

CREATE FUNCTION enforce_test_terminal_result() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a test_attempt;
BEGIN
 IF TG_TABLE_NAME='test_result' THEN
  SELECT * INTO a FROM test_attempt WHERE id=NEW.attempt_id;
 ELSE
  SELECT * INTO a FROM test_attempt WHERE id=NEW.id;
 END IF;
 IF (a.finished_at IS NOT NULL) IS DISTINCT FROM EXISTS(SELECT 1 FROM test_result WHERE attempt_id=a.id) THEN
  RAISE EXCEPTION 'Terminal Attempt must have exactly one Result in the same transaction' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER attempt_terminal_result AFTER INSERT OR UPDATE ON test_attempt DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_test_terminal_result();
CREATE CONSTRAINT TRIGGER result_terminal_attempt AFTER INSERT ON test_result DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_test_terminal_result();

CREATE FUNCTION enforce_run_closed_attempts() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.finished_at IS NOT NULL AND EXISTS(SELECT 1 FROM test_attempt WHERE test_run_id=NEW.id AND finished_at IS NULL) THEN
  RAISE EXCEPTION 'Terminal Run cannot leave active Attempts' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER run_closed_attempts AFTER UPDATE ON test_run DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_run_closed_attempts();
