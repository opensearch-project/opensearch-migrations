"""Run with RFS_TEST_COLLECTOR=1 to exercise the chart's processors and EMF exporter in Docker."""
import json
import os
import runpy
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest
import yaml


REPO = Path(__file__).resolve().parents[4]
CHART = REPO / "deployment/k8s/charts/aggregates/migrationAssistantWithArgo"
GIB = 2 ** 30


def attributes(labels):
    return [{"key": key, "value": {"stringValue": value}} for key, value in labels.items()]


def payload(metrics, writer=None):
    resource = {"service.instance.id": writer} if writer else {}
    return {"resource": {"attributes": attributes(resource)},
            "scopeMetrics": [{"scope": {"name": "documentMigration"}, "metrics": metrics}]}


@pytest.mark.skipif(os.environ.get("RFS_TEST_COLLECTOR") != "1", reason="requires Docker and the chart's ADOT image")
def test_fleet_snapshot_and_simultaneous_counter_writers(tmp_path):
    source = yaml.safe_load(yaml.safe_load((CHART / "valuesEks.yaml").read_text())["metrics"]["collectorConfig"])
    image = yaml.safe_load((CHART / "values.yaml").read_text())["otelCollectorImage"]
    # Exercise the same filter, batch and EMF exporter as the deployed collector.
    config = {"receivers": {}, "processors": {}, "exporters": {}, "service": {"pipelines": {}}}
    ports = {"rfs-tier": 4320}
    for name, port in ports.items():
        receiver = "otlp/" + name
        config["receivers"][receiver] = {"protocols": {"http": {"endpoint": f"0.0.0.0:{port}"}}}
        pipeline = source["service"]["pipelines"]["metrics/" + name]
        config["service"]["pipelines"]["metrics/" + name] = dict(pipeline, receivers=[receiver])
        for processor in pipeline["processors"]:
            config["processors"][processor] = source["processors"][processor]
        for exporter in pipeline["exporters"]:
            config["exporters"][exporter] = dict(source["exporters"][exporter],
                                                 output_destination="stdout", region="us-east-2")
    path = tmp_path / "config.json"
    path.write_text(json.dumps(config))
    bindings = [arg for port in ports.values() for arg in ("-p", f"127.0.0.1::{port}")]
    container = subprocess.check_output([
        "docker", "run", "-d", *bindings, "-v", f"{path}:/config.json:ro", "-e", "QUALIFIER=test",
        "-e", "AWS_EC2_METADATA_DISABLED=true", image, "--config=/config.json",
    ], text=True).strip()
    try:
        def post(name, resources):
            mapped = bindings_by_port[f"{ports[name]}/tcp"][0]["HostPort"]
            request = urllib.request.Request(f"http://127.0.0.1:{mapped}/v1/metrics",
                                             json.dumps({"resourceMetrics": resources}).encode(),
                                             headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=3) as response:
                assert not json.load(response).get("partialSuccess", {}).get("rejectedDataPoints")

        def wait_for_receiver():
            for attempt in range(50):
                try:
                    post("rfs-tier", [])
                    return
                except (urllib.error.URLError, ConnectionError):
                    if attempt == 49:
                        raise
                    time.sleep(0.1)

        def published_ports():
            return json.loads(subprocess.check_output(["docker", "inspect", container]))[0]["NetworkSettings"]["Ports"]

        bindings_by_port = published_ports()
        wait_for_receiver()
        started = time.time_ns()
        sampler = runpy.run_path(str(CHART / "files/metrics/rfs_fleet_resources.py"))
        data = sampler["payload"](started // 10**9, [{("Workers", "12"): 2, ("MemoryUsed", "12"): 3 * GIB}],
                                  {"0"}, "ma")
        post("rfs-tier", data["resourceMetrics"])
        sweep = REPO / "orchestrationSpecs/packages/migration-workflow-templates/resources/scripts/rfsTierSweep.py"
        build_sweep = runpy.run_path(str(sweep))["build_fleet_metrics_payload"]
        for migration, desired in [("first", 4), ("second", 2)]:
            allocation = {"caps": {"12": desired}, "replicas": {"12": desired}}
            post("rfs-tier", build_sweep([{"tier": 12}], allocation, migration, started)["resourceMetrics"])

        def post_counts(counts):
            stamp = str(time.time_ns())
            writers = []
            for writer, count in zip(("a", "b"), counts):
                sample = {"startTimeUnixNano": str(started), "timeUnixNano": stamp, "asInt": str(count),
                          "attributes": attributes({"tier": "12"})}
                writers.append(payload([{"name": "workItemsEscalated", "sum": {
                    "aggregationTemporality": 2, "isMonotonic": True, "dataPoints": [sample]}}], writer))
            post("rfs-tier", writers)
            time.sleep(0.01)  # EMF has millisecond resolution.

        for counts in [(1, 2), (1, 2), (2, 2)]:
            post_counts(counts)
        subprocess.run(["docker", "restart", container], check=True, stdout=subprocess.DEVNULL)
        bindings_by_port = published_ports()
        wait_for_receiver()
        post_counts((2, 2))  # The workers reconnect with their existing cumulative totals.
        post_counts((3, 2))
        subprocess.run(["docker", "stop", container], check=True, stdout=subprocess.DEVNULL)
        output = subprocess.check_output(["docker", "logs", container], stderr=subprocess.STDOUT, text=True)
        events = [json.loads(line) for line in output.splitlines() if line.startswith("{") and '"_aws"' in line]

        def values(name, **labels):
            labels["tier"] = "12"
            return [e[name] for e in events if name in e and all(e.get(k) == v for k, v in labels.items())]

        assert values("rfsFleetWorkers") == [2]
        assert values("rfsFleetMemoryUsed") == [3 * GIB]
        assert values("tierReplicasDesired", migration="first") == [4]
        assert values("tierReplicasDesired", migration="second") == [2]
        assert sum(values("workItemsEscalated")) == 5
    finally:
        subprocess.run(["docker", "rm", "-f", container], check=True, stdout=subprocess.DEVNULL)


def test_churn_keeps_fresh_workers_and_reports_unmeasured_limits():
    sampler = runpy.run_path(str(CHART / 'files/metrics/rfs_fleet_resources.py'))
    stamp = '2026-01-01T00:01:00Z'
    now = sampler['timestamp'](stamp)
    worker = sampler['Worker']
    limits = {'memory': '4Gi', 'cpu': '1500m'}
    running = [worker('present', '0', 'node', now - 120, None, limits),
               worker('restarted', '1', 'node', now - 3, now - 5, limits),
               worker('new', '2', 'node', now - 1, None, limits)]
    summary = {'startTime': '2025-12-31T23:58:59Z',
               'memory': {'time': stamp, 'workingSetBytes': GIB},
               'cpu': {'time': stamp, 'usageNanoCores': 0}}
    stats = {uid: summary for uid in ['present', 'restarted', 'already-stopped']}
    points = sampler['measure'](running, stats, now)
    assert points['MemoryUsed', '0'] == GIB
    assert points['MemoryUnused', None] == 3 * GIB
    assert points['MemoryAwaitingSample', None] == 8 * GIB
    assert points['CpuUnused', None] == 1.5
    assert points['CpuMissingWorkers', None] == 2
    assert points['Workers', '1'] == 1
    assert not sampler['measure']([], stats, now)
    assert sampler['fresh_usage'](summary, 'memory', 'workingSetBytes', running[0], now + 61) is None

    tiers = {'0'}

    def values(snapshots):
        data = sampler['payload'](int(now), snapshots, tiers, 'ma')['resourceMetrics'][0]['scopeMetrics'][0]
        return {(m['name'], tuple(a['value']['stringValue'] for a in d['attributes'])): d['asDouble']
                for m in data['metrics'] for d in m['gauge']['dataPoints']}
    snapshot = values([{}, {}, points, {}])
    assert snapshot['rfsFleetCensusSamples', ('ma',)] == 4
    assert snapshot['rfsFleetMemoryUsed', ('ma', '0')] == GIB / 4
    assert snapshot['rfsFleetWorkers', ('ma', '2')] == 0.25
    idle = values([{}, {}, {}, {}])
    assert idle['rfsFleetWorkers', ('ma', '2')] == 0
    assert idle['rfsFleetMemoryAwaitingSample', ('ma',)] == 0
