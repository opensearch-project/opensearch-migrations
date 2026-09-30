"""Workflow-owned pod churn for k6 capture/replay stress suites."""

import argparse
import base64
from collections import Counter
from contextlib import contextmanager
from datetime import datetime, timezone
from itertools import cycle
import json
import logging
import os
import re
import subprocess
import tempfile
import time

from kubernetes import client, config
from kubernetes.client.rest import ApiException

from console_link.models import kafka as kafka_models
from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION
from .testrun_utils import ARGO_GROUP, ARGO_VERSION, WORKFLOW_PLURAL, runner_selector


logger = logging.getLogger(__name__)

STRIMZI_GROUP = "kafka.strimzi.io"
STRIMZI_VERSION = "v1beta2"
TERMINAL_PHASES = {"Succeeded", "Failed", "Error", "Terminated"}
FAILED_PHASES = TERMINAL_PHASES - {"Succeeded"}
POD_SELECTORS = {
    "proxy": "migrations/proxy=capture-proxy",
    "kafka": "strimzi.io/cluster=default,strimzi.io/component-type=kafka",
    "replayer": "app=replayer",
}
_TOPIC_PARTITION = re.compile(
    r"Partition:\s*(?P<partition>\d+).*?"
    r"Leader:\s*(?P<leader>-?\d+).*?"
    r"Replicas:\s*(?P<replicas>[\d,]+).*?"
    r"Isr:\s*(?P<isr>[\d,]+)",
)


def _pod_ready(pod):
    return (
        pod.status.phase == "Running"
        and pod.metadata.deletion_timestamp is None
        and any(
            condition.type == "Ready" and condition.status == "True"
            for condition in (pod.status.conditions or [])
        )
    )


def _deployment_ready(deployment):
    desired = deployment.spec.replicas or 0
    status = deployment.status
    return (
        (status.observed_generation or 0) >= (deployment.metadata.generation or 0)
        and (status.updated_replicas or 0) == desired
        and (status.ready_replicas or 0) == desired
        and (status.available_replicas or 0) == desired
        and not (status.unavailable_replicas or 0)
    )


def _proxy_is_fail_closed(deployment):
    args = deployment.spec.template.spec.containers[0].args or []
    for index, argument in enumerate(args):
        if argument == "--capture-failure-policy":
            return index + 1 < len(args) and args[index + 1] == "fail-closed"
        if argument.startswith("--capture-failure-policy="):
            return argument.split("=", 1)[1] == "fail-closed"
        if argument == "---INLINE-JSON":
            if index + 1 >= len(args):
                raise ValueError("Capture proxy inline JSON argument has no value")
            raw_config = args[index + 1].strip()
            inline_config = json.loads(
                raw_config if raw_config.startswith(("{", "["))
                else base64.b64decode(raw_config).decode()
            )
            if not isinstance(inline_config, dict):
                raise ValueError("Capture proxy inline JSON must be an object")
            return inline_config.get("captureFailurePolicy", "fail-closed") == "fail-closed"
    return True


def _custom_resource_ready(resource):
    return any(
        condition.get("type") == "Ready" and condition.get("status") == "True"
        for condition in resource.get("status", {}).get("conditions", [])
    )


def _topic_partitions(description):
    partitions = {}
    for line in description.splitlines():
        match = _TOPIC_PARTITION.search(line)
        if match:
            partitions[int(match.group("partition"))] = {
                "leader": int(match.group("leader")),
                "replicas": match.group("replicas").split(","),
                "isr": match.group("isr").split(","),
            }
    return partitions


def _topic_fully_replicated(description, expected_partitions, expected_replicas):
    partitions = _topic_partitions(description)
    return (
        len(partitions) == expected_partitions
        and all(
            state["leader"] >= 0
            and len(state["replicas"]) == expected_replicas
            and set(state["isr"]) == set(state["replicas"])
            for state in partitions.values()
        )
    )


def _run_specs(raw_runs):
    runs = json.loads(raw_runs)
    if not isinstance(runs, list) or not runs:
        raise ValueError("runs must be a non-empty JSON list")
    specs = {}
    for run in runs:
        name = run.get("name")
        parallelism = int(run.get("parallelism", 0))
        if not name or parallelism < 1:
            raise ValueError(f"Invalid run specification: {run}")
        if name in specs:
            raise ValueError(f"Duplicate run name: {name}")
        specs[name] = parallelism
    return specs


def _workflow_phases(custom, namespace, suite_name, run_specs, allow_missing):
    phases = {}
    for run_name in run_specs:
        workflow_name = f"{suite_name}-{run_name}"
        try:
            workflow = custom.get_namespaced_custom_object(
                group=ARGO_GROUP,
                version=ARGO_VERSION,
                namespace=namespace,
                plural=WORKFLOW_PLURAL,
                name=workflow_name,
            )
        except ApiException as error:
            if allow_missing and error.status == 404:
                continue
            raise
        phases[run_name] = workflow.get("status", {}).get("phase", "Pending")
    return phases


def _raise_for_failed_runs(custom, namespace, suite_name, run_specs):
    phases = _workflow_phases(
        custom, namespace, suite_name, run_specs, allow_missing=False,
    )
    failed = {name: phase for name, phase in phases.items() if phase in FAILED_PHASES}
    if failed:
        raise RuntimeError(f"k6 child workflow failure: {failed}")
    return phases


def _wait_for_suite_start(core, custom, namespace, suite_name, run_specs, timeout, interval):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        phases = _workflow_phases(
            custom, namespace, suite_name, run_specs, allow_missing=True,
        )
        terminal = {name: phase for name, phase in phases.items() if phase in TERMINAL_PHASES}
        if terminal:
            raise RuntimeError(f"k6 child workflow ended before churn started: {terminal}")
        if len(phases) == len(run_specs):
            ready = True
            for run_name, expected_runners in run_specs.items():
                pods = core.list_namespaced_pod(
                    namespace,
                    label_selector=runner_selector(f"{suite_name}-{run_name}"),
                ).items
                if len(pods) != expected_runners or not all(_pod_ready(pod) for pod in pods):
                    ready = False
                    break
            if ready:
                logger.info(
                    "All %d k6 runners are ready; starting pod churn",
                    sum(run_specs.values()),
                )
                return
        time.sleep(interval)
    raise TimeoutError(f"k6 runners did not become ready within {timeout} seconds")


@contextmanager
def _kafka_client(namespace):
    core = client.CoreV1Api()
    password_secret = core.read_namespaced_secret("default-migration-app", namespace)
    ca_secret = core.read_namespaced_secret("default-cluster-ca-cert", namespace)
    ca_file = tempfile.NamedTemporaryFile(prefix="k6-kafka-ca-", delete=False)
    ca_file.write(base64.b64decode(ca_secret.data["ca.crt"]))
    ca_file.close()
    kafka = kafka_models.ScramKafka(
        {
            "broker_endpoints": f"default-kafka-bootstrap.{namespace}.svc:9093",
            "scram": {
                "username": "default-migration-app",
                "ca_cert_path": ca_file.name,
            },
        },
        password=base64.b64decode(password_secret.data["password"]).decode(),
    )
    try:
        yield kafka
    finally:
        for path in (getattr(kafka, "_props_file", None), ca_file.name):
            if path:
                try:
                    os.unlink(path)
                except OSError:
                    pass


def _describe_traffic_topic(custom, namespace, kafka):
    captured = custom.get_namespaced_custom_object(
        group=CRD_GROUP,
        version=CRD_VERSION,
        namespace=namespace,
        plural="capturedtraffics",
        name="capture-proxy-topic",
    )
    spec = captured["spec"]
    command = [
        kafka_models.resolve_kafka_tool(kafka_models.KAFKA_TOPICS_SCRIPT),
        "--bootstrap-server", kafka.brokers,
        "--describe", "--topic", spec["topicName"],
        *kafka._cmd_config_args(),
    ]
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=60,
        env=kafka_models._kafka_command_env(),
    )
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout, int(spec["partitions"]), int(spec["replicas"])


def _fleet_health(core, apps, custom, namespace, kafka, expected_brokers):
    proxy = apps.read_namespaced_deployment("capture-proxy", namespace)
    if not _deployment_ready(proxy):
        return False, "capture-proxy deployment is not fully ready"
    if not _proxy_is_fail_closed(proxy):
        raise ValueError("capture-proxy is not using fail-closed capture")

    replayers = apps.list_namespaced_deployment(
        namespace, label_selector=POD_SELECTORS["replayer"],
    ).items
    if len(replayers) != 1 or not _deployment_ready(replayers[0]):
        return False, "replayer deployment is not fully ready"

    kafka_resource = custom.get_namespaced_custom_object(
        group=STRIMZI_GROUP,
        version=STRIMZI_VERSION,
        namespace=namespace,
        plural="kafkas",
        name="default",
    )
    if not _custom_resource_ready(kafka_resource):
        return False, "Kafka resource is not Ready"

    node_pool = custom.get_namespaced_custom_object(
        group=STRIMZI_GROUP,
        version=STRIMZI_VERSION,
        namespace=namespace,
        plural="kafkanodepools",
        name="dual-role",
    )
    broker_count = int(node_pool["spec"]["replicas"])
    if broker_count != expected_brokers:
        raise ValueError(
            f"Kafka node pool has {broker_count} brokers, expected {expected_brokers}"
        )

    brokers = core.list_namespaced_pod(
        namespace, label_selector=POD_SELECTORS["kafka"],
    ).items
    if len(brokers) != broker_count or not all(_pod_ready(pod) for pod in brokers):
        return False, "Kafka broker pods are not fully ready"

    description, partitions, replicas = _describe_traffic_topic(custom, namespace, kafka)
    if replicas != expected_brokers:
        raise ValueError(
            f"traffic topic has {replicas} replicas, expected {expected_brokers}"
        )
    if not _topic_fully_replicated(description, partitions, replicas):
        return False, "traffic topic leaders or ISR have not fully recovered"
    return True, "healthy"


def _wait_for_fleet_recovery(
    core,
    apps,
    custom,
    namespace,
    kafka,
    expected_brokers,
    run_specs,
    suite_name,
    timeout,
    interval,
    stable_samples,
):
    started = time.monotonic()
    deadline = started + timeout
    stable = 0
    last_log = 0
    last_detail = "fleet has not reported health"
    while time.monotonic() < deadline:
        _raise_for_failed_runs(custom, namespace, suite_name, run_specs)
        try:
            healthy, detail = _fleet_health(
                core, apps, custom, namespace, kafka, expected_brokers,
            )
        except Exception as error:  # transient API/Kafka errors are expected during broker recovery
            if (
                isinstance(error, ApiException) and error.status in {401, 403, 404}
            ) or isinstance(error, (FileNotFoundError, KeyError, ValueError)):
                raise RuntimeError(f"Pod/ISR health check cannot run: {error}") from error
            healthy, detail = False, str(error)
        last_detail = detail
        stable = stable + 1 if healthy else 0
        if stable >= stable_samples:
            return time.monotonic() - started
        if time.monotonic() - last_log >= 30:
            logger.info("Waiting for full pod/ISR recovery: %s", detail)
            last_log = time.monotonic()
        time.sleep(interval)
    raise TimeoutError(
        f"Pod/ISR recovery did not complete within {timeout} seconds: {last_detail}"
    )


def _targets(raw_targets):
    targets = tuple(target.strip().lower() for target in raw_targets.split(",") if target.strip())
    unknown = set(targets) - set(POD_SELECTORS)
    if not targets:
        raise ValueError("Pod churn is enabled but no targets were selected")
    if unknown:
        raise ValueError(f"Unknown pod-churn targets: {sorted(unknown)}")
    return targets


def run(args):
    config.load_incluster_config()
    run_specs = _run_specs(args.runs)
    targets = _targets(args.targets)
    core = client.CoreV1Api()
    apps = client.AppsV1Api()
    custom = client.CustomObjectsApi()

    _wait_for_suite_start(
        core,
        custom,
        args.namespace,
        args.suite_name,
        run_specs,
        args.startup_timeout_seconds,
        args.interval_seconds,
    )

    events = []
    target_cycle = cycle(targets)
    positions = {target: 0 for target in targets}
    with _kafka_client(args.namespace) as kafka:
        while True:
            phases = _raise_for_failed_runs(
                custom, args.namespace, args.suite_name, run_specs,
            )
            if all(phase == "Succeeded" for phase in phases.values()):
                break

            recovery_seconds = _wait_for_fleet_recovery(
                core,
                apps,
                custom,
                args.namespace,
                kafka,
                args.expected_kafka_brokers,
                run_specs,
                args.suite_name,
                args.recovery_timeout_seconds,
                args.interval_seconds,
                args.stable_samples,
            )
            if events and "recoverySeconds" not in events[-1]:
                events[-1]["recoverySeconds"] = round(recovery_seconds, 1)

            phases = _raise_for_failed_runs(
                custom, args.namespace, args.suite_name, run_specs,
            )
            if all(phase == "Succeeded" for phase in phases.values()):
                break

            target = next(target_cycle)
            candidates = sorted(
                (
                    pod for pod in core.list_namespaced_pod(
                        args.namespace, label_selector=POD_SELECTORS[target],
                    ).items
                    if _pod_ready(pod)
                ),
                key=lambda pod: pod.metadata.name,
            )
            if not candidates:
                raise RuntimeError(f"No ready {target} pods available for churn")
            victim = candidates[positions[target] % len(candidates)]
            positions[target] += 1
            core.delete_namespaced_pod(victim.metadata.name, args.namespace)
            event = {
                "target": target,
                "pod": victim.metadata.name,
                "uid": victim.metadata.uid,
                "deletedAt": datetime.now(timezone.utc).isoformat(),
            }
            events.append(event)
            logger.info("POD_CHURN_EVENT %s", json.dumps(event, sort_keys=True))

        final_recovery = _wait_for_fleet_recovery(
            core,
            apps,
            custom,
            args.namespace,
            kafka,
            args.expected_kafka_brokers,
            run_specs,
            args.suite_name,
            args.recovery_timeout_seconds,
            args.interval_seconds,
            args.stable_samples,
        )
        if events and "recoverySeconds" not in events[-1]:
            events[-1]["recoverySeconds"] = round(final_recovery, 1)

    if not events:
        raise RuntimeError("Pod churn completed without deleting a pod")
    summary = {
        "deletions": len(events),
        "byTarget": dict(Counter(event["target"] for event in events)),
        "events": events,
    }
    logger.info("POD_CHURN_SUMMARY %s", json.dumps(summary, sort_keys=True))


def _parser():
    parser = argparse.ArgumentParser()
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--suite-name", required=True)
    parser.add_argument("--runs", required=True)
    parser.add_argument("--targets", required=True)
    parser.add_argument("--expected-kafka-brokers", type=int, required=True)
    parser.add_argument("--startup-timeout-seconds", type=int, default=600)
    parser.add_argument("--recovery-timeout-seconds", type=int, default=600)
    parser.add_argument("--interval-seconds", type=int, default=5)
    parser.add_argument("--stable-samples", type=int, default=3)
    return parser


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    run(_parser().parse_args())


if __name__ == "__main__":
    main()
