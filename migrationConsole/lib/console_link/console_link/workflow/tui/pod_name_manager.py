import logging
from typing import Dict

logger = logging.getLogger(__name__)


class PodNameManager:
    def __init__(self, app, scraper, workflow_name: str, namespace: str):
        self.cache: Dict[str, str] = {}

        self._app = app
        self._scraper = scraper
        self._workflow_name = workflow_name
        self._namespace = namespace

        self._is_dirty = True
        self._is_refresh_active = False

    def get_name(self, node_id: str) -> str | None:
        return self.cache.get(node_id) if node_id else None

    def observe_node(self, node_id: str) -> None:
        """Called when a new pod node is added to the tree."""
        if node_id not in self.cache:
            self._is_dirty = True

    def clear_cache(self) -> None:
        self.cache.clear()
        self._is_dirty = True

    def trigger_resolve(self, run_id: str, use_cache: bool = True) -> None:
        """Kicks off background fetch if needed."""
        if self._is_refresh_active or self._app.is_exiting or (not self._is_dirty and use_cache):
            return

        self._is_refresh_active = True
        self._is_dirty = False

        self._app.run_worker(
            lambda: self._bulk_resolve_worker(run_id, use_cache),
            thread=True,
            name=f"pods_resolve_{run_id}"
        )

    def _bulk_resolve_worker(self, run_id: str, use_cache: bool) -> None:
        try:
            items = self._scraper.fetch_pods_metadata(self._workflow_name, self._namespace, use_cache)
            new_names = {}
            for pod in items:
                node_id = pod.get('metadata', {}).get('annotations', {}).get('workflows.argoproj.io/node-id')
                if node_id:
                    new_names[node_id] = pod.get('metadata', {}).get('name')

            # Delegate back to main thread for fencing and UI sync
            self._app.call_from_thread(self._finalize_resolve, new_names, run_id)
        finally:
            self._is_refresh_active = False

    def _finalize_resolve(self, new_names: Dict[str, str], worker_run_id: str) -> None:
        if worker_run_id != self._app.current_run_id:
            return

        self.cache.update(new_names)
        self._app.update_pod_status()
        self._app._update_dynamic_bindings()

        if self._is_dirty:
            self.trigger_resolve(worker_run_id, use_cache=True)
