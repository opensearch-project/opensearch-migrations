"""Verify the Jenkins agent keeps AWS credentials out of controller-visible output."""

import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / "jenkins/configureMavenCache.sh"


class MavenCacheCredentialsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="maven-cache-credentials-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.private = self.root / "agent-tmp"
        self.private.mkdir()
        self.aws_log = self.root / "aws.jsonl"
        aws = self.root / "aws"
        aws.write_text("""#!/usr/bin/env python3
import json
import os
import sys

args = sys.argv[1:]
with open(os.environ["TEST_AWS_LOG"], "a") as log:
    log.write(json.dumps(args) + "\\n")
if args[:2] == ["sts", "get-caller-identity"]:
    print("arn:aws:sts::123456789012:assumed-role/jenkins-agent/session")
elif args[:2] == ["sts", "assume-role"]:
    assert args[args.index("--role-arn") + 1] == (
        "arn:aws:iam::123456789012:role/test-domain-maven-reader")
    print("private-access-key\\tprivate-secret-key\\tprivate-session-token")
else:
    assert os.environ["AWS_ACCESS_KEY_ID"] == "private-access-key"
    assert os.environ["AWS_SECRET_ACCESS_KEY"] == "private-secret-key"
    assert os.environ["AWS_SESSION_TOKEN"] == "private-session-token"
    if args[:2] == ["codeartifact", "get-repository-endpoint"]:
        print("https://test-domain-123456789012.d.codeartifact.us-east-1.amazonaws.com/maven/build-dependencies/")
    elif args[:2] == ["codeartifact", "get-authorization-token"]:
        print("private-repository-password")
        if os.environ.get("TEST_TOKEN_FAILURE"):
            sys.exit(1)
    else:
        sys.exit(2)
""")
        aws.chmod(0o700)
        self.environment = dict(
            os.environ, PATH=f"{self.root}:{os.environ['PATH']}",
            MIGRATIONS_CACHE_DOMAIN="test-domain", MIGRATIONS_CACHE_REGION="us-east-1",
            MIGRATIONS_CACHE_TMP_DIR=str(self.private), TEST_AWS_LOG=str(self.aws_log),
        )

    def run_script(self):
        result = subprocess.run(
            ["bash", str(SCRIPT)], env=self.environment, capture_output=True, text=True, timeout=30,
        )
        for secret in ("private-access-key", "private-secret-key",
                       "private-session-token", "private-repository-password"):
            self.assertNotIn(secret, result.stdout + result.stderr)
        return result

    def test_credentials_stay_in_private_file_and_reader_session(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        endpoint, filename = result.stdout.splitlines()
        self.assertTrue(endpoint.startswith("https://test-domain-123456789012.d.codeartifact."))
        password_file = Path(filename)
        self.assertTrue(password_file.is_relative_to(self.private))
        self.assertEqual(password_file.read_text().strip(), "private-repository-password")
        self.assertEqual(stat.S_IMODE(password_file.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(password_file.parent.stat().st_mode), 0o700)
        calls = [json.loads(line) for line in self.aws_log.read_text().splitlines()]
        self.assertEqual([call[:2] for call in calls], [
            ["sts", "get-caller-identity"], ["sts", "assume-role"],
            ["codeartifact", "get-repository-endpoint"], ["codeartifact", "get-authorization-token"],
        ])
        token_call = calls[-1]
        self.assertEqual(token_call[token_call.index("--duration-seconds") + 1], "43200")

    def test_failed_acquisition_removes_partial_password_file(self):
        self.environment["TEST_TOKEN_FAILURE"] = "1"
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")
        self.assertEqual(list(self.private.iterdir()), [])
        calls = [json.loads(line) for line in self.aws_log.read_text().splitlines()]
        self.assertEqual(calls[-1][:2], ["codeartifact", "get-authorization-token"])


if __name__ == "__main__":
    unittest.main()
