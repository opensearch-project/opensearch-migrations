import base64
import json
import logging
import os
import tempfile
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Callable, Dict, Iterable, List, Optional, Sequence

import yaml
from kubernetes import client
from kubernetes.client.rest import ApiException

from console_link.models.cluster import Cluster, SourceCluster
from console_link.models.factories import get_kafka
from console_link.models.kafka import Kafka
from console_link.workflow.models.utils import load_k8s_config, get_current_namespace
from console_link.workflow.services.script_runner import ScriptRunner

logger = logging.getLogger(__name__)

CRD_GROUP = "migrations.opensearch.org"
CRD_VERSION = "v1alpha1"


class ResourceRole(str, Enum):
    SOURCE = "source"
    TARGET = "target"
    PROXY = "proxy"
    KAFKA = "kafka"


CLUSTER_RESOURCE_ROLES = (ResourceRole.SOURCE, ResourceRole.TARGET, ResourceRole.PROXY)


K8S_SELECTOR_PREFIXES = {
    ResourceRole.PROXY: ("captureproxy", "captureproxies"),
    ResourceRole.KAFKA: ("kafkacluster", "kafkaclusters"),
}


class ResourceSelectionError(ValueError):
    pass


@dataclass
class ConsoleResourceEntry:
    role: ResourceRole
    ref_name: str
    aliases: List[str]
    source: Optional[str] = None
    client_config: Optional[Dict] = None
    kafka_runtime: Optional[Dict] = None
    k8s_name: Optional[str] = None
    phase: Optional[str] = None
    origins: set[str] = field(default_factory=set)
    proxy_config: Optional[Dict] = None

    def all_selectors(self) -> List[str]:
        values = [*self.public_selectors(), *self.aliases]
        for name in _dedupe([self.ref_name, self.k8s_name]):
            values.extend(_k8s_selector_aliases(self.role, name))
            values.extend(_fully_qualified_k8s_selector_aliases(self.role, name))
        return _dedupe(values)

    def public_selectors(self) -> List[str]:
        values = [self.ref_name, *self.aliases]
        if self.k8s_name:
            values.append(self.k8s_name)
        return _dedupe(
            value for value in values
            if value and not _is_k8s_selector_alias(self.role, value)
        )

    def matches(self, selector: str) -> bool:
        selectors = self.all_selectors()
        return any(candidate in selectors for candidate in _selector_match_candidates(self.role, selector))


@dataclass(frozen=True)
class ConsoleConsumerGroupEntry:
    name: str
    kafka_ref: Optional[str] = None
    target_ref: Optional[str] = None
    replay_ref: Optional[str] = None


class ConsoleResourceCatalog:
    def __init__(self, entries: Optional[Iterable[ConsoleResourceEntry]] = None, namespace: Optional[str] = None):
        self.entries: List[ConsoleResourceEntry] = list(entries or [])
        self.namespace = namespace
        self.consumer_groups: List[ConsoleConsumerGroupEntry] = []

    @classmethod
    def empty(cls) -> "ConsoleResourceCatalog":
        return cls([])

    @classmethod
    def from_legacy_environment(cls, env) -> "ConsoleResourceCatalog":
        entries: List[ConsoleResourceEntry] = []
        if getattr(env, "source_cluster", None) is not None:
            source_config = dict(env.source_cluster.config)
            source_proxy = getattr(env.source_cluster, "proxy", None)
            proxy_config = dict(source_proxy.config) if source_proxy is not None else None
            entries.append(ConsoleResourceEntry(
                role=ResourceRole.SOURCE,
                ref_name="source",
                aliases=["source"],
                client_config=source_config,
                proxy_config=proxy_config,
                origins={"config"},
            ))
            if source_proxy is not None:
                proxy_name = getattr(env.source_cluster, "proxy_name", None) or "proxy"
                entries.append(ConsoleResourceEntry(
                    role=ResourceRole.PROXY,
                    ref_name=proxy_name,
                    aliases=["proxy", proxy_name],
                    client_config=proxy_config,
                    origins={"config"},
                ))
        if getattr(env, "target_cluster", None) is not None:
            entries.append(ConsoleResourceEntry(
                role=ResourceRole.TARGET,
                ref_name="target",
                aliases=["target"],
                client_config=dict(env.target_cluster.config),
                origins={"config"},
            ))
        if getattr(env, "kafka", None) is not None:
            entries.append(ConsoleResourceEntry(
                role=ResourceRole.KAFKA,
                ref_name="default",
                aliases=["default"],
                kafka_runtime={"type": "instance"},
                origins={"config"},
            ))
        catalog = cls(entries)
        catalog._legacy_env = env
        return catalog

    @classmethod
    def from_k8s(
        cls,
        namespace: Optional[str] = None,
        session_name: str = "default",
        config: Optional[object] = None,
        runner_factory: Callable[[], ScriptRunner] = ScriptRunner,
        custom_api: Optional[client.CustomObjectsApi] = None,
    ) -> "ConsoleResourceCatalog":
        namespace = namespace or get_current_namespace()
        load_k8s_config()
        custom = custom_api or client.CustomObjectsApi()
        raw_catalogs: List[Dict] = []

        if config:
            config_text = getattr(config, "raw_yaml", None) or yaml.safe_dump(getattr(config, "data", config))
            raw_catalogs.append(
                cls._run_resolver(runner_factory(), config_text, "--user-config", source="config")
            )

        latest_run = cls._latest_migration_run(custom, namespace)
        if latest_run is not None:
            resolved_config = latest_run.get("spec", {}).get("resolvedConfig")
            if resolved_config:
                raw_catalogs.append(
                    cls._run_resolver(
                        runner_factory(),
                        json.dumps(resolved_config),
                        "--resolved-config",
                        source="migrationRun",
                    )
                )

        catalog = cls([], namespace=namespace)
        for raw in raw_catalogs:
            catalog.merge(cls._from_resolver_output(raw))
        catalog.overlay_live_resources(custom, namespace)
        return catalog

    @staticmethod
    def _run_resolver(runner: ScriptRunner, input_data: str, input_arg: str, source: str) -> Dict:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yaml", delete=False) as temp_file:
            temp_file.write(input_data)
            temp_path = temp_file.name
        try:
            output = runner.run_config_processor_node_script("resolveConsoleResources", input_arg, temp_path)
            catalog = json.loads(output)
            for section in ("sources", "targets", "kafkas"):
                for item in catalog.get(section, []):
                    item.setdefault("source", source)
            return catalog
        finally:
            try:
                os.unlink(temp_path)
            except OSError:
                logger.warning("Failed to clean up temporary resolver input %s", temp_path)

    @classmethod
    def _from_resolver_output(cls, data: Dict) -> "ConsoleResourceCatalog":
        entries: List[ConsoleResourceEntry] = []
        for source in data.get("sources", []):
            proxy = source.get("proxy")
            entries.append(ConsoleResourceEntry(
                role=ResourceRole.SOURCE,
                ref_name=source["refName"],
                aliases=source.get("aliases", []),
                source=source.get("source"),
                client_config=source.get("clientConfig"),
                proxy_config=_source_proxy_config(proxy.get("clientConfig")) if proxy else None,
                origins={source.get("source") or "config"},
            ))
            if proxy:
                entries.append(ConsoleResourceEntry(
                    role=ResourceRole.PROXY,
                    ref_name=proxy["refName"],
                    aliases=proxy.get("aliases", []),
                    source=source.get("source"),
                    client_config=proxy.get("clientConfig"),
                    k8s_name=proxy.get("k8sName"),
                    origins={source.get("source") or "config"},
                ))
        for target in data.get("targets", []):
            entries.append(ConsoleResourceEntry(
                role=ResourceRole.TARGET,
                ref_name=target["refName"],
                aliases=target.get("aliases", []),
                source=target.get("source"),
                client_config=target.get("clientConfig"),
                origins={target.get("source") or "config"},
            ))
        for kafka in data.get("kafkas", []):
            entries.append(ConsoleResourceEntry(
                role=ResourceRole.KAFKA,
                ref_name=kafka["refName"],
                aliases=kafka.get("aliases", []),
                source=kafka.get("source"),
                kafka_runtime=kafka.get("runtime"),
                k8s_name=kafka.get("k8sName"),
                origins={kafka.get("source") or "config"},
            ))
        catalog = cls(entries)
        catalog.consumer_groups = [
            ConsoleConsumerGroupEntry(
                name=group["name"],
                kafka_ref=group.get("kafkaRef"),
                target_ref=group.get("targetRef"),
                replay_ref=group.get("replayRef"),
            )
            for group in data.get("consumerGroups", [])
        ]
        return catalog

    def merge(self, other: "ConsoleResourceCatalog") -> None:
        for incoming in other.entries:
            existing = self._same_ref_entry(incoming)
            if existing is None:
                self.entries.append(incoming)
                continue
            existing.origins.update(incoming.origins)
            if "migrationRun" in incoming.origins:
                existing.client_config = incoming.client_config or existing.client_config
                existing.kafka_runtime = incoming.kafka_runtime or existing.kafka_runtime
                existing.proxy_config = incoming.proxy_config or existing.proxy_config
            existing.aliases = _dedupe([*existing.aliases, *incoming.aliases])
            existing.k8s_name = incoming.k8s_name or existing.k8s_name
            existing.phase = incoming.phase or existing.phase
        if hasattr(other, "consumer_groups"):
            self.consumer_groups = _dedupe([
                *getattr(self, "consumer_groups", []),
                *getattr(other, "consumer_groups", []),
            ])

    def consumer_group_entries(self, kafka_selector: Optional[str] = None) -> List[ConsoleConsumerGroupEntry]:
        groups = list(self.consumer_groups)
        if kafka_selector is None and len(self.candidates(ResourceRole.KAFKA)) != 1:
            return groups
        kafka_entry = self.resolve(ResourceRole.KAFKA, kafka_selector)
        return [
            group
            for group in groups
            if group.kafka_ref is None or kafka_entry.matches(group.kafka_ref)
        ]

    def consumer_group_names(self, kafka_selector: Optional[str] = None) -> List[str]:
        return _dedupe(group.name for group in self.consumer_group_entries(kafka_selector))

    def _same_ref_entry(self, incoming: ConsoleResourceEntry) -> Optional[ConsoleResourceEntry]:
        for entry in self.entries:
            if entry.role == incoming.role and entry.ref_name == incoming.ref_name:
                return entry
        return None

    def candidates(self, role: ResourceRole) -> List[ConsoleResourceEntry]:
        return [entry for entry in self.entries if entry.role == role]

    def has_any(self, *roles: ResourceRole) -> bool:
        return any(self.candidates(role) for role in roles)

    def selector_values(self, role: ResourceRole) -> List[str]:
        values: List[str] = []
        for entry in self.candidates(role):
            values.extend(entry.public_selectors())
        return sorted(_dedupe(values))

    def cluster_selector_values(self, roles: Sequence[ResourceRole] = CLUSTER_RESOURCE_ROLES) -> List[str]:
        values: List[str] = []
        for role in roles:
            candidates = self.candidates(role)
            if len(candidates) == 1:
                values.append(role.value)
            for entry in candidates:
                values.append(entry.ref_name)
                if entry.k8s_name and entry.k8s_name != entry.ref_name:
                    values.append(entry.k8s_name)
        return sorted(_dedupe(values))

    def matching_cluster_entries(
        self,
        selector: str,
        roles: Sequence[ResourceRole] = CLUSTER_RESOURCE_ROLES,
    ) -> List[ConsoleResourceEntry]:
        return [
            entry
            for role in roles
            for entry in self.candidates(role)
            if entry.matches(selector)
        ]

    def resolve(self, role: ResourceRole, selector: Optional[str] = None) -> ConsoleResourceEntry:
        candidates = self.candidates(role)
        if selector:
            matches = [entry for entry in candidates if entry.matches(selector)]
            if len(matches) == 1:
                return matches[0]
            if not matches:
                raise ResourceSelectionError(
                    f"No {role.value} resource matches '{selector}'. Valid selectors: "
                    f"{', '.join(self.selector_values(role)) or '<none>'}."
                )
            raise ResourceSelectionError(
                f"Selector '{selector}' matches multiple {role.value} resources. "
                f"Use one of: {', '.join(self.selector_values(role))}."
            )
        if len(candidates) == 1:
            return candidates[0]
        if not candidates:
            raise ResourceSelectionError(f"No {role.value} resource is configured for this deployment.")
        raise ResourceSelectionError(
            f"Multiple {role.value} resources are configured: "
            f"{', '.join(entry.ref_name for entry in candidates)}. "
            f"Specify one of: {', '.join(self.selector_values(role))}."
        )

    def resolve_cluster_selector(
        self,
        selector: str,
        roles: Sequence[ResourceRole] = CLUSTER_RESOURCE_ROLES,
        role_selectors: Optional[Dict[ResourceRole, Optional[str]]] = None,
        client_options=None,
    ):
        role_selectors = role_selectors or {}
        role_by_name = {role.value: role for role in roles}
        role = role_by_name.get(selector.lower())
        explicit_role_selector = role_selectors.get(role) if role else None
        if role and explicit_role_selector:
            return self.resolve_cluster(role, explicit_role_selector, client_options=client_options)

        matches = self.matching_cluster_entries(selector, roles)
        if len(matches) == 1:
            if any(role_selectors.get(candidate_role) for candidate_role in roles):
                raise ResourceSelectionError(
                    "Resource-specific selector flags cannot be combined with concrete "
                    f"cluster selector '{selector}'."
                )
            match = matches[0]
            return self.resolve_cluster(match.role, selector, client_options=client_options)
        if len(matches) > 1:
            raise ResourceSelectionError(
                f"Cluster selector '{selector}' matches multiple resources: "
                f"{', '.join(_entry_display_name(entry) for entry in matches)}. "
                f"Use one of: {', '.join(self.cluster_selector_values(roles))}."
            )
        if role:
            return self.resolve_cluster(role, client_options=client_options)
        raise ResourceSelectionError(
            f"No cluster resource matches '{selector}'. Valid selectors: "
            f"{', '.join(self.cluster_selector_values(roles)) or '<none>'}."
        )

    def resolve_cluster(self, role: ResourceRole, selector: Optional[str] = None, client_options=None):
        if hasattr(self, "_legacy_env") and selector is None:
            if role == ResourceRole.SOURCE and getattr(self._legacy_env, "source_cluster", None):
                return self._legacy_env.source_cluster
            if role == ResourceRole.TARGET and getattr(self._legacy_env, "target_cluster", None):
                return self._legacy_env.target_cluster
            if role == ResourceRole.PROXY and getattr(self._legacy_env, "proxy", None):
                return self._legacy_env.proxy
        entry = self.resolve(role, selector)
        if not entry.client_config:
            raise ResourceSelectionError(f"{role.value} resource '{entry.ref_name}' has no client configuration.")
        if role == ResourceRole.SOURCE:
            config = dict(entry.client_config)
            if entry.proxy_config:
                config["proxy"] = entry.proxy_config
            return SourceCluster(config=config, client_options=client_options)
        return Cluster(config=entry.client_config, client_options=client_options)

    def resolve_kafka(self, selector: Optional[str] = None) -> Kafka:
        if hasattr(self, "_legacy_env") and selector is None and getattr(self._legacy_env, "kafka", None):
            return self._legacy_env.kafka
        entry = self.resolve(ResourceRole.KAFKA, selector)
        runtime = dict(entry.kafka_runtime or {})
        if self.namespace:
            runtime.setdefault("namespace", self.namespace)
        if runtime.get("type") == "direct":
            client_config = dict(runtime["clientConfig"])
            if "scram" in client_config and runtime.get("secretName"):
                password, ca_cert_path = _resolve_strimzi_scram_credentials(
                    username_secret=runtime["secretName"],
                    ca_secret=runtime.get("caSecretName"),
                    namespace=runtime.get("namespace"),
                )
                scram_config = dict(client_config["scram"])
                if ca_cert_path:
                    scram_config["ca_cert_path"] = ca_cert_path
                client_config["scram"] = scram_config
                return get_kafka(client_config, scram_password=password)
            return get_kafka(client_config)
        if runtime.get("type") == "strimzi":
            return _build_strimzi_kafka(runtime)
        raise ResourceSelectionError(f"Kafka resource '{entry.ref_name}' has unsupported runtime metadata.")

    def overlay_live_resources(self, custom: client.CustomObjectsApi, namespace: str) -> None:
        self._overlay_live_cluster_labels(custom, namespace)
        for plural, role in {
            "kafkaclusters": ResourceRole.KAFKA,
            "captureproxies": ResourceRole.PROXY,
        }.items():
            for item in _list_custom_objects(custom, namespace, plural):
                name = item.get("metadata", {}).get("name")
                if not name:
                    continue
                entry = self._find_by_role_and_name(role, name)
                if entry is None and role == ResourceRole.KAFKA:
                    entry = ConsoleResourceEntry(
                        role=ResourceRole.KAFKA,
                        ref_name=name,
                        aliases=[name, f"kafkacluster.{name}"],
                        k8s_name=name,
                        kafka_runtime={
                            "type": "strimzi",
                            "clusterName": name,
                            "namespace": namespace,
                            "authType": "scram-sha-512",
                            "listenerName": "tls",
                            "usernameSecret": f"{name}-migration-app",
                            "caSecret": f"{name}-cluster-ca-cert",
                            "kafkaUserName": f"{name}-migration-app",
                        },
                        origins={"deployed"},
                    )
                    self.entries.append(entry)
                if entry is not None:
                    entry.k8s_name = name
                    entry.phase = item.get("status", {}).get("phase")
                    entry.aliases = _dedupe([
                        *entry.aliases,
                        f"{_singular(plural)}.{name}",
                    ])
                    entry.origins.add("deployed")

    def _find_by_role_and_name(self, role: ResourceRole, name: str) -> Optional[ConsoleResourceEntry]:
        for entry in self.candidates(role):
            if entry.ref_name == name or name in entry.all_selectors():
                return entry
        return None

    def _overlay_live_cluster_labels(self, custom: client.CustomObjectsApi, namespace: str) -> None:
        for plural in ("captureproxies", "datasnapshots", "snapshotmigrations", "trafficreplays"):
            for item in _list_custom_objects(custom, namespace, plural):
                labels = item.get("metadata", {}).get("labels", {})
                spec = item.get("spec", {})
                phase = item.get("status", {}).get("phase")
                source_name = labels.get(f"{CRD_GROUP}/source") or spec.get("sourceLabel")
                target_name = labels.get(f"{CRD_GROUP}/target") or spec.get("targetLabel")
                self._overlay_labeled_cluster(ResourceRole.SOURCE, source_name, phase)
                self._overlay_labeled_cluster(ResourceRole.TARGET, target_name, phase)

    def _overlay_labeled_cluster(self, role: ResourceRole, ref_name: Optional[str], phase: Optional[str]) -> None:
        if not ref_name:
            return
        entry = self._find_by_role_and_name(role, ref_name)
        if entry is None:
            self.entries.append(ConsoleResourceEntry(
                role=role,
                ref_name=ref_name,
                aliases=[ref_name],
                origins={"deployed"},
                phase=phase,
            ))
            return
        entry.phase = phase or entry.phase
        entry.origins.add("deployed")

    @staticmethod
    def _latest_migration_run(custom: client.CustomObjectsApi, namespace: str) -> Optional[Dict]:
        runs = _list_custom_objects(custom, namespace, "migrationruns")
        if not runs:
            return None
        return sorted(runs, key=_migration_run_sort_key, reverse=True)[0]


def _source_proxy_config(config: Optional[Dict]) -> Optional[Dict]:
    if not config:
        return None
    return {
        key: config[key]
        for key in ("name", "endpoint", "allow_insecure")
        if key in config
    }


def _build_strimzi_kafka(runtime: Dict) -> Kafka:
    cluster_name = runtime["clusterName"]
    namespace = runtime.get("namespace")
    auth_type = runtime.get("authType") or "scram-sha-512"
    listener_name = runtime.get("listenerName") or ("tls" if auth_type == "scram-sha-512" else "plain")
    bootstrap = _resolve_strimzi_bootstrap(cluster_name, listener_name, namespace=namespace)
    if auth_type == "scram-sha-512":
        password, ca_cert_path = _resolve_strimzi_scram_credentials(
            username_secret=runtime.get("usernameSecret") or f"{cluster_name}-migration-app",
            ca_secret=runtime.get("caSecret") or f"{cluster_name}-cluster-ca-cert",
            namespace=namespace,
        )
        scram_config = {
            "username": runtime.get("kafkaUserName") or runtime.get("usernameSecret") or f"{cluster_name}-migration-app",
        }
        if ca_cert_path:
            scram_config["ca_cert_path"] = ca_cert_path
        return get_kafka({"broker_endpoints": bootstrap, "scram": scram_config}, scram_password=password)
    return get_kafka({"broker_endpoints": bootstrap, "standard": None})


def _resolve_strimzi_bootstrap(cluster_name: str, listener_name: str, namespace: Optional[str] = None) -> str:
    load_k8s_config()
    custom = client.CustomObjectsApi()
    kafka_cr = custom.get_namespaced_custom_object(
        group="kafka.strimzi.io", version="v1",
        namespace=namespace or get_current_namespace(), plural="kafkas", name=cluster_name,
    )
    for listener in kafka_cr.get("status", {}).get("listeners", []):
        if listener.get("name") == listener_name:
            return listener["bootstrapServers"]
    raise ValueError(f"Kafka CR '{cluster_name}' has no listener named '{listener_name}' in .status.listeners")


def _resolve_strimzi_scram_credentials(
    username_secret: str,
    ca_secret: Optional[str],
    namespace: Optional[str] = None,
) -> tuple[str, Optional[str]]:
    load_k8s_config()
    namespace = namespace or get_current_namespace()
    v1 = client.CoreV1Api()
    secret = v1.read_namespaced_secret(name=username_secret, namespace=namespace)
    password_b64 = secret.data.get("password")
    if not password_b64:
        raise ValueError(f"Secret '{username_secret}' in namespace '{namespace}' has no 'password' key")
    password = base64.b64decode(password_b64).decode("utf-8")

    ca_cert_path = None
    if ca_secret:
        try:
            ca = v1.read_namespaced_secret(name=ca_secret, namespace=namespace)
            ca_crt_b64 = ca.data.get("ca.crt")
            if ca_crt_b64:
                ca_crt = base64.b64decode(ca_crt_b64).decode("utf-8")
                fd, ca_cert_path = tempfile.mkstemp(prefix="kafka-ca-", suffix=".crt")
                with os.fdopen(fd, "w") as f:
                    f.write(ca_crt)
        except Exception as e:
            logger.warning("Could not read CA cert from secret '%s': %s", ca_secret, e)
    return password, ca_cert_path


def _list_custom_objects(custom: client.CustomObjectsApi, namespace: str, plural: str) -> List[Dict]:
    try:
        return custom.list_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural=plural,
        ).get("items", [])
    except ApiException as e:
        if e.status == 404:
            return []
        raise


def _migration_run_sort_key(item: Dict):
    spec = item.get("spec", {})
    run_number = spec.get("runNumber")
    if isinstance(run_number, int):
        number_key = run_number
    else:
        try:
            number_key = int(run_number)
        except (TypeError, ValueError):
            number_key = -1
    timestamp = spec.get("timestamp") or item.get("metadata", {}).get("creationTimestamp")
    return number_key, _parse_timestamp(timestamp)


def _entry_display_name(entry: ConsoleResourceEntry) -> str:
    return f"{entry.role.value}/{entry.ref_name}"


def _parse_timestamp(value: Optional[str]) -> datetime:
    if not value:
        return datetime.min.replace(tzinfo=timezone.utc)
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return datetime.min.replace(tzinfo=timezone.utc)


def _singular(plural: str) -> str:
    return {
        "kafkaclusters": "kafkacluster",
        "captureproxies": "captureproxy",
    }.get(plural, plural)


def _public_k8s_selector_aliases(role: ResourceRole, name: str) -> List[str]:
    prefixes = K8S_SELECTOR_PREFIXES.get(role)
    if not prefixes:
        return []
    singular, _ = prefixes
    return [f"{singular}.{name}"]


def _k8s_selector_aliases(role: ResourceRole, name: str) -> List[str]:
    prefixes = K8S_SELECTOR_PREFIXES.get(role)
    if not prefixes:
        return []
    singular, plural = prefixes
    return [
        f"{singular}.{name}",
        f"{singular}/{name}",
        f"{plural}/{name}",
    ]


def _fully_qualified_k8s_selector_aliases(role: ResourceRole, name: str) -> List[str]:
    prefixes = K8S_SELECTOR_PREFIXES.get(role)
    if not prefixes:
        return []
    _, plural = prefixes
    return [f"{plural}.{CRD_GROUP}/{name}"]


def _selector_match_candidates(role: ResourceRole, selector: str) -> List[str]:
    values = [selector]
    k8s_name = _k8s_selector_name(role, selector)
    if k8s_name:
        values.append(k8s_name)
        values.extend(_k8s_selector_aliases(role, k8s_name))
        values.extend(_fully_qualified_k8s_selector_aliases(role, k8s_name))
    return _dedupe(values)


def _k8s_selector_name(role: ResourceRole, selector: str) -> Optional[str]:
    prefixes = K8S_SELECTOR_PREFIXES.get(role)
    if not prefixes:
        return None

    if "/" in selector:
        resource_prefix, name = selector.split("/", 1)
        suffix = f".{CRD_GROUP}"
        if resource_prefix.endswith(suffix):
            resource_prefix = resource_prefix[:-len(suffix)]
        if name and resource_prefix in prefixes:
            return name

    singular, plural = prefixes
    for prefix in (singular, plural):
        dotted_prefix = f"{prefix}."
        if selector.startswith(dotted_prefix):
            name = selector[len(dotted_prefix):]
            return name or None
    return None


def _is_fully_qualified_k8s_selector(value: str) -> bool:
    return f".{CRD_GROUP}/" in value


def _is_k8s_selector_alias(role: ResourceRole, value: str) -> bool:
    return _is_fully_qualified_k8s_selector(value) or _k8s_selector_name(role, value) is not None


def _dedupe(values: Iterable[Optional[str]]) -> List[str]:
    seen = set()
    result = []
    for value in values:
        if value and value not in seen:
            result.append(value)
            seen.add(value)
    return result
