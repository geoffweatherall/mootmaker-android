#!/usr/bin/env bash
# Starts email-helper/server.mjs in the background on 127.0.0.1:8787 and waits until it answers.
# It serves real Cognito verification codes from mootmaker-email-testing's shared queue to the
# emulator (through adb reverse) and to Maestro. Needs AWS credentials that can read the queue,
# already in the environment, and Node. The pid goes to $RUNNER_TEMP/email-helper.pid and the log
# to $RUNNER_TEMP/email-helper.log.
set -euo pipefail

cd "$(dirname "$0")/../../email-helper"
npm ci --no-audit --no-fund

queue_url="$(aws ssm get-parameter --name /mootmaker/email-testing/sqs-queue-url --query Parameter.Value --output text)"
SQS_QUEUE_URL="${queue_url}" nohup node server.mjs > "${RUNNER_TEMP}/email-helper.log" 2>&1 &
echo $! > "${RUNNER_TEMP}/email-helper.pid"

for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8787/health > /dev/null 2>&1; then
    echo "Email helper is up"
    exit 0
  fi
  sleep 1
done
echo "::error title=Email helper did not start::$(tail -c 500 "${RUNNER_TEMP}/email-helper.log")"
exit 1
