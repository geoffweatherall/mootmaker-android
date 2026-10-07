#!/usr/bin/env python3
"""Instrumentation arguments for ProductionDemoE2eTest, read from production's env-config.js.

Writes them to $GITHUB_ENV as E2E_ARGS. Interim: once the webapp publishes mobile-config.json with the Android client id, the test should
fetch that itself and this script goes away. Leaves E2E_ARGS empty (so the test skips, with a
warning annotation) if it can't read the file. The demo credentials are public: the website shows them to every visitor.
"""
import json
import re
import os
import urllib.request

try:
    text = urllib.request.urlopen("https://www.mootmaker.com/env-config.js", timeout=20).read().decode()
    config = json.loads(re.search(r"\{.*\}", text, re.S).group(0))
    args = {
        "e2eGraphqlUrl": config["GRAPHQL_API_URL"],
        "e2eUserPoolId": config["COGNITO_USER_POOL_ID"],
        "e2eClientId": config["COGNITO_CLIENT_ID"],
        "e2eEmail": config["DEMO_USER_EMAIL"],
        "e2ePassword": config["DEMO_USER_PASSWORD"],
    }
    if any(re.search(r"\s", value) for value in args.values()):
        raise ValueError("a value contains whitespace, which the emulator runner's shell would split")
except Exception as error:  # noqa: BLE001 - any failure means "skip", reported as a warning
    print(f"::warning title=e2e skipped::Could not read production env-config.js: {error}", flush=True)
    args = {}
line = " ".join(f"-Pandroid.testInstrumentationRunnerArguments.{k}={v}" for k, v in args.items())
with open(os.environ["GITHUB_ENV"], "a") as env:
    env.write(f"E2E_ARGS={line}\n")
