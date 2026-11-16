#!/usr/bin/env python3
# Copyright (c) 2026 - Yupiik SAS - https://yupiik.io/
# Licensed under the Apache License, Version 2.0.
"""Offline wrapper regression: only an isolated copy invokes a local fake Docker executable."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "extensions/bot-parent/slack-bot/src/main/docker/controller.sh.example"
IMAGE = "registry.invalid/controller@sha256:" + "a" * 64
BUDGET_KEYS = ("YCA_PROVIDER_BUDGET_ROOT", "YCA_PROVIDER_BUDGET_ID", "YCA_PROVIDER_BUDGET_SLOTS")


class SlackControllerWrapperTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        credentials = self.root / "credentials"
        credentials.mkdir(mode=0o700)
        for name in ("slack-app-token", "slack-bot-token", "provider-api-key", "services-keystore-password"):
            (credentials / name).write_text("private-test-secret")
        mock = self.root / "docker"
        mock.write_text("#!" + sys.executable + "\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n")
        mock.chmod(0o700)
        self.wrapper = self.root / "controller.sh"
        source = SOURCE.read_text()
        self.assertEqual(1, source.count("/usr/bin/docker run"))
        self.wrapper.write_text(source.replace("/usr/bin/docker run", str(mock) + " run"))
        self.environment = {key: value for key, value in os.environ.items() if key not in BUDGET_KEYS}
        self.environment.update(SLACK_CONTROLLER_IMAGE=IMAGE, DOCKER_SOCKET_GID="123",
                                ADMISSION_GROUP_GID="124", CREDENTIALS_DIRECTORY=str(credentials))

    def invoke(self, *args, **environment):
        return subprocess.run(["sh", str(self.wrapper), *args], text=True, capture_output=True,
                              env=self.environment | environment, timeout=0.4)

    def test_disabled_keeps_cli_and_image_once_without_secret_arguments(self):
        result = self.invoke("slack", "bot", "run", "--parallelism", "1")
        self.assertEqual(0, result.returncode, result.stderr)
        args = json.loads(result.stdout)
        self.assertEqual(1, args.count(IMAGE))
        self.assertEqual([IMAGE, "slack", "bot", "run", "--parallelism", "1"], args[-6:])
        self.assertNotIn("private-test-secret", result.stdout)
        self.assertNotIn("YCA_PROVIDER_BUDGET_ROOT", args)

    def test_opt_in_mounts_identical_trusted_path_and_forwards_names_not_values(self):
        budget = self.root / "budget"
        budget.mkdir(mode=0o700)
        result = self.invoke("slack", "bot", "list", YCA_PROVIDER_BUDGET_ROOT=str(budget),
                             YCA_PROVIDER_BUDGET_ID="account", YCA_PROVIDER_BUDGET_SLOTS="2")
        self.assertEqual(0, result.returncode, result.stderr)
        args = json.loads(result.stdout)
        self.assertIn("type=bind,src=" + str(budget) + ",dst=" + str(budget), args)
        for name in BUDGET_KEYS:
            self.assertIn(name, args)
        self.assertEqual(1, args.count(IMAGE))
        self.assertEqual([IMAGE, "slack", "bot", "list"], args[-4:])
        self.assertNotIn("private-test-secret", result.stdout)
        self.assertNotIn("account", args)

    def test_partial_missing_or_symlinked_budget_never_invokes_docker(self):
        budget = self.root / "budget"
        budget.mkdir(mode=0o700)
        alias = self.root / "alias"
        alias.symlink_to(budget)
        for environment in ({"YCA_PROVIDER_BUDGET_ID": "orphan"},
                            {"YCA_PROVIDER_BUDGET_ROOT": str(budget)},
                            {"YCA_PROVIDER_BUDGET_ROOT": str(alias), "YCA_PROVIDER_BUDGET_ID": "account", "YCA_PROVIDER_BUDGET_SLOTS": "1"}):
            with self.subTest(environment=environment):
                result = self.invoke("slack", "bot", "list", **environment)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertNotIn("private-test-secret", result.stderr)


if __name__ == "__main__":
    unittest.main()
