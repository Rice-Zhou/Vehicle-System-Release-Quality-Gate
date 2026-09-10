ALTER TABLE test_attempt ADD CONSTRAINT uq_attempt_evidence_binding UNIQUE(id,test_run_id,project_id,agent_id,device_id);
CREATE TABLE evidence_upload_session (
 id varchar(40) PRIMARY KEY, evidence_id varchar(40) NOT NULL UNIQUE,
 attempt_id uuid NOT NULL, test_run_id varchar(40) NOT NULL, release_id varchar(40) NOT NULL,
 project_id varchar(40) NOT NULL, agent_id varchar(40) NOT NULL, device_id varchar(128) NOT NULL,
 lease_id varchar(40) NOT NULL, fencing_token bigint NOT NULL CHECK(fencing_token>0),
 request jsonb NOT NULL, state varchar(30) NOT NULL CHECK(state IN ('PENDING_UPLOAD','UPLOADING','VERIFYING','AVAILABLE','REJECTED','EXPIRED')),
 expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL, metadata jsonb,
 sensitivity varchar(20) NOT NULL CHECK(sensitivity IN ('GENERAL','RESTRICTED','HIGH')),
 retention_until timestamptz, legal_hold boolean NOT NULL DEFAULT false,
 FOREIGN KEY(attempt_id,test_run_id,project_id,agent_id,device_id) REFERENCES test_attempt(id,test_run_id,project_id,agent_id,device_id),
 FOREIGN KEY(test_run_id,release_id) REFERENCES test_run(id,release_id),
 UNIQUE(evidence_id,project_id), CHECK((state='AVAILABLE')=(metadata IS NOT NULL)),
 CHECK(expires_at=created_at+interval '5 minutes')
);
CREATE INDEX ix_evidence_attempt ON evidence_upload_session(attempt_id);
CREATE FUNCTION guard_local_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Evidence history cannot be deleted' USING ERRCODE='55000'; END IF;
 IF ROW(NEW.id,NEW.evidence_id,NEW.attempt_id,NEW.test_run_id,NEW.release_id,NEW.project_id,NEW.agent_id,NEW.device_id,NEW.lease_id,NEW.fencing_token,NEW.request,NEW.expires_at,NEW.created_at,NEW.sensitivity)
 IS DISTINCT FROM ROW(OLD.id,OLD.evidence_id,OLD.attempt_id,OLD.test_run_id,OLD.release_id,OLD.project_id,OLD.agent_id,OLD.device_id,OLD.lease_id,OLD.fencing_token,OLD.request,OLD.expires_at,OLD.created_at,OLD.sensitivity)
 OR (OLD.state IN ('AVAILABLE','REJECTED','EXPIRED') AND ROW(NEW.state,NEW.metadata) IS DISTINCT FROM ROW(OLD.state,OLD.metadata)) THEN
 RAISE EXCEPTION 'Evidence identity and completed facts are immutable' USING ERRCODE='55000'; END IF;
 IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
   (OLD.state='PENDING_UPLOAD' AND NEW.state IN ('UPLOADING','VERIFYING','EXPIRED')) OR
   (OLD.state='UPLOADING' AND NEW.state IN ('VERIFYING','EXPIRED')) OR
   (OLD.state='VERIFYING' AND NEW.state IN ('AVAILABLE','REJECTED','EXPIRED'))
 ) THEN RAISE EXCEPTION 'Invalid Evidence state transition' USING ERRCODE='23514'; END IF;
 IF NEW.state='AVAILABLE' AND OLD.state<>'AVAILABLE' AND (
   NOT EXISTS(SELECT 1 FROM test_attempt a JOIN test_run r ON r.id=a.test_run_id
      WHERE a.id=NEW.attempt_id AND a.finished_at IS NULL AND r.finished_at IS NULL AND a.lease_id=NEW.lease_id AND a.fencing_token=NEW.fencing_token)
   OR NEW.metadata->>'evidenceId' IS DISTINCT FROM NEW.evidence_id
   OR NEW.metadata->>'attemptId' IS DISTINCT FROM NEW.attempt_id::text
   OR NEW.metadata->>'testRunId' IS DISTINCT FROM NEW.test_run_id
   OR NEW.metadata->>'releaseId' IS DISTINCT FROM NEW.release_id
   OR NEW.metadata->>'payloadChecksum' IS DISTINCT FROM NEW.request->>'payloadChecksum'
   OR NEW.metadata->>'sizeBytes' IS DISTINCT FROM NEW.request->>'sizeBytes'
   OR NEW.metadata->>'contentType' IS DISTINCT FROM NEW.request->>'contentType'
   OR NEW.metadata->>'sensitivity' IS DISTINCT FROM NEW.sensitivity
 ) THEN RAISE EXCEPTION 'Evidence Metadata must match its writable Session' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_local_evidence BEFORE UPDATE OR DELETE ON evidence_upload_session FOR EACH ROW EXECUTE FUNCTION guard_local_evidence();
CREATE TABLE evidence_integrity_observation (
 sequence_no bigserial PRIMARY KEY, evidence_id varchar(40) NOT NULL REFERENCES evidence_upload_session(evidence_id),
 code varchar(50) NOT NULL, observed_at timestamptz NOT NULL
);
CREATE TRIGGER immutable_evidence_observation BEFORE UPDATE OR DELETE ON evidence_integrity_observation FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
CREATE TABLE evidence_download_grant (
 id varchar(40) PRIMARY KEY, actor_id varchar(40) NOT NULL REFERENCES principal(id),
 project_id varchar(40) NOT NULL, evidence_id varchar(40) NOT NULL, purpose varchar(1000) NOT NULL,
 expires_at timestamptz NOT NULL,
 FOREIGN KEY(evidence_id,project_id) REFERENCES evidence_upload_session(evidence_id,project_id)
);
CREATE TRIGGER immutable_evidence_grant BEFORE UPDATE OR DELETE ON evidence_download_grant FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
