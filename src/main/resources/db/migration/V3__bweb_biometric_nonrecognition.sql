CREATE TABLE bweb_biometric_nonrecognition (
    election_year SMALLINT NOT NULL DEFAULT 2026,
    election_round SMALLINT NOT NULL CHECK (election_round IN (1,2)),
    uf CHAR(2) NOT NULL,
    municipality_code VARCHAR(12) NOT NULL,
    zone INTEGER NOT NULL,
    section INTEGER NOT NULL,
    turnout BIGINT NOT NULL CHECK (turnout >= 0),
    not_recognized BIGINT NOT NULL CHECK (not_recognized >= 0),
    source_sha256 CHAR(64) NOT NULL REFERENCES import_batch(sha256),
    PRIMARY KEY (election_year,election_round,uf,municipality_code,zone,section)
);
CREATE INDEX ix_bweb_nonrecognition_region
    ON bweb_biometric_nonrecognition(election_round,uf,municipality_code);
