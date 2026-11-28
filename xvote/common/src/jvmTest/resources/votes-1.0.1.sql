-- xVote 1.0.1's SQLite database, as its Schema.STEPS make it (schema 2, "xvote_" prefix) and its
-- Ledger writes it: Steve with three counted votes (the last unpaid), Alex's vote waiting for his
-- name, a held item and the party's count. Times are 2026-01-04, a Sunday, in UTC.
CREATE TABLE IF NOT EXISTS xvote_meta (name VARCHAR(64) PRIMARY KEY, value VARCHAR(255) NOT NULL)
CREATE TABLE xvote_votes (id INTEGER PRIMARY KEY, uuid VARCHAR(36), name VARCHAR(64) NOT NULL, name_key VARCHAR(64) NOT NULL, site VARCHAR(64) NOT NULL, address VARCHAR(64) NOT NULL, list_time VARCHAR(64) NOT NULL, source VARCHAR(16) NOT NULL, at BIGINT NOT NULL, number INT, streak INT, first_today INT, claim VARCHAR(40), paid_at BIGINT)
CREATE INDEX xvote_votes_player ON xvote_votes (uuid, at)
CREATE INDEX xvote_votes_name ON xvote_votes (name_key, site)
CREATE INDEX xvote_votes_at ON xvote_votes (at)
CREATE TABLE xvote_voters (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, total INT NOT NULL, streak INT NOT NULL, best_streak INT NOT NULL, last_day INT NOT NULL, last_vote BIGINT NOT NULL)
CREATE TABLE xvote_held (id INTEGER PRIMARY KEY, uuid VARCHAR(36) NOT NULL, item BLOB NOT NULL, created BIGINT NOT NULL)
CREATE INDEX xvote_held_player ON xvote_held (uuid)
CREATE TABLE xvote_state (name VARCHAR(32) PRIMARY KEY, value BIGINT NOT NULL)
INSERT INTO xvote_state (name, value) VALUES ('party_votes', 0)
INSERT INTO xvote_state (name, value) VALUES ('party_since', 0)
INSERT INTO xvote_meta (name, value) VALUES ('schema', '2')
INSERT INTO xvote_votes (uuid, name, name_key, site, address, list_time, source, at, number, streak, first_today, claim, paid_at) VALUES ('53746576-6500-0000-0000-000000000000', 'Steve', 'steve', 'PlanetMinecraft.com', '', '1767427200', 'VOTIFIER_V1', 1767427200000, 1, 1, 1, '0a1b2c3d4e5f-1', 1767427200000)
INSERT INTO xvote_votes (uuid, name, name_key, site, address, list_time, source, at, number, streak, first_today, claim, paid_at) VALUES ('53746576-6500-0000-0000-000000000000', 'Steve', 'steve', 'PlanetMinecraft.com', '', '1767513600', 'VOTIFIER_V2', 1767513600000, 2, 2, 1, '0a1b2c3d4e5f-2', 1767513600000)
INSERT INTO xvote_votes (uuid, name, name_key, site, address, list_time, source, at, number, streak, first_today, claim, paid_at) VALUES ('53746576-6500-0000-0000-000000000000', 'Steve', 'steve', 'Minecraft-MP.com', '', '1767517200', 'VOTIFIER_V2', 1767517200000, 3, 2, 0, NULL, NULL)
INSERT INTO xvote_votes (uuid, name, name_key, site, address, list_time, source, at, number, streak, first_today, claim, paid_at) VALUES (NULL, 'Alex', 'alex', 'PlanetMinecraft.com', '', '1767517300', 'VOTIFIER_V1', 1767517300000, NULL, NULL, NULL, NULL, NULL)
INSERT INTO xvote_voters (uuid, name, total, streak, best_streak, last_day, last_vote) VALUES ('53746576-6500-0000-0000-000000000000', 'Steve', 3, 2, 2, 20457, 1767517200000)
INSERT INTO xvote_held (uuid, item, created) VALUES ('53746576-6500-0000-0000-000000000000', X'6469616d6f6e642031', 1767517200000)
UPDATE xvote_state SET value = 3 WHERE name = 'party_votes'
UPDATE xvote_state SET value = 1767427200000 WHERE name = 'party_since'
