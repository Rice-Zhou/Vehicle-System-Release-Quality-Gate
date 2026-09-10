ALTER TABLE test_run ADD COLUMN terminal_snapshot jsonb, ADD COLUMN input_digest varchar(71),
 ADD COLUMN snapshot_required boolean NOT NULL DEFAULT false;
UPDATE test_run SET snapshot_required=true WHERE finished_at IS NULL;
ALTER TABLE test_run ALTER COLUMN snapshot_required SET DEFAULT true;
ALTER TABLE test_run ADD CONSTRAINT snapshot_digest_pair CHECK (
 (terminal_snapshot IS NULL) = (input_digest IS NULL) AND
 (input_digest IS NULL OR input_digest ~ '^sha256:[0-9a-f]{64}$'));

CREATE FUNCTION guard_run_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
  IF NOT NEW.snapshot_required THEN RAISE EXCEPTION 'New Run requires terminal snapshot' USING ERRCODE='23514'; END IF;
 ELSE
  IF NEW.snapshot_required IS DISTINCT FROM OLD.snapshot_required OR
     (OLD.terminal_snapshot IS NOT NULL AND ROW(NEW.terminal_snapshot,NEW.input_digest) IS DISTINCT FROM ROW(OLD.terminal_snapshot,OLD.input_digest)) THEN
   RAISE EXCEPTION 'Snapshot policy and frozen inputs are immutable' USING ERRCODE='55000';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER guard_run_snapshot BEFORE INSERT OR UPDATE ON test_run FOR EACH ROW EXECUTE FUNCTION guard_run_snapshot();

CREATE FUNCTION require_terminal_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_run test_run;
BEGIN
 SELECT * INTO current_run FROM test_run WHERE id=NEW.id;
 IF current_run.finished_at IS NOT NULL AND current_run.snapshot_required AND
    (current_run.terminal_snapshot IS NULL OR current_run.terminal_snapshot->>'status' IS DISTINCT FROM current_run.state) THEN
  RAISE EXCEPTION 'New terminal Run requires matching frozen Result inputs' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER terminal_run_snapshot AFTER INSERT OR UPDATE ON test_run
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION require_terminal_snapshot();
