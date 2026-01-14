import os
import tempfile
import subprocess
import logging
from typing import List

logger = logging.getLogger(__name__)


class LogManager:
    def __init__(self, pod_scraper, namespace: str, tail_lines: int = 500):
        self._pod_scraper = pod_scraper
        self._namespace = namespace
        self._tail_lines = tail_lines

    def get_containers(self, pod_name: str) -> List[str]:
        """Get list of user containers (excluding Argo sidecars) from a pod."""
        try:
            pod = self._pod_scraper.read_pod(pod_name, self._namespace)
            main_containers = [c.name for c in pod.spec.containers] if pod.spec.containers else []
            # Filter out Argo executor containers
            return [c for c in main_containers if c not in ('wait', 'init')]
        except Exception as e:
            logger.error(f"Error getting containers for {pod_name}: {e}")
            return []

    def follow_logs(self, app, pod_name: str, container: str) -> None:
        """Follow logs using kubectl logs -f and pipe to less."""
        try:
            cmd = ['kubectl', 'logs', pod_name, '-f', '-c', container, '-n', self._namespace]
            
            # Suspend Textual UI to hand control to kubectl and less
            with app.suspend():
                os.system('clear')
                # Use os.system to run the command in a separate process group
                # This prevents kubectl exit from affecting the parent application
                kubectl_cmd = ' '.join(cmd + ['|', 'less', '-R', '+F'])
                os.system(kubectl_cmd)
                    
        except Exception as e:
            app.notify(f"Follow Error: {e}", severity="error")

    def show_in_pager(self, app, pod_name: str, display_name: str) -> None:
        """Fetch logs, write to temp file, and open system pager."""
        temp_path = None
        try:
            logs = self._get_pod_logs(pod_name)

            with tempfile.NamedTemporaryFile(mode='w', suffix='.log', delete=False) as f:
                f.write(f"=== Logs: {display_name} ===\nPod: {pod_name}\n\n{logs}")
                temp_path = f.name

            # Suspend Textual UI to hand control to the terminal pager (less/more)
            with app.suspend():
                os.system('clear')
                pager = os.environ.get('PAGER', 'less -qqR')  # -qq: no bell; -R: color support
                subprocess.run(pager.split() + [temp_path])

        except Exception as e:
            app.notify(f"Log Error: {e}", severity="error")
        finally:
            if temp_path and os.path.exists(temp_path):
                os.unlink(temp_path)

    def _get_pod_logs(self, pod_name: str) -> str:
        """Internal helper to aggregate logs from all containers in a pod."""
        try:
            pod = self._pod_scraper.read_pod(pod_name, self._namespace)
            # Combine init and standard containers
            containers = [c.name for c in (pod.spec.init_containers or []) + pod.spec.containers]

            output = []
            for c in containers:
                output.append(f"\n--- Container: {c} ---\n")
                try:
                    logs = self._pod_scraper.read_pod_log(
                        pod_name, self._namespace, c, self._tail_lines
                    )
                    output.append(logs or "(No output)")
                except Exception:
                    output.append("(Container not ready or logs unavailable)")

            return "".join(output)
        except Exception as e:
            return f"Error fetching logs for {pod_name}: {e}"
