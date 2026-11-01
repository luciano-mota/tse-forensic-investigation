CREATE TABLE section_turnout (
  election_year SMALLINT NOT NULL DEFAULT 2026,
  election_round SMALLINT NOT NULL CHECK (election_round IN (1, 2)),
  uf CHAR(2) NOT NULL,
  municipality_code VARCHAR(12) NOT NULL,
  municipality VARCHAR(180) NOT NULL,
  zone INTEGER NOT NULL CHECK (zone >= 0),
  section INTEGER NOT NULL CHECK (section >= 0),
  registered BIGINT NOT NULL CHECK (registered >= 0),
  turnout BIGINT NOT NULL CHECK (turnout >= 0),
  abstentions BIGINT NOT NULL CHECK (abstentions >= 0),
  source_sha256 CHAR(64) NOT NULL REFERENCES import_batch(sha256),
  PRIMARY KEY(election_year, election_round, uf, municipality_code, zone, section)
);
CREATE INDEX ix_section_turnout_region ON section_turnout(election_round, uf, municipality_code);

CREATE TABLE section_biometric (
  election_year SMALLINT NOT NULL DEFAULT 2026,
  election_round SMALLINT NOT NULL CHECK(election_round IN (1,2)),
  uf CHAR(2) NOT NULL,
  municipality_code VARCHAR(12) NOT NULL,
  zone INTEGER NOT NULL CHECK(zone >= 0),
  section INTEGER NOT NULL CHECK(section >= 0),
  biometric_recognized BIGINT NOT NULL CHECK (biometric_recognized >= 0),
  no_biometric_enrollment BIGINT NOT NULL CHECK (no_biometric_enrollment >= 0),
  biometric_failed BIGINT NOT NULL CHECK (biometric_failed >= 0),
  operator_authorizations BIGINT,
  source_sha256 CHAR(64) NOT NULL REFERENCES import_batch(sha256),
  PRIMARY KEY(election_year, election_round, uf, municipality_code, zone, section),
  CHECK (operator_authorizations IS NULL OR operator_authorizations >= 0)
);
CREATE INDEX ix_biometric_region ON section_biometric(election_round, uf, municipality_code);

CREATE TABLE section_vote (
  election_year SMALLINT NOT NULL DEFAULT 2026,
  election_round SMALLINT NOT NULL CHECK(election_round IN (1,2)),
  uf CHAR(2) NOT NULL,
  municipality_code VARCHAR(12) NOT NULL,
  zone INTEGER NOT NULL CHECK(zone >= 0),
  section INTEGER NOT NULL CHECK(section >= 0),
  candidate_number INTEGER NOT NULL,
  votes BIGINT NOT NULL CHECK(votes >= 0),
  source_sha256 CHAR(64) NOT NULL REFERENCES import_batch(sha256),
  PRIMARY KEY(election_year, election_round, uf, municipality_code, zone, section, candidate_number)
);
CREATE INDEX ix_section_vote_region ON section_vote(election_round, uf, municipality_code);
