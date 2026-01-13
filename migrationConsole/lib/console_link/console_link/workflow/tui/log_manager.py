import os
import tempfile
import subprocess
import logging

logger = logging.getLogger(__name__)


class LogManager:
    def __init__(self, pod_scraper, namespace: str, tail_lines: int = 500):
        self.pod_scraper = pod_scraper
        self.namespace = namespace
        self.tail_lines = tail_lines

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
                pager = os.environ.get('PAGER', 'less -R')  # -R for color support
                subprocess.run(pager.split() + [temp_path])

        except Exception as e:
            app.notify(f"Log Error: {e}", severity="error")
        finally:
            if temp_path and os.path.exists(temp_path):
                os.unlink(temp_path)

    def _get_pod_logs(self, pod_name: str) -> str:
        """Internal helper to aggregate logs from all containers in a pod."""
        try:
            pod = self.pod_scraper.read_pod(pod_name, self.namespace)
            # Combine init and standard containers
            containers = [c.name for c in (pod.spec.init_containers or []) + pod.spec.containers]

            output = []
            for c in containers:
                output.append(f"\n--- Container: {c} ---\n")
                try:
                    logs = self.pod_scraper.read_pod_log(
                        pod_name, self.namespace, c, self.tail_lines
                    )
                    output.append(logs or "(No output)")
                except Exception:
                    output.append("(Container not ready or logs unavailable)")

            return "".join(output)
        except Exception as e:
            return f"Error fetching logs for {pod_name}: {e}"
