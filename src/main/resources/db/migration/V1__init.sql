CREATE TABLE import_batch (
    sha256 CHAR(64) PRIMARY KEY,
    filename TEXT NOT NULL,
    row_count BIGINT NOT NULL CHECK (row_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE election_vote (
    election_year SMALLINT NOT NULL DEFAULT 2026,
    election_round SMALLINT NOT NULL CHECK (election_round IN (1, 2)),
    uf CHAR(2) NOT NULL,
    municipality_code VARCHAR(12) NOT NULL,
    municipality VARCHAR(180) NOT NULL,
    candidate_number INTEGER NOT NULL,
    candidate_name VARCHAR(180) NOT NULL,
    votes BIGINT NOT NULL CHECK (votes >= 0),
    source_sha256 CHAR(64) NOT NULL REFERENCES import_batch(sha256),
    PRIMARY KEY (election_year, election_round, uf, municipality_code, candidate_number)
);

CREATE INDEX ix_votes_round_uf ON election_vote(election_round, uf);
CREATE INDEX ix_votes_municipality ON election_vote(uf, municipality_code);
CREATE INDEX ix_votes_candidate ON election_vote(election_round, candidate_number);
