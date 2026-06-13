import json
import logging
import os
import socket
import subprocess
import time
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from typing import Iterable

import requests

from console_link.models.utils import create_boto3_client, raise_for_aws_api_error

logger = logging.getLogger(__name__)


def assert_jaeger_received_spans(namespace: str = "ma", service_names: Iterable[str] = ("documentMigration",),
                                 lookback_minutes: int = 20, attempts: int = 10, wait_seconds: int = 15):
    expected_services = tuple(service_names)
    with _jaeger_query_url(namespace) as jaeger_url:
        for attempt in range(1, attempts + 1):
            seen_services = {
                service_name
                for service_name in expected_services
                if _jaeger_has_traces(jaeger_url, service_name, lookback_minutes)
            }
            missing_services = set(expected_services) - seen_services
            if not missing_services:
                logger.info("Found Jaeger traces for services: %s", seen_services)
                return
            if attempt < attempts:
                logger.info(
                    "Jaeger traces not visible yet (attempt %s/%s, missing=%s); waiting %ss",
                    attempt,
                    attempts,
                    sorted(missing_services),
                    wait_seconds
                )
                time.sleep(wait_seconds)
    raise AssertionError(f"Expected Jaeger traces for all of {expected_services} within {lookback_minutes} minutes")


def assert_xray_received_spans(namespace: str = "ma", service_names: Iterable[str] = ("documentMigration",),
                               lookback_minutes: int = 20, attempts: int = 10, wait_seconds: int = 30):
    expected_services = tuple(service_names)
    aws_metadata = _load_aws_metadata(namespace)
    region = aws_metadata.get("AWS_REGION")
    if not region:
        raise AssertionError(f"aws-metadata ConfigMap is missing AWS_REGION: {aws_metadata}")

    client = create_boto3_client(aws_service_name="xray", region=region)
    for attempt in range(1, attempts + 1):
        seen_services = _xray_services_with_recent_traces(client, expected_services, lookback_minutes)
        missing_services = set(expected_services) - seen_services
        if not missing_services:
            logger.info("Found X-Ray traces for services: %s", seen_services)
            return
        if attempt < attempts:
            logger.info(
                "X-Ray traces not visible yet (attempt %s/%s, missing=%s); waiting %ss",
                attempt,
                attempts,
                sorted(missing_services),
                wait_seconds
            )
            time.sleep(wait_seconds)
    raise AssertionError(f"Expected X-Ray traces for all of {expected_services} within {lookback_minutes} minutes")


def _load_aws_metadata(namespace: str):
    result = subprocess.run(
        ["kubectl", "-n", namespace, "get", "configmap", "aws-metadata", "-o", "json"],
        capture_output=True,
        text=True,
        timeout=30
    )
    if result.returncode != 0:
        raise AssertionError("aws-metadata ConfigMap was not found; X-Ray tracing tests require an EKS install")
    return json.loads(result.stdout).get("data", {})


@contextmanager
def _jaeger_query_url(namespace: str):
    if os.getenv("KUBERNETES_SERVICE_HOST"):
        url = f"http://jaeger-query.{namespace}.svc.cluster.local:16686"
        logger.info("Using in-cluster Jaeger query endpoint: %s", url)
        _wait_for_jaeger_query(url)
        yield url
    else:
        with _port_forward(namespace, "svc/jaeger-query", 16686) as url:
            yield url


@contextmanager
def _port_forward(namespace: str, resource_name: str, remote_port: int):
    local_port = _find_free_port()
    command = [
        "kubectl", "-n", namespace, "port-forward", resource_name, f"{local_port}:{remote_port}"
    ]
    logger.info("Starting port-forward: %s", " ".join(command))
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    url = f"http://127.0.0.1:{local_port}"
    try:
        _wait_for_port_forward(process, url)
        yield url
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def _find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _wait_for_port_forward(process: subprocess.Popen, url: str, timeout_seconds: int = 30):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            _, stderr = process.communicate(timeout=1)
            raise AssertionError(f"kubectl port-forward exited early: {stderr}")
        try:
            requests.get(f"{url}/api/services", timeout=2)
            return
        except requests.RequestException:
            time.sleep(1)
    process.terminate()
    _, stderr = process.communicate(timeout=10)
    raise AssertionError(f"Timed out waiting for kubectl port-forward to become ready: {stderr}")


def _wait_for_jaeger_query(url: str, timeout_seconds: int = 30):
    deadline = time.monotonic() + timeout_seconds
    last_error = None
    while time.monotonic() < deadline:
        try:
            requests.get(f"{url}/api/services", timeout=2).raise_for_status()
            return
        except requests.RequestException as e:
            last_error = e
            time.sleep(1)
    raise AssertionError(f"Timed out waiting for Jaeger query endpoint {url}: {last_error}")


def _jaeger_has_traces(jaeger_url: str, service_name: str, lookback_minutes: int) -> bool:
    response = requests.get(
        f"{jaeger_url}/api/traces",
        params={"service": service_name, "lookback": f"{lookback_minutes}m", "limit": 20},
        timeout=10
    )
    response.raise_for_status()
    traces = response.json().get("data", [])
    return bool(traces)


def _xray_services_with_recent_traces(client, service_names: Iterable[str], lookback_minutes: int) -> set[str]:
    expected_services = set(service_names)
    seen_services = set()
    trace_ids_to_check = []
    end_time = datetime.now(timezone.utc)
    request = {
        "StartTime": end_time - timedelta(minutes=lookback_minutes),
        "EndTime": end_time,
    }
    while True:
        response = client.get_trace_summaries(**request)
        raise_for_aws_api_error(response)
        for trace_summary in response.get("TraceSummaries", []):
            seen_services.update(_matching_xray_services(trace_summary, expected_services))
            if trace_summary.get("Id"):
                trace_ids_to_check.append(trace_summary["Id"])
        if expected_services.issubset(seen_services):
            return seen_services
        next_token = response.get("NextToken")
        if not next_token:
            seen_services.update(_xray_services_from_trace_documents(client, trace_ids_to_check, expected_services))
            return seen_services
        request["NextToken"] = next_token


def _matching_xray_services(trace_summary: dict, expected_services: set[str]) -> set[str]:
    matching_services = set()
    for service_id in trace_summary.get("ServiceIds", []):
        service_names = {service_id.get("Name")}
        service_names.update(service_id.get("Names", []) or [])
        matching_services.update(name for name in service_names if name in expected_services)
    return matching_services


def _xray_services_from_trace_documents(client, trace_ids: list[str], expected_services: set[str]) -> set[str]:
    matching_services = set()
    for i in range(0, len(trace_ids), 5):
        response = client.batch_get_traces(TraceIds=trace_ids[i:i + 5])
        raise_for_aws_api_error(response)
        for trace in response.get("Traces", []):
            for segment in trace.get("Segments", []):
                document = segment.get("Document")
                if not document:
                    continue
                matching_services.update(_matching_xray_document_services(document, expected_services))
    return matching_services


def _matching_xray_document_services(document: str, expected_services: set[str]) -> set[str]:
    try:
        segment = json.loads(document)
    except json.JSONDecodeError:
        return set()
    segment_names = {segment.get("name")}
    for subsegment in segment.get("subsegments", []) or []:
        segment_names.add(subsegment.get("name"))
    return {name for name in segment_names if name in expected_services}
