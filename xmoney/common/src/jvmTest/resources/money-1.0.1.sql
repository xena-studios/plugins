-- An xMoney 1.0.1 SQLite database, as its core (core/db/Database.java) and its Schema.java made
-- it: the meta table, schema 1, and the three tables, with two players, a town's bank made by
-- name through Vault, and their ledger rows (each first row of a transaction with its commit_id).
CREATE TABLE IF NOT EXISTS xmoney_meta (name VARCHAR(64) PRIMARY KEY, value VARCHAR(255) NOT NULL);
INSERT INTO xmoney_meta (name, value) VALUES ('schema', '1');
CREATE TABLE IF NOT EXISTS xmoney_accounts (uuid CHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL, player SMALLINT NOT NULL, created BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS xmoney_balances (uuid CHAR(36) NOT NULL, currency VARCHAR(32) NOT NULL, balance BIGINT NOT NULL, PRIMARY KEY (uuid, currency));
CREATE TABLE IF NOT EXISTS xmoney_transactions (id INTEGER PRIMARY KEY, time BIGINT NOT NULL, uuid CHAR(36) NOT NULL, currency VARCHAR(32) NOT NULL, amount BIGINT NOT NULL, balance BIGINT NOT NULL, kind VARCHAR(16) NOT NULL, source VARCHAR(64) NOT NULL, reason VARCHAR(100) NOT NULL, other CHAR(36), commit_id BIGINT);
CREATE INDEX xmoney_transactions_uuid ON xmoney_transactions (uuid, id);
CREATE INDEX xmoney_transactions_time ON xmoney_transactions (time);
CREATE UNIQUE INDEX xmoney_transactions_commit ON xmoney_transactions (commit_id);
INSERT INTO xmoney_accounts VALUES ('8667ba71-b85a-4004-af54-457a9734eed7', 'Steve', 1, 1763500000000);
INSERT INTO xmoney_accounts VALUES ('ec561538-f3fd-461d-aff5-086b22154bce', 'Alex', 1, 1763500001000);
INSERT INTO xmoney_accounts VALUES ('9b0a1b47-8a43-3d32-9f2b-6f7b6c0a5e11', 'town-Spawn', 0, 1763500002000);
INSERT INTO xmoney_balances VALUES ('8667ba71-b85a-4004-af54-457a9734eed7', 'default', 1250000);
INSERT INTO xmoney_balances VALUES ('ec561538-f3fd-461d-aff5-086b22154bce', 'default', 49950);
INSERT INTO xmoney_balances VALUES ('9b0a1b47-8a43-3d32-9f2b-6f7b6c0a5e11', 'default', 500000);
INSERT INTO xmoney_transactions VALUES (1, 1763500000000, '8667ba71-b85a-4004-af54-457a9734eed7', 'default', 1000000, 1000000, 'START', 'xMoney', 'starting balance', NULL, 4711);
INSERT INTO xmoney_transactions VALUES (2, 1763500005000, '8667ba71-b85a-4004-af54-457a9734eed7', 'default', 300000, 1300000, 'GIVE', 'Console', '', NULL, 4712);
INSERT INTO xmoney_transactions VALUES (3, 1763500006000, '8667ba71-b85a-4004-af54-457a9734eed7', 'default', -50050, 1249950, 'PAY', 'Steve', '', 'ec561538-f3fd-461d-aff5-086b22154bce', 4713);
INSERT INTO xmoney_transactions VALUES (4, 1763500006000, 'ec561538-f3fd-461d-aff5-086b22154bce', 'default', 50050, 50050, 'PAY', 'Steve', '', '8667ba71-b85a-4004-af54-457a9734eed7', NULL);
INSERT INTO xmoney_transactions VALUES (5, 1763500007000, 'ec561538-f3fd-461d-aff5-086b22154bce', 'default', -100, 49950, 'PLUGIN', 'ChestShop', 'bought dirt', NULL, 4714);
INSERT INTO xmoney_transactions VALUES (6, 1763500008000, '8667ba71-b85a-4004-af54-457a9734eed7', 'default', 50, 1250000, 'PLUGIN', 'Towny', '', NULL, 4715);
INSERT INTO xmoney_transactions VALUES (7, 1763500009000, '9b0a1b47-8a43-3d32-9f2b-6f7b6c0a5e11', 'default', 500000, 500000, 'PLUGIN', 'Towny', 'upkeep', NULL, 4716);
