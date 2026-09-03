ALTER TABLE issue_commit_edge_revision
    ALTER COLUMN source_reference TYPE varchar(1024),
    ALTER COLUMN proof_reference TYPE varchar(1024);

ALTER TABLE commit_build_edge_revision
    ALTER COLUMN source_reference TYPE varchar(1024),
    ALTER COLUMN proof_reference TYPE varchar(1024);

ALTER TABLE build_artifact_edge_revision
    ALTER COLUMN source_reference TYPE varchar(1024),
    ALTER COLUMN proof_reference TYPE varchar(1024);
