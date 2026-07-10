"""Reset command for workflow CLI - delete migration CRDs safely."""

import json
import logging
import time

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config, get_current_namespace
from .artifact_store import ArtifactStoreError, artifact_uri, delete_artifact_prefix
from .crd_utils import (
    CRD_GROUP,
    CRD_VERSION,
    DISPLAY_NAMES,
    RESETTABLE_PLURALS,
    has_glob,
    list_migration_resources,
    match_names,
    parse_resource_path,
    resource_display_name,
)

logger = logging.getLogger(__name__)

ARTIFACT_OUTPUT_ROOT = "migration-outputs"
NONE_PLACEHOLDER = "<none>"
CR_DELETION_TIMEOUT_SECONDS = 600
OWNED_RESOURCE_DELETION_TIMEOUT_SECONDS = 120
TARGET_INDEX_WARNING = (
    "reset does not delete indexes on the target cluster. If the snapshot migration "
    "created indexes on the target, remove them with: console clusters clear-indices --cluster target"
)


def _resettable_names(namespace):
    return [resource_display_name(p, n) for p, n, _, _ in list_migration_resources(namespace)]


def _find_resource_by_name(namespace, path):
    """Find a single resource by type.name path."""
    custom = client.CustomObjectsApi()
    parsed = parse_resource_path(path)
    search_plurals = [parsed[0]] if parsed else RESETTABLE_PLURALS
    search_name = parsed[1] if parsed else path
    for plural in search_plurals:
        try:
            item = custom.get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
                name=search_name,
            )
            phase = item.get('status', {}).get('phase', 'Unknown')
            deps = item.get('spec', {}).get('dependsOn', []) or []
            return (plural, search_name, phase, deps)
        except ApiException:
            pass
    return None


def _delete_crd(namespace, plural, name):
    """Delete a CRD instance with foreground cascading. Returns True on success."""
    custom = client.CustomObjectsApi()
    try:
        custom.delete_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural=plural,
            name=name,
            body=client.V1DeleteOptions(propagation_policy='Foreground'),
        )
        return True
    except ApiException as e:
        if e.status == 404:
            return True
        logger.error(f"Failed to delete {plural}/{name}: {e}")
        return False


def _wait_until_gone(namespace, plural, names, timeout=CR_DELETION_TIMEOUT_SECONDS):
    """Wait until all named CRDs are fully deleted."""
    custom = client.CustomObjectsApi()
    deadline = time.time() + timeout
    remaining = set(names)
    while remaining and time.time() < deadline:
        gone = set()
        for name in remaining:
            try:
                custom.get_namespaced_custom_object(
                    group=CRD_GROUP,
                    version=CRD_VERSION,
                    namespace=namespace,
                    plural=plural,
                    name=name,
                )
            except ApiException as e:
                if e.status == 404:
                    gone.add(name)
        remaining -= gone
        if remaining:
            time.sleep(2)
    if remaining:
        timeout_message = (
            f"Timed out waiting for deletion: {remaining}. Resource deletion was requested, "
            f"but the resource is still present. Check deletionTimestamp, finalizers, "
            f"ownerReferences, and status with: kubectl get {plural} {','.join(sorted(remaining))} "
            f"-n {namespace} -o yaml"
        )
        click.echo(f"  Error: {timeout_message}", err=True)
        logger.error(timeout_message)
        _emit_deletion_diagnostics(namespace, plural, sorted(remaining))
        return False
    return True


def _get_resource_completions(ctx, _, incomplete):
    namespace = ctx.params.get('namespace', 'ma')
    try:
        load_k8s_config()
        names = _resettable_names(namespace)
    except Exception:
        return []
    return [name for name in names if name.startswith(incomplete)]


def _find_dependents(target_names, all_resources):
    """Find all resources that transitively depend on any of target_names."""
    dependents = []
    found = set(target_names)
    changed = True
    while changed:
        changed = False
        for _, name, _, deps in all_resources:
            if name not in found and any(dep in found for dep in deps):
                dependents.append(name)
                found.add(name)
                changed = True
    return dependents


def _resolve_targets(namespace, path):
    """Resolve a type.name path or glob to matching resources."""
    if has_glob(path):
        resources = list_migration_resources(namespace)
        display_names = [resource_display_name(p, n) for p, n, _, _ in resources]
        matched = set(match_names(display_names, path))
        return [r for r in resources if resource_display_name(r[0], r[1]) in matched]
    match = _find_resource_by_name(namespace, path)
    return [match] if match else []


# --- Owned resource cleanup ---
# Each migration CR type maps to an ordered list of child resource groups to delete
# before the parent CR. Each entry: (api_group_version, plural, label_key_or_None)
#   label_key: select by label (value = CR name), or None to match by name directly.
# Groups are processed in order. Within each group all matches are deleted and awaited.

_STRIMZI_API = 'kafka.strimzi.io/v1'
_STRIMZI_CORE_API = 'core.strimzi.io/v1beta2'
_STRIMZI_CLUSTER_LABEL = 'strimzi.io/cluster'
_APPS_API = 'apps/v1'
_BATCH_API = 'batch/v1'
_POLICY_API = 'policy/v1'
_RBAC_API = 'rbac.authorization.k8s.io/v1'

_OWNER_REF_DIAGNOSTIC_RESOURCES = [
    ('v1', 'pods'),
    ('v1', 'persistentvolumeclaims'),
    ('v1', 'services'),
    ('v1', 'secrets'),
    ('v1', 'configmaps'),
    ('v1', 'serviceaccounts'),
    (_APPS_API, 'deployments'),
    (_APPS_API, 'statefulsets'),
    (_APPS_API, 'replicasets'),
    (_BATCH_API, 'jobs'),
    (_POLICY_API, 'poddisruptionbudgets'),
    (_RBAC_API, 'roles'),
    (_RBAC_API, 'rolebindings'),
    (_STRIMZI_API, 'kafkas'),
    (_STRIMZI_API, 'kafkanodepools'),
    (_STRIMZI_API, 'kafkatopics'),
    (_STRIMZI_API, 'kafkausers'),
    (_STRIMZI_CORE_API, 'strimzipodsets'),
]

_OWNED_RESOURCE_CLEANUP = {
    'kafkaclusters': [
        # Topics/users first — Strimzi topic operator needs the cluster alive
        (_STRIMZI_API, 'kafkatopics', _STRIMZI_CLUSTER_LABEL),
        (_STRIMZI_API, 'kafkausers', _STRIMZI_CLUSTER_LABEL),
        # Kafka CR next — Strimzi tears down brokers
        (_STRIMZI_API, 'kafkas', None),
        # Strimzi-generated podsets can lag behind the Kafka CR in busy clusters
        (_STRIMZI_CORE_API, 'strimzipodsets', _STRIMZI_CLUSTER_LABEL),
        # Nodepool last — Strimzi honors deleteClaim when it processes nodepool deletion
        (_STRIMZI_API, 'kafkanodepools', _STRIMZI_CLUSTER_LABEL),
    ],
    'captureproxies': [
        (_APPS_API, 'deployments', None),
        ('v1', 'services', None),
        ('cert-manager.io/v1', 'certificates', None),
    ],
    'snapshotmigrations': [
        # RFS workers before coordinator
        (_APPS_API, 'deployments', 'migrations.opensearch.org/from-snapshot-migration'),
        (_APPS_API, 'statefulsets', None),
        ('v1', 'services', None),
        ('v1', 'secrets', None),
    ],
    'trafficreplays': [
        (_APPS_API, 'deployments', None),
    ],
}


def _parse_api_gv(api_gv):
    if '/' in api_gv:
        g, v = api_gv.rsplit('/', 1)
        return g, v
    return '', api_gv


def _find_owned(namespace, api_gv, plural, label_key, cr_name):
    """Find resources by label selector or by name."""
    group, version = _parse_api_gv(api_gv)
    try:
        if group:
            custom = client.CustomObjectsApi()
            if label_key:
                return custom.list_namespaced_custom_object(
                    group=group, version=version, namespace=namespace,
                    plural=plural, label_selector=f"{label_key}={cr_name}"
                ).get('items', [])
            return [custom.get_namespaced_custom_object(
                group=group, version=version, namespace=namespace, plural=plural, name=cr_name)]
        # Core/apps API
        v1, apps = client.CoreV1Api(), client.AppsV1Api()
        dispatch = {
            'services': (v1.list_namespaced_service, v1.read_namespaced_service),
            'secrets': (v1.list_namespaced_secret, v1.read_namespaced_secret),
            'deployments': (apps.list_namespaced_deployment, apps.read_namespaced_deployment),
            'statefulsets': (apps.list_namespaced_stateful_set, apps.read_namespaced_stateful_set),
        }
        list_fn, read_fn = dispatch[plural]
        if label_key:
            return list_fn(namespace, label_selector=f"{label_key}={cr_name}").items
        return [read_fn(cr_name, namespace)]
    except (ApiException, KeyError):
        return []


def _list_items(response):
    """Return Kubernetes list items, treating mocked diagnostic responses as empty."""
    items = getattr(response, "items", None) if not isinstance(response, dict) else response.get("items", [])
    return items if isinstance(items, list) else []


def _list_namespaced_resources(namespace, api_gv, plural):
    """List a known namespaced resource group for stuck-delete diagnostics."""
    group, version = _parse_api_gv(api_gv)
    try:
        if group:
            return _list_items(client.CustomObjectsApi().list_namespaced_custom_object(
                group=group, version=version, namespace=namespace, plural=plural
            ))
        v1, apps = client.CoreV1Api(), client.AppsV1Api()
        dispatch = {
            'pods': v1.list_namespaced_pod,
            'persistentvolumeclaims': v1.list_namespaced_persistent_volume_claim,
            'services': v1.list_namespaced_service,
            'secrets': v1.list_namespaced_secret,
            'configmaps': v1.list_namespaced_config_map,
            'serviceaccounts': v1.list_namespaced_service_account,
            'deployments': apps.list_namespaced_deployment,
            'statefulsets': apps.list_namespaced_stateful_set,
            'replicasets': apps.list_namespaced_replica_set,
            'jobs': client.BatchV1Api().list_namespaced_job,
            'poddisruptionbudgets': client.PolicyV1Api().list_namespaced_pod_disruption_budget,
            'roles': client.RbacAuthorizationV1Api().list_namespaced_role,
            'rolebindings': client.RbacAuthorizationV1Api().list_namespaced_role_binding,
        }
        return _list_items(dispatch[plural](namespace))
    except (ApiException, KeyError):
        return []


def _delete_owned_resource(namespace, api_gv, plural, name):
    group, version = _parse_api_gv(api_gv)
    try:
        if group:
            client.CustomObjectsApi().delete_namespaced_custom_object(
                group=group, version=version, namespace=namespace, plural=plural, name=name)
        else:
            v1, apps = client.CoreV1Api(), client.AppsV1Api()
            dispatch = {
                'services': v1.delete_namespaced_service,
                'secrets': v1.delete_namespaced_secret,
                'deployments': apps.delete_namespaced_deployment,
                'statefulsets': apps.delete_namespaced_stateful_set,
                'jobs': client.BatchV1Api().delete_namespaced_job,
                'poddisruptionbudgets': client.PolicyV1Api().delete_namespaced_pod_disruption_budget,
                'roles': client.RbacAuthorizationV1Api().delete_namespaced_role,
                'rolebindings': client.RbacAuthorizationV1Api().delete_namespaced_role_binding,
            }
            dispatch[plural](name, namespace)
        logger.info(f"Deleted {plural}/{name}")
    except ApiException as e:
        if e.status != 404:
            logger.warning(f"Failed to delete {plural}/{name}: {e}")


def _item_name(item):
    return item['metadata']['name'] if isinstance(item, dict) else item.metadata.name


def _item_finalizers(item):
    return (item.get('metadata', {}).get('finalizers', []) if isinstance(item, dict)
            else item.metadata.finalizers or [])


def _item_metadata(item):
    return item.get('metadata', {}) if isinstance(item, dict) else item.metadata


def _item_status(item):
    return item.get('status', {}) if isinstance(item, dict) else getattr(item, 'status', None)


def _item_phase(item):
    status = _item_status(item)
    if isinstance(status, dict):
        return status.get('phase')
    return getattr(status, 'phase', None)


def _item_conditions(item):
    status = _item_status(item)
    if isinstance(status, dict):
        return status.get('conditions', [])
    return getattr(status, 'conditions', []) or []


def _metadata_field(metadata, key, default=None):
    if isinstance(metadata, dict):
        return metadata.get(key, default)
    return getattr(metadata, key, default)


def _format_owner_references(owner_refs):
    if not owner_refs:
        return "[]"
    formatted = []
    for ref in owner_refs:
        if isinstance(ref, dict):
            formatted.append(f"{ref.get('kind')}/{ref.get('name')}")
        else:
            formatted.append(f"{getattr(ref, 'kind', None)}/{getattr(ref, 'name', None)}")
    return "[" + ", ".join(formatted) + "]"


def _owner_ref_field(ref, field):
    if isinstance(ref, dict):
        return ref.get(field)
    return getattr(ref, field, None)


def _metadata_owner_references(metadata):
    return _metadata_field(metadata, "ownerReferences", None) or _metadata_field(
        metadata, "owner_references", None
    ) or []


def _owner_ref_matches(metadata, owner_uid, owner_kind, owner_name):
    for ref in _metadata_owner_references(metadata):
        if owner_uid and _owner_ref_field(ref, "uid") == owner_uid:
            return True
        if _owner_ref_field(ref, "kind") == owner_kind and _owner_ref_field(ref, "name") == owner_name:
            return True
    return False


def _format_metadata_for_diagnostics(metadata):
    finalizers = _metadata_field(metadata, "finalizers", None) or []
    deletion_timestamp = _metadata_field(metadata, "deletionTimestamp", None) or _metadata_field(
        metadata, "deletion_timestamp", None
    )
    owner_refs = _metadata_owner_references(metadata)
    return (
        f"deletionTimestamp={deletion_timestamp or NONE_PLACEHOLDER}, "
        f"finalizers={finalizers}, ownerReferences={_format_owner_references(owner_refs)}"
    )


def _format_conditions(conditions):
    if not isinstance(conditions, list):
        return "[]"
    if not conditions:
        return "[]"
    formatted = []
    for condition in conditions[:5]:
        if isinstance(condition, dict):
            formatted.append(
                f"{condition.get('type')}={condition.get('status')}"
                f" reason={condition.get('reason', NONE_PLACEHOLDER)}"
            )
        else:
            formatted.append(
                f"{getattr(condition, 'type', None)}={getattr(condition, 'status', None)}"
                f" reason={getattr(condition, 'reason', NONE_PLACEHOLDER)}"
            )
    suffix = f", ... ({len(conditions)} total)" if len(conditions) > 5 else ""
    return "[" + ", ".join(formatted) + suffix + "]"


def _resource_request(storage_requests):
    if not storage_requests:
        return NONE_PLACEHOLDER
    if isinstance(storage_requests, dict):
        return storage_requests.get("storage", NONE_PLACEHOLDER)
    return NONE_PLACEHOLDER


def _emit_core_resource_line(kind, name, metadata, status_phase=None, extra=None):
    detail = _format_metadata_for_diagnostics(metadata)
    if status_phase:
        detail = f"phase={status_phase}, {detail}"
    if extra:
        detail = f"{detail}, {extra}"
    click.echo(f"    {kind}/{name}: {detail}", err=True)


def _find_pods_using_pvc(namespace, pvc_name):
    try:
        pods = _list_items(client.CoreV1Api().list_namespaced_pod(namespace=namespace))
    except ApiException:
        return []
    matches = []
    for pod in pods:
        for volume in pod.spec.volumes or []:
            claim = getattr(volume, "persistent_volume_claim", None)
            if claim and claim.claim_name == pvc_name:
                matches.append(pod)
                break
    return matches


def _emit_events_for_object(namespace, kind, name, limit=5):
    try:
        events = _list_items(client.CoreV1Api().list_namespaced_event(
            namespace=namespace,
            field_selector=f"involvedObject.kind={kind},involvedObject.name={name}",
        ))
    except ApiException:
        return
    if not events:
        return

    def event_timestamp(event):
        candidates = (
            getattr(event, "last_timestamp", None),
            getattr(event, "event_time", None),
            getattr(event, "first_timestamp", None),
            event.metadata.creation_timestamp,
        )
        return next((candidate for candidate in candidates if candidate), None)

    events = sorted(
        events,
        key=event_timestamp,
    )[-limit:]
    click.echo(f"      recent {kind} events:", err=True)
    for event in events:
        timestamp = event_timestamp(event)
        click.echo(
            f"        {timestamp} {event.type or '<unknown>'} {event.reason or NONE_PLACEHOLDER}: "
            f"{event.message or '<no message>'}",
            err=True,
        )


def _emit_pv_diagnostics(pv_name):
    if not pv_name:
        return
    try:
        pv = client.CoreV1Api().read_persistent_volume(pv_name)
    except ApiException as e:
        click.echo(f"      PV/{pv_name}: unavailable ({e.status})", err=True)
        return
    claim_ref = pv.spec.claim_ref
    claim = f"{claim_ref.namespace}/{claim_ref.name}" if claim_ref else NONE_PLACEHOLDER
    _emit_core_resource_line(
        "PV",
        pv.metadata.name,
        pv.metadata,
        getattr(pv.status, "phase", None),
        (
            f"reclaimPolicy={pv.spec.persistent_volume_reclaim_policy}, "
            f"storageClass={pv.spec.storage_class_name or NONE_PLACEHOLDER}, claimRef={claim}"
        ),
    )


def _emit_kafka_storage_diagnostics(namespace, cluster_name):
    pvcs = _find_kafka_pvcs(namespace, [cluster_name])
    if not pvcs:
        click.echo(f"    No Kafka PVCs found for cluster {cluster_name}.", err=True)
        return

    click.echo(f"    Kafka PVC/PV diagnostics for cluster {cluster_name}:", err=True)
    for pvc in pvcs:
        storage = _resource_request((pvc.spec.resources.requests or {}) if pvc.spec.resources else {})
        _emit_core_resource_line(
            "PVC",
            pvc.metadata.name,
            pvc.metadata,
            getattr(pvc.status, "phase", None),
            (
                f"volume={pvc.spec.volume_name or NONE_PLACEHOLDER}, "
                f"storageClass={pvc.spec.storage_class_name or NONE_PLACEHOLDER}, requestedStorage={storage}"
            ),
        )
        pods = _find_pods_using_pvc(namespace, pvc.metadata.name)
        if pods:
            pod_summaries = [
                f"{pod.metadata.name}(phase={getattr(pod.status, 'phase', '<unknown>')})"
                for pod in pods[:5]
            ]
            suffix = f", ... ({len(pods)} total)" if len(pods) > 5 else ""
            click.echo(f"      pods using PVC: {', '.join(pod_summaries)}{suffix}", err=True)
        _emit_pv_diagnostics(pvc.spec.volume_name)
        _emit_events_for_object(namespace, "PersistentVolumeClaim", pvc.metadata.name)


def _emit_owner_reference_blockers(namespace, owner_kind, owner_name, owner_uid):
    """Print objects that still point at a foreground-deleting owner."""
    blockers = []
    for api_gv, plural in _OWNER_REF_DIAGNOSTIC_RESOURCES:
        for item in _list_namespaced_resources(namespace, api_gv, plural):
            metadata = _item_metadata(item)
            if _owner_ref_matches(metadata, owner_uid, owner_kind, owner_name):
                blockers.append((api_gv, plural, item))

    if not blockers:
        click.echo("    No ownerReference dependents found in diagnostic resource groups.", err=True)
        return

    click.echo("    OwnerReference dependents that may block foreground deletion:", err=True)
    for api_gv, plural, item in blockers:
        metadata = _item_metadata(item)
        click.echo(
            f"      {api_gv}/{plural}/{_item_name(item)}: "
            f"{_format_metadata_for_diagnostics(metadata)}, "
            f"phase={_item_phase(item) or NONE_PLACEHOLDER}, "
            f"conditions={_format_conditions(_item_conditions(item))}",
            err=True,
        )


def _emit_strimzi_child_diagnostics(namespace, cluster_name):
    for api_gv, plural, label_key in _OWNED_RESOURCE_CLEANUP.get('kafkaclusters', []):
        items = _find_owned(namespace, api_gv, plural, label_key, cluster_name)
        if not items:
            continue
        click.echo(f"    Related Strimzi {plural}:", err=True)
        for item in items:
            metadata = item.get("metadata", {})
            status = item.get("status", {})
            name = metadata.get("name")
            click.echo(
                f"      {plural}/{name}: {_format_metadata_for_diagnostics(metadata)}, "
                f"conditions={_format_conditions(status.get('conditions', []))}",
                err=True,
            )


def _emit_deletion_diagnostics(namespace, plural, names):
    """Print concise stuck-delete diagnostics for resources that reset could not remove."""
    custom = client.CustomObjectsApi()
    for name in names:
        click.echo(f"  Diagnostics for {resource_display_name(plural, name)}:", err=True)
        try:
            item = custom.get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
                name=name,
            )
        except ApiException as e:
            click.echo(f"    Unable to fetch resource: Kubernetes API returned {e.status}", err=True)
            continue
        metadata = item.get("metadata", {})
        status = item.get("status", {})
        click.echo(f"    metadata: {_format_metadata_for_diagnostics(metadata)}", err=True)
        click.echo(
            f"    status: phase={status.get('phase', NONE_PLACEHOLDER)}, "
            f"conditions={_format_conditions(status.get('conditions', []))}",
            err=True,
        )
        _emit_events_for_object(namespace, item.get("kind", plural), name)
        owner_uid = metadata.get("uid")
        owner_kind = item.get("kind", DISPLAY_NAMES.get(plural, plural))
        _emit_owner_reference_blockers(namespace, owner_kind, name, owner_uid)
        if plural == 'kafkaclusters':
            _emit_strimzi_child_diagnostics(namespace, name)
            _emit_kafka_storage_diagnostics(namespace, name)


def _strip_finalizers(namespace, api_gv, plural, name):
    group, version = _parse_api_gv(api_gv)
    if group:
        try:
            client.CustomObjectsApi().patch_namespaced_custom_object(
                group=group, version=version, namespace=namespace, plural=plural, name=name,
                body={'metadata': {'finalizers': []}})
        except ApiException:
            pass


def _wait_for_owned_deletion(
    namespace,
    api_gv,
    plural,
    label_key,
    cr_name,
    timeout=OWNED_RESOURCE_DELETION_TIMEOUT_SECONDS,
):
    """Poll until owned resources are gone, stripping stuck finalizers as fallback."""
    group, _ = _parse_api_gv(api_gv)
    deadline = time.time() + timeout
    while time.time() < deadline:
        remaining = _find_owned(namespace, api_gv, plural, label_key, cr_name)
        if not remaining:
            return True
        if group:
            for item in remaining:
                if _item_finalizers(item):
                    logger.info(f"Stripping finalizers from {plural}/{_item_name(item)}")
                    _strip_finalizers(namespace, api_gv, plural, _item_name(item))
        time.sleep(2)
    remaining = _find_owned(namespace, api_gv, plural, label_key, cr_name)
    if remaining:
        names = ', '.join(_item_name(i) for i in remaining)
        timeout_message = f"Timed out waiting for {plural} deletion: {names}"
        click.echo(f"  Error: {timeout_message}", err=True)
        logger.error(timeout_message)
        return False
    return True


def _cleanup_owned_resources(namespace, cr_plural, cr_name):
    """Delete owned child resources in declared order before the parent migration CR."""
    for api_gv, plural, label_key in _OWNED_RESOURCE_CLEANUP.get(cr_plural, []):
        for item in _find_owned(namespace, api_gv, plural, label_key, cr_name):
            _delete_owned_resource(namespace, api_gv, plural, _item_name(item))
        if not _wait_for_owned_deletion(namespace, api_gv, plural, label_key, cr_name):
            click.echo(
                f"  Error: refusing to delete {resource_display_name(cr_plural, cr_name)} "
                f"until owned {plural} resources are gone.",
                err=True,
            )
            return False
    return True


def _mark_deleting(namespace, plural, name):
    """Set status.phase to Deleting so VAPs block further updates."""
    try:
        client.CustomObjectsApi().patch_namespaced_custom_object_status(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural=plural, name=name,
            body={'status': {'phase': 'Deleting'}})
    except ApiException:
        pass


def _resource_metadata(namespace, plural, name):
    try:
        item = client.CustomObjectsApi().get_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural=plural,
            name=name,
        )
        metadata = item.get("metadata", {})
        return metadata.get("uid"), metadata.get("creationTimestamp")
    except ApiException:
        return None, None


def _artifact_output_prefix(plural, name, uid=None, created_at=None):
    base = f"{ARTIFACT_OUTPUT_ROOT}/{DISPLAY_NAMES.get(plural, plural)}/{name}/"
    if uid and created_at:
        return f"{base}{created_at}_{uid}/"
    return f"{base}{uid}/" if uid else base


def _cleanup_output_artifacts(plural, name, uid, created_at=None):
    prefix = _artifact_output_prefix(plural, name, uid, created_at)
    # Keep this as a prefix cleanup, not a delete of the single status.outputs
    # S3 key. Argo may create small sidecar objects for an artifact, such as
    # index or metadata files, and deleting only the primary output file can
    # leave those behind. The workaround is to keep all durable workflow output
    # under migration-outputs/<resource-type>/<resource-name>/
    # <resource-creation-timestamp>_<resource-uid>/ and remove that entire
    # prefix when the owning migration resource is reset.
    try:
        deleted = delete_artifact_prefix(prefix)
        if deleted:
            click.echo(f"  Deleted {deleted} output artifact(s) for {resource_display_name(plural, name)}")
    except ArtifactStoreError as e:
        click.echo(f"  Warning: {e}", err=True)


def _delete_and_wait(namespace, plural, name, delete_output_artifacts=True):
    """Delete a single CRD and poll until it is gone."""
    uid, created_at = _resource_metadata(namespace, plural, name)
    _mark_deleting(namespace, plural, name)
    if not _cleanup_owned_resources(namespace, plural, name):
        return name, False
    if delete_output_artifacts:
        _cleanup_output_artifacts(plural, name, uid, created_at)
    else:
        path = artifact_uri(_artifact_output_prefix(plural, name, uid, created_at))
        detail = f" (created {created_at}, uid {uid})" if uid and created_at else ""
        click.echo(f"  Keeping output artifacts for {resource_display_name(plural, name)}{detail}: {path}")
    ok = _delete_crd(namespace, plural, name)
    if ok:
        ok = _wait_until_gone(namespace, plural, [name])
    return name, ok


def _build_child_map(targets):
    """Build reverse dependency map: name -> set of children."""
    target_names = {target[1] for target in targets}
    children = {name: set() for name in target_names}
    for _, name, _, deps in targets:
        for dep in deps:
            if dep in children:
                children[dep].add(name)
    return children


def _delete_targets(targets, namespace, delete_output_artifacts=True):
    """Delete targets in dependency-safe order with concurrency."""
    from concurrent.futures import ThreadPoolExecutor, as_completed

    target_map = {target[1]: target for target in targets}
    pending_children = {name: set(children) for name, children in _build_child_map(targets).items()}
    failed = False

    with ThreadPoolExecutor(max_workers=8) as pool:
        in_flight = {}

        def submit_ready():
            for name, children in pending_children.items():
                if name not in in_flight and not children:
                    plural = target_map[name][0]
                    in_flight[name] = pool.submit(
                        _delete_and_wait,
                        namespace,
                        plural,
                        name,
                        delete_output_artifacts,
                    )

        submit_ready()

        while in_flight:
            done = next(as_completed(in_flight.values()))
            name = next(candidate for candidate, future in in_flight.items() if future is done)
            del in_flight[name]
            del pending_children[name]

            _, ok = done.result()
            if ok:
                click.echo(f"  Deleted {resource_display_name(target_map[name][0], name)}")
            else:
                click.echo(f"  Failed to delete {resource_display_name(target_map[name][0], name)}", err=True)
                failed = True

            for children in pending_children.values():
                children.discard(name)

            submit_ready()

    return not failed


def _dedupe_resources(resources):
    """Return resources in first-seen order, keyed by plural/name."""
    seen = set()
    result = []
    for resource in resources:
        key = (resource[0], resource[1])
        if key in seen:
            continue
        seen.add(key)
        result.append(resource)
    return result


def _emit_or_collect(message, messages=None):
    if messages is None:
        click.echo(message)
    else:
        messages.append(message)


def _show_resource_list(resources):
    """Display migration resources and their dependencies."""
    click.echo("Migration resources:")
    if not resources:
        return
    types = [DISPLAY_NAMES.get(p, p) for p, _, _, _ in resources]
    names = [n for _, n, _, _ in resources]
    max_type = max(len(t) for t in types)
    max_name = max(len(n) for n in names)
    for (plural, name, phase, deps), t in zip(resources, types):
        aligned = f"{t:>{max_type}}.{name:<{max_name}}"
        dep_str = f" (depends on: {', '.join(deps)})" if deps else ""
        click.echo(f"  {aligned}  ({phase}){dep_str}")


def _filter_proxy_targets(targets, include_proxies, messages=None):
    """Block or remove proxy targets unless explicitly included."""
    proxy_targets = [target for target in targets if target[0] == 'captureproxies']
    if include_proxies or not proxy_targets:
        return targets

    names = ', '.join(resource_display_name(t[0], t[1]) for t in proxy_targets)
    _emit_or_collect(f"Proxies are protected by default: {names}", messages)
    _emit_or_collect("Use --include-proxies to delete them.", messages)
    return None


def _resolve_cascade_targets(targets, namespace, cascade, include_proxies, messages=None):
    """Return expanded delete set or None when blocked.

    Protected proxies (captureproxies) are never included in the delete set
    unless --include-proxies is passed. When a target has a protected proxy
    as a dependent, the proxy is silently skipped — it does not block deletion
    of the target.
    """
    filtered = _filter_proxy_targets(targets, include_proxies, messages)
    if filtered is None:
        return None

    target_names = {target[1] for target in filtered}
    all_resources = list_migration_resources(namespace)
    dependent_names = _find_dependents(target_names, all_resources)
    if not dependent_names:
        return filtered

    blocking = [resource for resource in all_resources if resource[1] in dependent_names]

    # Separate proxy dependents from non-proxy dependents
    blocking_proxy = [r for r in blocking if r[0] == 'captureproxies']
    blocking_non_proxy = [r for r in blocking if r[0] != 'captureproxies']

    # Protected proxies don't block — they're just skipped
    if not include_proxies and blocking_proxy:
        proxy_names = ', '.join(r[1] for r in blocking_proxy)
        _emit_or_collect(f"Keeping protected proxies alive: {proxy_names}", messages)

    # Non-proxy dependents still require --cascade
    if blocking_non_proxy and not cascade:
        _emit_or_collect("Cannot delete because dependent resources still exist:", messages)
        for plural, name, _, _ in blocking_non_proxy:
            _emit_or_collect(f"  {DISPLAY_NAMES.get(plural, plural)}.{name}", messages)
        _emit_or_collect("", messages)
        _emit_or_collect("Use --cascade to delete them too.", messages)
        return None

    # Build the final delete set: targets + non-proxy dependents (cascade)
    # Proxy dependents are excluded unless --include-proxies
    expanded_names = target_names | {r[1] for r in blocking_non_proxy}
    if include_proxies:
        expanded_names |= {r[1] for r in blocking_proxy}
    expanded = [resource for resource in all_resources if resource[1] in expanded_names]
    return expanded


def _resolve_named_reset_targets(names, namespace, cascade, include_proxies, messages=None):
    """Resolve user-supplied reset names into the resources reset would delete."""
    targets = []
    for path in names:
        resolved = _resolve_targets(namespace, path)
        if not resolved:
            _emit_or_collect(f"No resources matching '{path}'.", messages)
            continue
        expanded = _resolve_cascade_targets(
            resolved,
            namespace,
            cascade,
            include_proxies,
            messages,
        )
        if expanded is None:
            return None
        targets.extend(expanded)
    return _dedupe_resources(targets)


def _resolve_exact_reset_targets(names, namespace, include_proxies, messages=None):
    """Resolve an already-confirmed resource set without cascade expansion."""
    if not names:
        _emit_or_collect("Exact reset requires at least one resource name.", messages)
        return None
    targets = []
    failed = False
    for path in names:
        if has_glob(path):
            _emit_or_collect(f"Exact reset does not accept glob patterns: {path}", messages)
            failed = True
            continue
        resolved = _resolve_targets(namespace, path)
        if not resolved:
            _emit_or_collect(f"Confirmed resource is no longer present: {path}", messages)
            failed = True
            continue
        targets.extend(resolved)
    if failed:
        return None

    targets = _dedupe_resources(targets)
    filtered = _filter_proxy_targets(targets, include_proxies, messages)
    if filtered is None:
        return None
    target_names = {name for _, name, _, _ in filtered}
    all_resources = list_migration_resources(namespace)
    new_dependents = [
        resource
        for resource in all_resources
        if resource[1] in set(_find_dependents(target_names, all_resources)) - target_names
    ]
    if new_dependents:
        _emit_or_collect(
            "Cannot reset the confirmed plan because additional dependent resources now exist:",
            messages,
        )
        for plural, name, _, _ in new_dependents:
            _emit_or_collect(f"  {resource_display_name(plural, name)}", messages)
        _emit_or_collect("Re-run dry-run and confirm the updated reset plan.", messages)
        return None
    return filtered


def _reset_plan_warnings(namespace, targets, delete_storage):
    warnings = []
    if any(plural == 'snapshotmigrations' for plural, _, _, _ in targets):
        warnings.append(TARGET_INDEX_WARNING)
    kafka_names = [name for plural, name, _, _ in targets if plural == 'kafkaclusters']
    if kafka_names and not delete_storage:
        pvcs = _find_kafka_pvcs(namespace, kafka_names)
        if pvcs and _pvcs_at_risk(namespace, pvcs):
            pvc_names = ', '.join(p.metadata.name for p in pvcs[:5])
            if len(pvcs) > 5:
                pvc_names += f", ... ({len(pvcs)} total)"
            warnings.append(
                f"{len(pvcs)} Kafka PVC(s) will persist after reset: {pvc_names}. "
                "These may cause cluster ID conflicts on redeployment. Use --delete-storage to remove them."
            )
    return warnings


def _reset_plan(targets, namespace, messages=None, delete_storage=False):
    return {
        'targets': [
            {
                'plural': plural,
                'type': DISPLAY_NAMES.get(plural, plural),
                'name': name,
                'path': resource_display_name(plural, name),
                'phase': phase,
                'dependsOn': deps,
            }
            for plural, name, phase, deps in targets
        ],
        'messages': list(messages or []),
        'warnings': _reset_plan_warnings(namespace, targets, delete_storage),
    }


def _emit_reset_plan(plan, output):
    if output == 'json':
        click.echo(json.dumps(plan, indent=2))
        return
    click.echo("Reset dry-run plan:")
    if plan['targets']:
        for target in plan['targets']:
            dep_str = f" (depends on: {', '.join(target['dependsOn'])})" if target['dependsOn'] else ""
            click.echo(f"  {target['path']} ({target['phase']}){dep_str}")
    else:
        click.echo("  No resources would be deleted.")
    if plan['messages']:
        click.echo()
        click.echo("Messages:")
        for message in plan['messages']:
            click.echo(f"  {message}" if message else "")
    if plan['warnings']:
        click.echo()
        click.echo("Warnings:")
        for warning in plan['warnings']:
            click.echo(f"  {warning}")


def _find_ancestors(target_names, all_resources):
    """Find all resources transitively referenced by dependsOn from target_names."""
    resource_map = {name: deps for _, name, _, deps in all_resources}
    ancestors = set()
    pending = list(target_names)
    while pending:
        name = pending.pop()
        for dep in resource_map.get(name, []):
            if dep not in ancestors:
                ancestors.add(dep)
                pending.append(dep)
    return ancestors


def _prune_ancestors_of_protected_proxies(resources, include_proxies):
    """When proxies are protected, remove only the proxy CRs from the delete set.

    Unlike before, upstream dependencies (topics, kafka clusters) are NOT
    protected — they can be deleted independently. The proxy stays alive
    to continue serving traffic even if its upstream is torn down.
    """
    if include_proxies:
        return resources, set()

    protected_proxy_names = {
        name for plural, name, _, _ in resources if plural == 'captureproxies'
    }
    if not protected_proxy_names:
        return resources, set()

    filtered = [
        resource for resource in resources
        if resource[0] != 'captureproxies'
    ]
    return filtered, protected_proxy_names


def _find_kafka_pvcs(namespace, cluster_names):
    """Find PVCs created by Strimzi for the given kafka cluster names."""
    v1 = client.CoreV1Api()
    pvcs = []
    for name in cluster_names:
        try:
            result = v1.list_namespaced_persistent_volume_claim(
                namespace=namespace,
                label_selector=f"strimzi.io/cluster={name}"
            )
            pvcs.extend(result.items)
        except ApiException:
            pass
    return pvcs


def _delete_pvcs(pvcs):
    """Delete a list of PVCs and their released PVs."""
    v1 = client.CoreV1Api()
    for pvc in pvcs:
        ns = pvc.metadata.namespace
        name = pvc.metadata.name
        # Track the PV before deleting the PVC
        pv_name = pvc.spec.volume_name
        try:
            v1.delete_namespaced_persistent_volume_claim(name, ns)
            click.echo(f"  Deleted PVC {name}")
        except ApiException as e:
            if e.status != 404:
                click.echo(f"  Failed to delete PVC {name}: {e}", err=True)
        # Clean up released PV if reclaim policy is Retain
        if pv_name:
            try:
                pv = v1.read_persistent_volume(pv_name)
                if pv.status.phase == 'Released' or pv.spec.persistent_volume_reclaim_policy == 'Retain':
                    v1.delete_persistent_volume(pv_name)
                    click.echo(f"  Deleted PV {pv_name}")
            except ApiException:
                pass


def _pvcs_at_risk(namespace, pvcs):
    """Check if any PVCs will persist — deleteClaim is false or PV reclaim is Retain."""
    v1 = client.CoreV1Api()
    custom = client.CustomObjectsApi()
    # Check if any nodepool has deleteClaim: false
    try:
        pools = custom.list_namespaced_custom_object(
            group='kafka.strimzi.io', version='v1', namespace=namespace, plural='kafkanodepools'
        ).get('items', [])
        for pool in pools:
            storage = pool.get('spec', {}).get('storage', {})
            if not storage.get('deleteClaim', False):
                return True
    except ApiException:
        pass
    # Check if any PV has Retain reclaim policy
    for pvc in pvcs:
        pv_name = pvc.spec.volume_name
        if pv_name:
            try:
                pv = v1.read_persistent_volume(pv_name)
                if pv.spec.persistent_volume_reclaim_policy == 'Retain':
                    return True
            except ApiException:
                pass
    return False


def _handle_kafka_storage(namespace, kafka_cluster_names, delete_storage):
    """Warn about or delete Kafka PVCs during reset."""
    if not kafka_cluster_names:
        return
    pvcs = _find_kafka_pvcs(namespace, kafka_cluster_names)
    if not pvcs:
        return
    if delete_storage:
        click.echo(f"Deleting {len(pvcs)} Kafka PVC(s)...")
        _delete_pvcs(pvcs)
    elif _pvcs_at_risk(namespace, pvcs):
        pvc_names = ', '.join(p.metadata.name for p in pvcs[:5])
        if len(pvcs) > 5:
            pvc_names += f", ... ({len(pvcs)} total)"
        click.echo(
            f"\n⚠️  {len(pvcs)} Kafka PVC(s) will persist after reset: {pvc_names}\n"
            f"   These may cause cluster ID conflicts on redeployment.\n"
            f"   Use --delete-storage to remove them.\n"
        )


def _warn_target_indexes_remain(targets):
    """Remind users that snapshot migration target indexes are not deleted by reset."""
    if not any(plural == 'snapshotmigrations' for plural, _, _, _ in targets):
        return
    click.echo(
        f"\nNote: {TARGET_INDEX_WARNING}"
    )


def _reset_by_resource_name(ctx, path, namespace, cascade, include_proxies, delete_storage,
                            delete_output_artifacts):
    """Handle reset for a specific resource path/pattern."""
    targets = _resolve_targets(namespace, path)
    if not targets:
        click.echo(f"No resources matching '{path}'.")
        return
    targets = _resolve_cascade_targets(targets, namespace, cascade, include_proxies)
    if targets is None:
        ctx.exit(ExitCode.FAILURE.value)
        return
    kafka_names = [name for plural, name, _, _ in targets if plural == 'kafkaclusters']
    _handle_kafka_storage(namespace, kafka_names, delete_storage)
    if not _delete_targets(targets, namespace, delete_output_artifacts):
        ctx.exit(ExitCode.FAILURE.value)
        return
    _warn_target_indexes_remain(targets)


@click.command(name="reset")
@click.argument('names', nargs=-1, metavar='NAME', shell_complete=_get_resource_completions)
@click.option('--list', 'list_resources', is_flag=True, default=False, help='List migration resources and exit')
@click.option('--all', 'reset_all', is_flag=True, default=False, help='Delete all migration resources')
@click.option('--cascade', is_flag=True, default=False, help='Also delete dependent resources')
@click.option('--include-proxies', is_flag=True, default=False,
              help='Also delete capture proxies (they are protected by default)')
@click.option('--dry-run', is_flag=True, default=False, help='Show the resources that would be reset')
@click.option('--exact', is_flag=True, default=False,
              help='Delete exactly the named resources; fail if additional dependents exist')
@click.option('--output', 'output_format', type=click.Choice(['text', 'json']), default='text',
              show_default=True, help='Dry-run output format')
@click.option('--delete-storage', is_flag=True, default=False,
              help='Delete Kafka PVCs and orphaned PVs during reset')
@click.option('--keep-output-artifacts', is_flag=True, default=False,
              help='Keep retained workflow output artifacts and print their S3 prefix')
@click.option('--namespace', default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE')
@click.pass_context
def reset_command(ctx, names, reset_all, list_resources, cascade, include_proxies, dry_run, exact,
                  output_format, delete_storage, keep_output_artifacts, namespace):
    """Reset deletes named workflow resources but does not alter resources
    that are managed outside the migration system, such as the target clusters.
    To fully reset or reverse a previous step, actions will need to be made on
    those other resources.  Those changes can be done through commands like
    `console clusters clear-indices`, etc.

    For example, reset should be coupled with `clear-indices` commands before rerunning
    a historical backfill again.

    With no arguments or --list, lists migration resources and their status.
    With one or more NAMEs or glob patterns, deletes matching resources.
    With --all, deletes all matching migration resources.
    """
    try:
        load_k8s_config()

        if output_format != 'text' and not dry_run:
            click.echo("--output is only supported with --dry-run.", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return
        if exact and reset_all:
            click.echo("--exact cannot be combined with --all.", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return
        if exact and cascade:
            click.echo("--exact cannot be combined with --cascade.", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return

        if list_resources:
            resources = list_migration_resources(namespace)
            if not resources:
                click.echo("No migration resources found.")
            else:
                _show_resource_list(resources)
            return

        if exact:
            messages = []
            targets = _resolve_exact_reset_targets(names, namespace, include_proxies, messages)
            if targets is None:
                for message in messages:
                    click.echo(message)
                ctx.exit(ExitCode.FAILURE.value)
                return
            if dry_run:
                _emit_reset_plan(
                    _reset_plan(targets, namespace, messages, delete_storage),
                    output_format,
                )
                return
            kafka_names = [name for plural, name, _, _ in targets if plural == 'kafkaclusters']
            _handle_kafka_storage(namespace, kafka_names, delete_storage)
            if targets and not _delete_targets(targets, namespace, not keep_output_artifacts):
                ctx.exit(ExitCode.FAILURE.value)
                return
            _warn_target_indexes_remain(targets)
            return

        if names:
            if dry_run:
                messages = []
                targets = _resolve_named_reset_targets(
                    names,
                    namespace,
                    cascade,
                    include_proxies,
                    messages,
                )
                if targets is None:
                    _emit_reset_plan(
                        _reset_plan([], namespace, messages, delete_storage),
                        output_format,
                    )
                    ctx.exit(ExitCode.FAILURE.value)
                    return
                _emit_reset_plan(
                    _reset_plan(targets, namespace, messages, delete_storage),
                    output_format,
                )
                return
            for n in names:
                _reset_by_resource_name(
                    ctx,
                    n,
                    namespace,
                    cascade,
                    include_proxies,
                    delete_storage,
                    not keep_output_artifacts,
                )
            return

        resources = list_migration_resources(namespace)

        if not resources and not reset_all:
            click.echo("No migration resources found.")
            return

        if not reset_all:
            _show_resource_list(resources)
            click.echo()
            click.echo("Use 'workflow reset <name>' to delete a resource.")
            click.echo("Use 'workflow reset --all' to delete everything.")
            click.echo("Use 'workflow submit' to replace the Argo workflow without deleting migration resources.")
            return

        delete_targets, protected_proxy_names = _prune_ancestors_of_protected_proxies(
            resources, include_proxies
        )
        if not include_proxies and protected_proxy_names:
            proxy_names = ", ".join(sorted(f"captureproxy.{n}" for n in protected_proxy_names))
            messages = [
                f"Keeping protected proxies alive: {proxy_names}",
                "Use --include-proxies to delete them.",
            ]
        else:
            messages = []

        if dry_run:
            _emit_reset_plan(
                _reset_plan(delete_targets, namespace, messages, delete_storage),
                output_format,
            )
            return

        for message in messages:
            click.echo(message)

        kafka_names = [name for plural, name, _, _ in delete_targets if plural == 'kafkaclusters']
        _handle_kafka_storage(namespace, kafka_names, delete_storage)

        if delete_targets and not _delete_targets(delete_targets, namespace, not keep_output_artifacts):
            ctx.exit(ExitCode.FAILURE.value)
            return

        _warn_target_indexes_remain(delete_targets)
        click.echo("Done.")

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
