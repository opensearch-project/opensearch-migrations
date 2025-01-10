#!/usr/bin/python3
import yaml
import sys


# Parse YAML and extract value using dot notation
def yaml_extract(yaml_str, path):
    data = yaml.safe_load(yaml_str)
    keys = path.split('.')
    result = data
    for key in keys:
        result = result[key]
    return result


if __name__ == "__main__":
    # Parse args like yq does: script.py '.foo.bar' file.yaml
    path = sys.argv[1]
    if len(sys.argv) > 2:
        # Read from file
        with open(sys.argv[2]) as f:
            yaml_str = f.read()
    else:
        # Read from stdin
        yaml_str = sys.stdin.read()

    print(yaml_extract(yaml_str, path.lstrip('.')))
