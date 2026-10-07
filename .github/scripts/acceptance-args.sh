#!/usr/bin/env bash
# Instrumentation arguments for the acceptance suite (app/src/androidTest/.../acceptance), written
# to $GITHUB_ENV as ACCEPTANCE_ARGS. Reads the environment's test fixture users from SSM, where
# mootmaker-api publishes them in ephemeral environments only. Each value is base64-encoded so
# that no password character can upset the shell the emulator runner starts Gradle in, and every
# password is masked in the log.
#
# Usage: acceptance-args.sh <environment>
set -euo pipefail

environment="$1"
api="/mootmaker/${environment}/api"

ssm() {
  aws ssm get-parameter --name "$1" --with-decryption --query Parameter.Value --output text
}

args="-Pandroid.testInstrumentationRunnerArguments.package=com.mootmaker.app.acceptance"
add() {
  local encoded
  encoded="$(printf '%s' "$2" | base64 -w0)"
  args+=" -Pandroid.testInstrumentationRunnerArguments.$1=${encoded}"
}

add accEnvironment "${environment}"
for role in admin standard no-person; do
  email="$(ssm "${api}/test-fixtures/users/${role}/email")"
  password="$(ssm "${api}/test-fixtures/users/${role}/password")"
  echo "::add-mask::${password}"
  echo "::add-mask::$(printf '%s' "${password}" | base64 -w0)"
  case "${role}" in
    admin) prefix=accAdmin ;;
    standard) prefix=accStandard ;;
    no-person) prefix=accNoPerson ;;
  esac
  add "${prefix}Email" "${email}"
  add "${prefix}Password" "${password}"
done

echo "ACCEPTANCE_ARGS=${args}" >> "${GITHUB_ENV}"
