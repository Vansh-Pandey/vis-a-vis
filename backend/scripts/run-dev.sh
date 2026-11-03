#!/bin/zsh
# Runs the API on http://localhost:8080 against the local vinsight_dev database.
# DEV mode: cookies without the Secure flag (plain http), two-step login optional, reset links returned in API answers.
cd "$(dirname "$0")/.."
set -a; source .env.local; set +a
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}
export VINSIGHT_ENV=DEV VINSIGHT_SECURE_COOKIES=false VINSIGHT_SITE_ORIGIN=${VINSIGHT_SITE_ORIGIN:-http://localhost:3000}
exec mvn -q spring-boot:run
