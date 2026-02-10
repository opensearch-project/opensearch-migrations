#!/usr/bin/env python3
"""Setup kiro-cli auth from AWS Secrets Manager."""
import json
import os
import sqlite3
import subprocess
import sys

SECRET_ID = "kiro-cli-creds-temp"
REGION = "us-east-1"
DB_PATH = os.path.expanduser("~/.local/share/kiro-cli/data.sqlite3")


def main():
    # Get credentials from secrets manager
    result = subprocess.run(
        [
            "aws", "secretsmanager", "get-secret-value",
            "--secret-id", SECRET_ID,
            "--region", REGION,
            "--query", "SecretString",
            "--output", "text"
        ],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print(f"Failed to get secret: {result.stderr}", file=sys.stderr)
        sys.exit(1)

    creds = json.loads(result.stdout.strip())

    # Create directory if needed
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)

    # Setup sqlite database
    conn = sqlite3.connect(DB_PATH)
    c = conn.cursor()
    c.execute("CREATE TABLE IF NOT EXISTS auth_kv (key TEXT PRIMARY KEY, value TEXT)")
    for key, value in creds.items():
        c.execute(
            "INSERT OR REPLACE INTO auth_kv (key, value) VALUES (?, ?)",
            (key, json.dumps(value))
        )
    conn.commit()
    conn.close()

    print(f"kiro-cli auth configured at {DB_PATH}")


if __name__ == "__main__":
    main()
