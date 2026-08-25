ALTER TABLE manifest_revision
    ADD COLUMN locked_validation_id varchar(40);

ALTER TABLE manifest_validation
    ADD CONSTRAINT uq_validation_id_manifest UNIQUE (id, manifest_id);

ALTER TABLE manifest_revision
    ADD CONSTRAINT fk_manifest_locked_validation
    FOREIGN KEY (locked_validation_id, id) REFERENCES manifest_validation(id, manifest_id);

CREATE FUNCTION reject_locked_manifest_validation_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM manifest_revision
        WHERE id = NEW.manifest_id AND state = 'LOCKED'
    ) THEN
        RAISE EXCEPTION 'validation of locked manifest % is immutable', NEW.manifest_id
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER immutable_locked_manifest_validation
    BEFORE INSERT ON manifest_validation
    FOR EACH ROW EXECUTE FUNCTION reject_locked_manifest_validation_insert();
