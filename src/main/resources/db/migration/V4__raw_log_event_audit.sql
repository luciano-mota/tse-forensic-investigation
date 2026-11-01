CREATE TABLE section_log_audit (
  election_year SMALLINT NOT NULL DEFAULT 2026,
  election_round SMALLINT NOT NULL CHECK(election_round IN(1,2)),
  uf CHAR(2) NOT NULL,
  municipality_code VARCHAR(12) NOT NULL,
  zone INTEGER NOT NULL,
  section INTEGER NOT NULL,
  votes_computed BIGINT NOT NULL CHECK(votes_computed>=0),
  biometric_recognized BIGINT NOT NULL CHECK(biometric_recognized>=0),
  biometric_failed BIGINT NOT NULL CHECK(biometric_failed>=0),
  no_enrollment BIGINT NOT NULL CHECK(no_enrollment>=0),
  unclassified BIGINT NOT NULL CHECK(unclassified>=0),
  after_17_local BIGINT NOT NULL CHECK(after_17_local>=0),
  log_files INTEGER NOT NULL CHECK(log_files>0),
  source_sha256 CHAR(64) NOT NULL REFERENCES import_batch(sha256),
  PRIMARY KEY(election_year,election_round,uf,municipality_code,zone,section)
);
CREATE INDEX ix_section_log_region ON section_log_audit(election_round,uf,municipality_code);
