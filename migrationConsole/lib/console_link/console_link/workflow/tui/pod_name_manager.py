import logging
from typing import Dict

logger = logging.getLogger(__name__)


class PodNameManager:
    def __init__(self, app, scraper, workflow_name: str, namespace: str):
        self.app = app
        self.scraper = scraper
        self.workflow_name = workflow_name
        self.namespace = namespace

        self.cache: Dict[str, str] = {}
        self.is_dirty = True
        self.is_refresh_active = False

    def get_name(self, node_id: str) -> str | None:
        return self.cache.get(node_id) if node_id else None

    def observe_node(self, node_id: str) -> None:
        """Called when a new pod node is added to the tree."""
        if node_id not in self.cache:
            self.is_dirty = True

    def clear_cache(self) -> None:
        self.cache.clear()
        self.is_dirty = True

    def trigger_resolve(self, run_id: str, use_cache: bool = True) -> None:
        """Kicks off background fetch if needed."""
        if self.is_refresh_active or self.app._is_exiting or (not self.is_dirty and use_cache):
            return

        self.is_refresh_active = True
        self.is_dirty = False

        self.app.run_worker(
            lambda: self._bulk_resolve_worker(run_id, use_cache),
            thread=True,
            name=f"pods_resolve_{run_id}"
        )

    def _bulk_resolve_worker(self, run_id: str, use_cache: bool) -> None:
        try:
            items = self.scraper.fetch_pods_metadata(self.workflow_name, self.namespace, use_cache)
            new_names = {}
            for pod in items:
                node_id = pod.get('metadata', {}).get('annotations', {}).get('workflows.argoproj.io/node-id')
                if node_id:
                    new_names[node_id] = pod.get('metadata', {}).get('name')

            # Delegate back to main thread for fencing and UI sync
            self.app.call_from_thread(self._finalize_resolve, new_names, run_id)
        finally:
            self.is_refresh_active = False

    def _finalize_resolve(self, new_names: Dict[str, str], worker_run_id: str) -> None:
        if worker_run_id != self.app._current_run_id:
            return

        self.cache.update(new_names)
        self.app._update_pod_status()
        self.app._update_dynamic_bindings()

        if self.is_dirty:
            self.trigger_resolve(worker_run_id, use_cache=True)