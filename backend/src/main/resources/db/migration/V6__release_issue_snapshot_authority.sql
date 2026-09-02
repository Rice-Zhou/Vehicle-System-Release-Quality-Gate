ALTER TABLE issue_sync_run
    ADD COLUMN result_set_mode varchar(10),
    ADD COLUMN filter_reference varchar(255),
    ADD CONSTRAINT ck_issue_sync_run_result_set_mode
        CHECK (result_set_mode IS NULL OR result_set_mode IN ('FULL', 'DELTA'));

ALTER TABLE normalized_issue
    ADD CONSTRAINT uq_normalized_issue_id_source_project
        UNIQUE (id, source_id, project_id);

CREATE TABLE issue_sync_run_item (
    sync_run_id varchar(40) NOT NULL,
    ordinal integer NOT NULL,
    project_id varchar(40) NOT NULL,
    source_id varchar(40) NOT NULL,
    issue_id varchar(40) NOT NULL,
    source_issue_id varchar(255) NOT NULL,
    observed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (sync_run_id, ordinal),
    CONSTRAINT ck_sync_run_item_ordinal CHECK (ordinal >= 0),
    CONSTRAINT uq_sync_run_item_issue UNIQUE (sync_run_id, issue_id),
    CONSTRAINT uq_sync_run_item_source_issue UNIQUE (sync_run_id, source_issue_id),
    CONSTRAINT fk_sync_run_item_run_source_project
        FOREIGN KEY (sync_run_id, source_id, project_id)
        REFERENCES issue_sync_run(id, source_id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sync_run_item_issue_source_project
        FOREIGN KEY (issue_id, source_id, project_id)
        REFERENCES normalized_issue(id, source_id, project_id) ON DELETE RESTRICT
);

CREATE INDEX ix_issue_sync_run_item_issue ON issue_sync_run_item(issue_id);

ALTER TABLE release_issue_snapshot
    ADD COLUMN source_id varchar(40),
    ADD COLUMN source_watermark text,
    ADD COLUMN adapter_version varchar(80),
    ADD COLUMN mapping_version varchar(80),
    ADD COLUMN canonicalization_version varchar(80),
    ADD COLUMN age_policy_version varchar(80),
    ADD COLUMN observed_count integer,
    ADD COLUMN tombstone_count integer,
    ADD COLUMN selected_count integer,
    ADD CONSTRAINT uq_issue_snapshot_run_filter
        UNIQUE (release_id, sync_run_id, filter_reference),
    ADD CONSTRAINT ck_issue_snapshot_counts CHECK (
        (observed_count IS NULL AND tombstone_count IS NULL AND selected_count IS NULL)
        OR (
            observed_count IS NOT NULL AND tombstone_count IS NOT NULL AND selected_count IS NOT NULL
            AND observed_count >= 0 AND tombstone_count >= 0 AND selected_count >= 0
            AND observed_count = tombstone_count + selected_count
        )
    );

CREATE TRIGGER immutable_issue_sync_run_item
    BEFORE UPDATE OR DELETE ON issue_sync_run_item
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();

CREATE FUNCTION seal_terminal_issue_sync_run() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF OLD.status IN ('SUCCEEDED', 'FAILED') AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'terminal issue sync run is immutable' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER seal_terminal_issue_sync_run
    BEFORE UPDATE ON issue_sync_run
    FOR EACH ROW EXECUTE FUNCTION seal_terminal_issue_sync_run();

CREATE FUNCTION validate_release_issue_snapshot_v1() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.canonicalization_version = 'release-issue-snapshot-jcs/v1'
        AND (
            NEW.source_id IS NULL
            OR NEW.source_watermark IS NULL
            OR NEW.adapter_version IS NULL
            OR NEW.mapping_version IS NULL
            OR NEW.age_policy_version IS NULL
            OR NEW.observed_count IS NULL
            OR NEW.tombstone_count IS NULL
            OR NEW.selected_count IS NULL
        ) THEN
        RAISE EXCEPTION 'release issue snapshot v1 metadata is incomplete' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER validate_release_issue_snapshot_v1
    BEFORE INSERT ON release_issue_snapshot
    FOR EACH ROW EXECUTE FUNCTION validate_release_issue_snapshot_v1();
