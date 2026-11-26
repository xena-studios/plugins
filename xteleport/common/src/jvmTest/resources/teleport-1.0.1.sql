-- An xTeleport 1.0.1 SQLite database, as its core (core/db/Database.java) and its Schema.java made
-- it: the meta table at schema 1 and the four tables, with two players' homes (one with TPA
-- requests off), two warps and the spawn.
CREATE TABLE IF NOT EXISTS xteleport_meta (name VARCHAR(64) PRIMARY KEY, value VARCHAR(255) NOT NULL);
INSERT INTO xteleport_meta (name, value) VALUES ('schema', '1');
CREATE TABLE IF NOT EXISTS xteleport_homes (owner CHAR(36) NOT NULL, name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '', world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, created BIGINT NOT NULL, PRIMARY KEY (owner, name_key));
CREATE TABLE IF NOT EXISTS xteleport_warps (name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '', world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, created BIGINT NOT NULL, PRIMARY KEY (server, name_key));
CREATE TABLE IF NOT EXISTS xteleport_spawns (name_key VARCHAR(32) NOT NULL, name VARCHAR(32) NOT NULL, server VARCHAR(64) NOT NULL DEFAULT '', world VARCHAR(255) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, created BIGINT NOT NULL, PRIMARY KEY (server, name_key));
CREATE TABLE IF NOT EXISTS xteleport_players (uuid CHAR(36) NOT NULL PRIMARY KEY, tpa_off INT NOT NULL DEFAULT 0);
INSERT INTO xteleport_homes VALUES ('8667ba71-b85a-4004-af54-457a9734eed7', 'base', 'Base', '', 'world', 100.5, 64.0, -20.5, 90.0, 10.0, 1763500000000);
INSERT INTO xteleport_homes VALUES ('8667ba71-b85a-4004-af54-457a9734eed7', 'mine', 'mine', '', 'world_nether', -8.25, 33.0, 12.75, 0.0, 0.0, 1763500100000);
INSERT INTO xteleport_homes VALUES ('ec561538-f3fd-461d-aff5-086b22154bce', 'home', 'home', '', 'world', 0.5, 70.0, 0.5, 180.0, -5.0, 1763500200000);
INSERT INTO xteleport_warps VALUES ('shop', 'Shop', '', 'world', 10.5, 65.0, 10.5, 0.0, 0.0, 1763500300000);
INSERT INTO xteleport_warps VALUES ('arena', 'arena', '', 'world', -50.5, 70.0, 30.5, 45.0, 0.0, 1763500400000);
INSERT INTO xteleport_spawns VALUES ('spawn', 'spawn', '', 'world', 0.5, 65.0, 0.5, 0.0, 0.0, 1763500500000);
INSERT INTO xteleport_players VALUES ('ec561538-f3fd-461d-aff5-086b22154bce', 1);
