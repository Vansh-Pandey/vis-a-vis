#!/bin/zsh
# Local development databases for vinsight-api (MariaDB from Homebrew). Run once.
# Two accounts per database, so the running app can never change the schema:
#   vinsight_migrator  - owns the schema, used only by Flyway at start-up
#   vinsight_app       - SELECT/INSERT/UPDATE/DELETE only
# Passwords are random and written to .env.local (git-ignored).
set -e
cd "$(dirname "$0")/.."
[ -f .env.local ] && { echo ".env.local already exists; delete it to recreate the databases."; exit 1; }
MIG=$(openssl rand -hex 24); APP=$(openssl rand -hex 24)
mariadb -u "$(whoami)" <<SQL
CREATE DATABASE IF NOT EXISTS vinsight_dev  CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci;
CREATE DATABASE IF NOT EXISTS vinsight_test CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci;
CREATE OR REPLACE USER 'vinsight_migrator'@'localhost' IDENTIFIED BY '$MIG';
CREATE OR REPLACE USER 'vinsight_app'@'localhost' IDENTIFIED BY '$APP';
GRANT ALL PRIVILEGES ON vinsight_dev.*  TO 'vinsight_migrator'@'localhost';
GRANT ALL PRIVILEGES ON vinsight_test.* TO 'vinsight_migrator'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON vinsight_dev.*  TO 'vinsight_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE ON vinsight_test.* TO 'vinsight_app'@'localhost';
FLUSH PRIVILEGES;
SQL
cat > .env.local <<ENV
VINSIGHT_DB_MIGRATOR_PASSWORD=$MIG
VINSIGHT_DB_APP_PASSWORD=$APP
VINSIGHT_SECRET_KEY=$(openssl rand -base64 32)
ENV
chmod 600 .env.local
echo "Databases vinsight_dev and vinsight_test are ready; credentials are in .env.local"
