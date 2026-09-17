-- Runs once, on first start of an empty data directory (docker-entrypoint-initdb.d).
--
-- Two schemas on purpose:
--   lab_mysql     the app (docker compose up lab-app) writes here
--   lab_mysql_it  the integration suite owns this one, so pointing the tests at a
--                 long-lived node never disturbs whatever you left in lab_mysql
CREATE DATABASE IF NOT EXISTS lab_mysql     DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS lab_mysql_it  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- A non-root account for the app. The test suite deliberately stays on root: reading
-- performance_schema.data_locks, SHOW ENGINE INNODB STATUS and flipping session
-- binlog_format all need privileges an application account should not have.
CREATE USER IF NOT EXISTS 'lab'@'%' IDENTIFIED BY 'lab';
GRANT ALL PRIVILEGES ON lab_mysql.*    TO 'lab'@'%';
GRANT ALL PRIVILEGES ON lab_mysql_it.* TO 'lab'@'%';
FLUSH PRIVILEGES;
