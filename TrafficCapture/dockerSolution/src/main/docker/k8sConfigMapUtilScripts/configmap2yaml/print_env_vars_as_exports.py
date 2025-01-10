#!/usr/bin/python3
import os
import shlex


# Export environment variables to sourceable shell script
def export_env():
    for key, value in sorted(os.environ.items()):
        print(f"export {key}={shlex.quote(value)}")


# Main logic to handle both cases
if __name__ == "__main__":
    # No args - export env vars
    export_env()
