-- An xBans 1.0.1 database, made with 1.x's own statements (the old core's meta table, then
-- xbans/src/main/java/.../storage/Schema.java's step 1, on SQLite) and rows as 1.x wrote them:
-- Steve banned for good by the console, Alex's mute lifted, a kick, an IP ban, players and IPs.
CREATE TABLE IF NOT EXISTS xbans_meta (name VARCHAR(64) PRIMARY KEY, value VARCHAR(255) NOT NULL);
INSERT INTO xbans_meta (name, value) VALUES ('schema', '1');
INSERT INTO xbans_meta (name, value) VALUES ('ip-mode', 'raw');
CREATE TABLE xbans_punishments (id INTEGER PRIMARY KEY, type TINYINT NOT NULL, uuid CHAR(36), name VARCHAR(64) NOT NULL, ip VARCHAR(64), ip_based TINYINT NOT NULL, reason VARCHAR(1024) NOT NULL, staff_uuid CHAR(36), staff_name VARCHAR(64) NOT NULL, staff_weight INT NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL, scope VARCHAR(64) NOT NULL, origin VARCHAR(64) NOT NULL, silent TINYINT NOT NULL, template VARCHAR(64), imported VARCHAR(96));
CREATE INDEX xbans_punishments_uuid ON xbans_punishments (uuid, id);
CREATE INDEX xbans_punishments_staff ON xbans_punishments (staff_uuid, id);
CREATE INDEX xbans_punishments_ip ON xbans_punishments (ip, id);
CREATE INDEX xbans_punishments_active ON xbans_punishments (type, expires);
CREATE INDEX xbans_punishments_imported ON xbans_punishments (imported);
CREATE TABLE xbans_removals (punishment BIGINT NOT NULL PRIMARY KEY, staff_uuid CHAR(36), staff_name VARCHAR(64) NOT NULL, removed BIGINT NOT NULL, reason VARCHAR(1024) NOT NULL);
CREATE TABLE xbans_players (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, weight INT NOT NULL, exempt TINYINT NOT NULL);
CREATE INDEX xbans_players_name ON xbans_players (name);
CREATE TABLE xbans_ips (uuid CHAR(36) NOT NULL, ip VARCHAR(64) NOT NULL, first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, PRIMARY KEY (uuid, ip));
CREATE INDEX xbans_ips_ip ON xbans_ips (ip);
INSERT INTO xbans_punishments (type, uuid, name, ip, ip_based, reason, staff_uuid, staff_name, created, expires, scope, origin, silent, template, imported, staff_weight) VALUES (1, '5627dd98-e6be-3c21-b8a8-e92344183641', 'Steve', NULL, 0, 'Hacking', NULL, 'Console', 1763500000000, 0, '*', 'server', 0, 'hacking', NULL, 2147483647);
INSERT INTO xbans_punishments (type, uuid, name, ip, ip_based, reason, staff_uuid, staff_name, created, expires, scope, origin, silent, template, imported, staff_weight) VALUES (2, '7a1e3a7d-1b2c-3d4e-8f90-112233445566', 'Alex', NULL, 0, 'Spamming', 'b0c1d2e3-f4a5-4b6c-9d7e-8f9012345678', 'Mod', 1763500001000, 1763503601000, '*', 'server', 1, 'spam', NULL, 10);
INSERT INTO xbans_removals (punishment, staff_uuid, staff_name, removed, reason) VALUES (2, NULL, 'Console', 1763500002000, 'appealed');
INSERT INTO xbans_punishments (type, uuid, name, ip, ip_based, reason, staff_uuid, staff_name, created, expires, scope, origin, silent, template, imported, staff_weight) VALUES (4, '7a1e3a7d-1b2c-3d4e-8f90-112233445566', 'Alex', NULL, 0, 'Calm down', NULL, 'Console', 1763500003000, 0, '*', 'server', 0, NULL, NULL, 2147483647);
INSERT INTO xbans_punishments (type, uuid, name, ip, ip_based, reason, staff_uuid, staff_name, created, expires, scope, origin, silent, template, imported, staff_weight) VALUES (1, NULL, '198.51.x.x', '198.51.100.7', 1, 'VPN abuse', NULL, 'Console', 1763500004000, 0, '*', 'server', 0, NULL, NULL, 2147483647);
INSERT INTO xbans_players (uuid, name, first_seen, last_seen, weight, exempt) VALUES ('5627dd98-e6be-3c21-b8a8-e92344183641', 'Steve', 1763400000000, 1763499999000, 0, 0);
INSERT INTO xbans_players (uuid, name, first_seen, last_seen, weight, exempt) VALUES ('7a1e3a7d-1b2c-3d4e-8f90-112233445566', 'Alex', 1763400000000, 1763500000000, 0, 0);
INSERT INTO xbans_ips (uuid, ip, first_seen, last_seen) VALUES ('5627dd98-e6be-3c21-b8a8-e92344183641', '203.0.113.9', 1763400000000, 1763499999000);
INSERT INTO xbans_ips (uuid, ip, first_seen, last_seen) VALUES ('7a1e3a7d-1b2c-3d4e-8f90-112233445566', '203.0.113.9', 1763400000000, 1763500000000);
