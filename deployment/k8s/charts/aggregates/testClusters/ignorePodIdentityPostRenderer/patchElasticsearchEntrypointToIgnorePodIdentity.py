#!/usr/bin/env python3
# patchElasticsearchEntrypointToIgnorePodIdentity.py: Helm post-renderer; see plugin.yaml beside it.

import sys
import os
import json
import subprocess
import tempfile
import re


# Image ENTRYPOINT + CMD for a container that sets neither command nor args (case 3 below).
# The stock Elastic images (elasticsearch, elasticsearch-oss) start with
# docker-entrypoint.sh eswrapper. The searchguard-wrapped custom image built from
# TrafficCapture/dockerSolution/src/main/docker/elasticsearchWithSearchGuard/Dockerfile
# uses /usr/local/bin/entrypoint.sh, which execs bin/elasticsearch.
STOCK_ELASTIC_ENTRYPOINT = "/usr/local/bin/docker-entrypoint.sh eswrapper"
CUSTOM_IMAGE_ENTRYPOINT = "/usr/local/bin/entrypoint.sh"


def default_entrypoint_for_image(image):
    if image.startswith("docker.elastic.co/elasticsearch/"):
        return STOCK_ELASTIC_ENTRYPOINT
    return CUSTOM_IMAGE_ENTRYPOINT


def is_target_statefulset(doc):
    """Quick check if this is the elasticsearch-master StatefulSet"""
    return ('kind: StatefulSet' in doc and
            'name: elasticsearch-master' in doc)


def main():
    # Read input from stdin
    input_yaml = sys.stdin.read()

    # Split by lines starting with ---
    raw_docs = re.split(r'^---[ \t]*$', input_yaml, flags=re.MULTILINE)
    documents = [doc for doc in raw_docs if doc.strip()]

    # Process each document
    first = True
    for i, doc in enumerate(documents):
        # Skip empty documents or documents without kind
        if not doc.strip() or 'kind:' not in doc:
            continue

        # Output separator (except for first document)
        if not first:
            print("---")
        first = False

        # Quick check: is this our target StatefulSet?
        if is_target_statefulset(doc):
            # Convert YAML to JSON using kubectl
            try:
                with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
                    f.write(doc)
                    temp_file = f.name

                # A pure YAML-to-JSON conversion. `kubectl create --dry-run=client` is NOT one: it
                # needs a reachable API server for discovery, and it stamps the current context's
                # namespace onto the object, which sent the StatefulSet to the wrong namespace
                # whenever that context's namespace wasn't the release namespace.
                # `patch --local` with an empty patch reads only the file, and an empty
                # KUBECONFIG guarantees no cluster is consulted.
                result = subprocess.run(
                    ['kubectl', 'patch', '--local', '-f', temp_file,
                     '--type', 'merge', '-p', '{}', '-o', 'json'],
                    capture_output=True,
                    text=True,
                    check=True,
                    env={**os.environ, 'KUBECONFIG': os.devnull},
                )

                manifest = json.loads(result.stdout)

            except (subprocess.CalledProcessError, json.JSONDecodeError) as e:
                print(f"ERROR: Failed to process StatefulSet: {e}", file=sys.stderr)
                print(doc)
                continue
            finally:
                if 'temp_file' in locals():
                    os.unlink(temp_file)

            # Patch ONLY the 'source' or 'elasticsearch' container
            containers = manifest['spec']['template']['spec']['containers']
            for container in containers:
                container_name = container.get('name', '')

                # Only patch the 'source' or 'elasticsearch' container
                if container_name not in ['source', 'elasticsearch']:
                    continue

                # Case 1: Container has args (shell script) - prepend to it
                if 'args' in container and container['args']:
                    original_script = container['args'][0]
                    patched_script = (
                        "unset AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE\n"
                        "unset AWS_CONTAINER_CREDENTIALS_FULL_URI\n"
                        "\n" +
                        original_script
                    )
                    container['args'][0] = patched_script

                # Case 2: Container has command but no args
                elif 'command' in container and container['command']:
                    original_command = container['command']
                    container['command'] = ['sh', '-c']
                    container['args'] = [
                        f"unset AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE\n"
                        f"unset AWS_CONTAINER_CREDENTIALS_FULL_URI\n"
                        f"exec {' '.join(original_command)}"
                    ]

                # Case 3: Container has neither - start the image's own ENTRYPOINT and CMD.
                else:
                    container['command'] = ['sh', '-c']
                    container['args'] = [
                        "unset AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE\n"
                        "unset AWS_CONTAINER_CREDENTIALS_FULL_URI\n"
                        f"exec {default_entrypoint_for_image(container.get('image', ''))}"
                    ]

            # Output as JSON directly
            print(json.dumps(manifest, indent=2))

        else:
            # Not our target - just pass through as-is
            print(doc)


if __name__ == '__main__':
    main()
