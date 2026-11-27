"""Sample running RFS containers and export one fleet observation per UTC minute via OTLP."""

from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import logging
import os
from pathlib import Path
import re
import time
from typing import NamedTuple, Optional
from urllib.parse import quote

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from kubernetes.utils.quantity import parse_quantity


INTERVAL = 15
MAX_SAMPLE_AGE = 60
SECRETS = Path('/var/run/secrets/kubernetes.io/serviceaccount')
TIER = re.compile(r'.*-rfs-t([0-9]+)-[a-z0-9]{1,10}-[a-z0-9]{5}')
RESOURCES = [('cpu', 'Cpu', 'usageNanoCores', 1e9), ('memory', 'Memory', 'workingSetBytes', 1)]


class Worker(NamedTuple):
    uid: str
    tier: str
    node: str
    started: float
    previous_end: Optional[float]
    limits: dict


def timestamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()


def kube(path):
    response = requests.get('https://kubernetes.default.svc' + path, timeout=(2, 6),
                            headers={'Authorization': 'Bearer ' + (SECRETS / 'token').read_text().strip()},
                            verify=str(SECRETS / 'ca.crt'))
    response.raise_for_status()
    return response.json()


def running_workers(pods):
    for pod in pods:
        spec = next((c for c in pod['spec']['containers'] if c['name'] == 'bulk-loader'), None)
        if not spec:
            continue
        tier = pod['metadata'].get('labels', {}).get('migrations.opensearch.org/rfs-memory-tier', '')
        if not tier.isascii() or not tier.isdecimal():
            match = TIER.fullmatch(pod['metadata']['name'])
            tier = match[1] if match else '0'
        status = next((c for c in pod['status'].get('containerStatuses', []) if c['name'] == 'bulk-loader'), {})
        running = status.get('state', {}).get('running')
        if running:
            previous = status.get('lastState', {}).get('terminated', {}).get('finishedAt')
            yield Worker(pod['metadata']['uid'], tier, pod['spec']['nodeName'], timestamp(running['startedAt']),
                         timestamp(previous) if previous else None, spec.get('resources', {}).get('limits', {}))


def node_stats(node):
    try:
        summary = kube('/api/v1/nodes/' + quote(node, safe='') + '/proxy/stats/summary?only_cpu_and_memory=true')
        return {p['podRef']['uid']: c for p in summary['pods'] for c in p.get('containers', [])
                if c['name'] == 'bulk-loader'}
    except (requests.RequestException, KeyError, ValueError):
        logging.exception('Kubelet stats unavailable for %s', node)
        return {}


def fresh_usage(container, resource, field, worker, now):
    sample = container.get(resource, {})
    if field not in sample or not sample.get('time') or not container.get('startTime'):
        return None
    # The kubelet can create the cgroup before the runtime marks the container started.
    # The UID and name identify the pod, and the previous finish excludes an exited incarnation.
    if worker.previous_end is not None and timestamp(container['startTime']) < worker.previous_end:
        return None
    observed = timestamp(sample['time'])
    if observed < worker.started or not -2 <= now - observed <= MAX_SAMPLE_AGE:
        return None
    return sample[field]


def measure(workers, stats, now):
    points = Counter()
    for worker in workers:
        points['Workers', worker.tier] += 1
        container = stats.get(worker.uid, {})
        for key, resource, field, divisor in RESOURCES:
            value = fresh_usage(container, key, field, worker, now)
            limit = float(parse_quantity(worker.limits.get(key, '0')))
            if value is None:
                points[resource + 'AwaitingSample', None] += limit
                points[resource + 'MissingWorkers', None] += 1
            else:
                used = value / divisor
                points[resource + 'Used', worker.tier] += used
                points[resource + 'Unused', None] += max(0, limit - used)
    return points


def sample(namespace, pool):
    pods = kube('/api/v1/namespaces/' + quote(namespace, safe='') + '/pods')['items']
    workers = list(running_workers(pods))
    stats = {}
    for by_uid in pool.map(node_stats, {w.node for w in workers}):
        stats.update(by_uid)
    return measure(workers, stats, time.time())


def payload(minute, snapshots, tiers, namespace):
    totals = Counter()
    for snapshot in snapshots:
        totals.update(snapshot)
    tiers.update(tier for _, tier in totals if tier is not None)
    metrics = []

    def gauge(name, dimension_values, count=False):
        data = []
        for tier in dimension_values:
            attrs = {'namespace': namespace}
            if tier is not None:
                attrs['tier'] = tier
            data.append({'attributes': [{'key': k, 'value': {'stringValue': v}} for k, v in attrs.items()],
                         'timeUnixNano': str(minute * 1_000_000_000),
                         'asDouble': len(snapshots) if count else totals[name, tier] / len(snapshots)})
        metrics.append({'name': 'rfsFleet' + name, 'gauge': {'dataPoints': data}})

    ordered_tiers = sorted(tiers, key=int)
    gauge('Workers', ordered_tiers)
    for _, resource, _, _ in RESOURCES:
        gauge(resource + 'Used', ordered_tiers)
        for suffix in ('Unused', 'AwaitingSample', 'MissingWorkers'):
            gauge(resource + suffix, [None])
    gauge('CensusSamples', [None], count=True)
    return {'resourceMetrics': [{'scopeMetrics': [{'scope': {'name': 'rfsFleetResources'}, 'metrics': metrics}]}]}


def export(session, endpoint, minute, observation):
    try:
        response = session.post(endpoint, json=observation, timeout=(2, 5))
        response.raise_for_status()
        rejected = response.json().get('partialSuccess', {}).get('rejectedDataPoints', 0)
        if int(rejected):
            logging.error('Collector rejected %s fleet data points for %s', rejected, minute)
    except (requests.RequestException, ValueError):
        logging.exception('Fleet observation export failed for %s', minute)


def main():
    namespace = (SECRETS / 'namespace').read_text().strip()
    endpoint = os.environ['OTLP_ENDPOINT']
    minute = None
    snapshots = []
    tiers = {'0'}
    # Replay connection failures and explicit throttling/unavailability. Retrying an ambiguous
    # read timeout could double the minute's CloudWatch Sum. Export never delays the next census.
    retry = Retry(total=3, connect=3, read=0, other=0, status_forcelist=(429, 503),
                  allowed_methods={'POST'}, backoff_factor=0.25, respect_retry_after_header=False)
    with requests.Session() as session, ThreadPoolExecutor(1) as sender, ThreadPoolExecutor(12) as pool:
        session.mount('http://', HTTPAdapter(max_retries=retry))
        while True:
            tick = int(time.time()) // INTERVAL * INTERVAL
            bucket = tick // 60 * 60
            if minute != bucket:
                if snapshots:
                    sender.submit(export, session, endpoint, minute, payload(minute, snapshots, tiers, namespace))
                snapshots = []
                minute = bucket
            try:
                snapshots.append(sample(namespace, pool))
            except (requests.RequestException, ValueError, KeyError):
                logging.exception('Fleet census unavailable')
            time.sleep(max(0, tick + INTERVAL - time.time()))


if __name__ == '__main__':
    main()
