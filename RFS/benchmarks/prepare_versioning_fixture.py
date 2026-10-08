#!/usr/bin/env python3
"""Create immutable benchmark inputs using a real, disposable OpenSearch node."""

import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def docker(*args, check=True):
    return subprocess.run(
        ["docker", *args], check=check, text=True, capture_output=True
    ).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--image", required=True)
    parser.add_argument("--documents", type=int, default=8192)
    args = parser.parse_args()
    if args.documents < 256 or args.documents % 256:
        parser.error("--documents must be a positive multiple of 256")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    name = "rfs-version-benchmark-" + uuid.uuid4().hex[:12]
    docker(
        "run", "-d", "--name", name, "--memory=3g", "--cpus=4",
        "-p", "127.0.0.1::9200",
        "-e", "discovery.type=single-node",
        "-e", "DISABLE_SECURITY_PLUGIN=true",
        "-e", "DISABLE_INSTALL_DEMO_CONFIG=true",
        "-e", "OPENSEARCH_JAVA_OPTS=-Xms1g -Xmx1g",
        args.image, "-Epath.repo=/tmp/version-benchmark-snapshots",
    )
    try:
        endpoint = "http://" + docker("port", name, "9200/tcp")

        def request(method, path, body=None):
            data = body if isinstance(body, bytes) else (
                json.dumps(body).encode() if body is not None else None
            )
            req = urllib.request.Request(
                endpoint + path, data=data, method=method,
                headers={"Content-Type": "application/json"},
            )
            with urllib.request.urlopen(req, timeout=180) as response:
                return json.load(response)

        deadline = time.monotonic() + 180
        while True:
            try:
                cluster = request("GET", "/")
                break
            except (urllib.error.URLError, ConnectionError):
                if time.monotonic() > deadline:
                    raise TimeoutError(docker("logs", name))
                time.sleep(1)

        datasets = {}
        for label, size, varied in [
            ("small-uniform", 1024, False),
            ("small-varied", 1024, True),
            ("large-varied", 16384, True),
        ]:
            index = label
            request("PUT", "/" + index, {
                "settings": {"number_of_shards": 1, "number_of_replicas": 0,
                             "refresh_interval": "-1"},
                "mappings": {"dynamic": False},
            })
            rng = random.Random(3434)
            source_hash = hashlib.sha256()
            for start in range(0, args.documents, 256):
                lines = []
                for doc_id in range(start, start + 256):
                    # Include numerous fields; a single long string understates Map costs.
                    source = {"event_id": doc_id, "service": "orders", "ok": True}
                    for field in range(6 if size == 1024 else 120):
                        source["field_" + str(field)] = rng.randbytes(50).hex()
                    source["padding"] = ""
                    body = json.dumps(source, separators=(",", ":"))
                    source["padding"] = rng.randbytes(size).hex()[:size - len(body)]
                    body = json.dumps(source, separators=(",", ":")).encode()
                    assert len(body) == size
                    source_hash.update(body)
                    metadata = {"_id": str(doc_id)}
                    if varied:
                        version = (1 + rng.randrange(1000) if doc_id % 4 == 0
                                   else 9007199254740993 + rng.randrange(2**48))
                        metadata.update(version=version, version_type="external")
                    lines.extend([json.dumps({"index": metadata}).encode(), body])
                result = request("POST", "/" + index + "/_bulk", b"\n".join(lines) + b"\n")
                if result["errors"]:
                    raise RuntimeError(result)
            request("POST", "/" + index + "/_refresh")
            request("POST", "/" + index + "/_forcemerge?max_num_segments=1")
            request("POST", "/" + index + "/_flush?force=true")
            assert request("GET", "/" + index + "/_count")["count"] == args.documents
            settings = request("GET", "/" + index + "/_settings?flat_settings=true")
            datasets[label] = {
                "image": args.image, "version": cluster["version"]["number"],
                "lucene": cluster["version"]["lucene_version"],
                "documents": args.documents, "sourceBytes": size,
                "sourceSha256": source_hash.hexdigest(),
                "indexUuid": settings[index]["settings"]["index.uuid"],
            }
            print(f"Created {label}: {args.documents} x {size} bytes", flush=True)

        request("PUT", "/_snapshot/benchmark", {
            "type": "fs", "settings": {"location": "/tmp/version-benchmark-snapshots"},
        })
        snapshot = request("PUT", "/_snapshot/benchmark/fixture?wait_for_completion=true", {
            "indices": ",".join(datasets), "include_global_state": False,
        })
        assert snapshot["snapshot"]["state"] == "SUCCESS"
        # Stop before copying committed shard files. The snapshot is retained as provenance.
        paths = {}
        for label, info in datasets.items():
            for base in ("/usr/share/opensearch/data/indices",
                         "/usr/share/opensearch/data/nodes/0/indices"):
                path = f"{base}/{info['indexUuid']}/0/index"
                result = subprocess.run(
                    ["docker", "exec", name, "test", "-d", path], check=False
                )
                if result.returncode == 0:
                    paths[label] = path
                    break
            if label not in paths:
                raise RuntimeError("Cannot locate shard files for " + label)
        docker("stop", name)
        docker("cp", name + ":/tmp/version-benchmark-snapshots", str(output / "snapshot"))
        for label, info in datasets.items():
            directory = output / label
            directory.mkdir()
            docker("cp", name + ":" + paths[label], str(directory / "index"))
            info["files"] = {
                file.name: hashlib.sha256(file.read_bytes()).hexdigest()
                for file in sorted((directory / "index").iterdir()) if file.is_file()
            }
            (directory / "manifest.json").write_text(json.dumps(info, indent=2) + "\n")
    finally:
        docker("rm", "-f", name, check=False)


if __name__ == "__main__":
    main()
