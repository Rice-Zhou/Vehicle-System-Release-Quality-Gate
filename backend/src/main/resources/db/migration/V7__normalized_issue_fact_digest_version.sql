ALTER TABLE normalized_issue
    ADD COLUMN fact_digest_version varchar(40),
    ADD CONSTRAINT ck_normalized_issue_fact_digest_version
        CHECK (
            fact_digest_version IS NULL
            OR fact_digest_version = 'normalized-issue-facts/v1'
        );

CREATE FUNCTION require_normalized_issue_fact_digest_version()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    IF NEW.fact_digest_version IS DISTINCT FROM 'normalized-issue-facts/v1' THEN
        RAISE EXCEPTION 'new normalized issue requires fact digest version normalized-issue-facts/v1'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER require_normalized_issue_fact_digest_version
BEFORE INSERT ON normalized_issue
FOR EACH ROW
EXECUTE FUNCTION require_normalized_issue_fact_digest_version();
