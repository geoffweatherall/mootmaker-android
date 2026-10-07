#!/usr/bin/env python3
"""Instrumentation arguments for ProductionDemoE2eTest, written to $GITHUB_ENV as E2E_ARGS.

Prefers production's mobile-config.json with the Android Cognito client, which is what the app
itself reads. Until a release has published that file, it falls back to the webapp's env-config.js
and the webapp's client, which also allows SRP. An annotation names the source used. If neither
can be read, E2E_ARGS stays empty, the test is skipped and a warning says so. The demo
credentials are public: the website shows them to every visitor.
"""
import json
import os
import re
import urllib.request

SITE = "https://www.mootmaker.com"


def fetch(path):
    return urllib.request.urlopen(f"{SITE}/{path}", timeout=20).read().decode()


def from_mobile_config():
    config = json.loads(fetch("mobile-config.json"))
    return config, config["COGNITO_ANDROID_CLIENT_ID"]


def from_env_config():
    config = json.loads(re.search(r"\{.*\}", fetch("env-config.js"), re.S).group(0))
    return config, config["COGNITO_CLIENT_ID"]


args = {}
for source, read in (("mobile-config.json (Android client)", from_mobile_config), ("env-config.js (webapp client)", from_env_config)):
    try:
        config, client_id = read()
        args = {
            "e2eGraphqlUrl": config["GRAPHQL_API_URL"],
            "e2eUserPoolId": config["COGNITO_USER_POOL_ID"],
            "e2eClientId": client_id,
            "e2eEmail": config["DEMO_USER_EMAIL"],
            "e2ePassword": config["DEMO_USER_PASSWORD"],
        }
        if any(re.search(r"\s", value) for value in args.values()):
            raise ValueError("a value contains whitespace, which the emulator runner's shell would split")
        print(f"::notice title=e2e config::Production e2e uses {source}", flush=True)
        break
    except Exception as error:  # noqa: BLE001 - try the next source, or skip with a warning
        print(f"{source}: {error}", flush=True)
        args = {}
if not args:
    print("::warning title=e2e skipped::Could not read production's mobile-config.json or env-config.js", flush=True)

line = " ".join(f"-Pandroid.testInstrumentationRunnerArguments.{k}={v}" for k, v in args.items())
with open(os.environ["GITHUB_ENV"], "a") as env:
    env.write(f"E2E_ARGS={line}\n")
