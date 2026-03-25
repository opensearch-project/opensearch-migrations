from __future__ import annotations

import argparse
import logging
import signal
import sys
import time
from dataclasses import dataclass
from typing import Optional

import requests
from requests import Response
from requests.adapters import HTTPAdapter
from urllib3.util import Retry

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)


@dataclass
class JenkinsConfig:
    jenkins_url: str
    pipeline_token: str
    job_name: str
    job_params: dict[str, str]
    job_timeout_minutes: int
    jenkins_user: str = ""
    jenkins_api_token: str = ""

    def __post_init__(self) -> None:
        if not self.jenkins_url:
            raise ValueError("jenkins_url is required")
        if not self.pipeline_token:
            raise ValueError("pipeline_token is required")
        if not self.job_name:
            raise ValueError("job_name is required")
        if self.job_timeout_minutes <= 0:
            raise ValueError("job_timeout_minutes must be positive")
        # Strip trailing slash for consistent URL construction
        self.jenkins_url = self.jenkins_url.rstrip("/")

    @property
    def has_api_auth(self) -> bool:
        return bool(self.jenkins_user and self.jenkins_api_token)

    @property
    def webhook_url(self) -> str:
        return f"{self.jenkins_url}/generic-webhook-trigger/invoke"

    @property
    def auth_headers(self) -> dict[str, str]:
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.pipeline_token}",
        }

    @property
    def payload(self) -> dict[str, str]:
        return {**self.job_params, "job_name": self.job_name}

    @property
    def timeout_seconds(self) -> int:
        return self.job_timeout_minutes * 60


@dataclass
class JobResult:
    status: Optional[str]
    workflow_url: Optional[str]

    @property
    def is_success(self) -> bool:
        return self.status == "SUCCESS"


# Global state for signal handler cancellation
_active_config: Optional[JenkinsConfig] = None
_active_build_url: Optional[str] = None


def _cancel_jenkins_build(signum: int, frame: object) -> None:
    """Signal handler that aborts the running Jenkins build on cancellation."""
    sig_name = signal.Signals(signum).name
    logging.warning(f"Received {sig_name}, cancelling Jenkins build...")
    if _active_build_url and _active_config:
        if not _active_config.has_api_auth:
            logging.warning("No jenkins_user/jenkins_api_token configured — cannot cancel Jenkins build")
            sys.exit(1)
        try:
            stop_url = f"{_active_build_url}stop"
            auth = (_active_config.jenkins_user, _active_config.jenkins_api_token)
            response = requests.post(stop_url, auth=auth)
            logging.info(f"Sent stop request to {stop_url}, response: {response.status_code}")
        except Exception as e:
            logging.error(f"Failed to cancel Jenkins build: {e}")
    else:
        logging.warning("No Jenkins build URL available to cancel")
    sys.exit(1)


signal.signal(signal.SIGTERM, _cancel_jenkins_build)
signal.signal(signal.SIGINT, _cancel_jenkins_build)


def perform_request(url: str, payload: dict, headers: dict, retries: int = 3, backoff_factor: int = 1) -> Response:
    retry_strategy = Retry(
        total=retries,
        backoff_factor=backoff_factor,
        status_forcelist=[429, 500, 502, 503, 504],
    )
    adapter = HTTPAdapter(max_retries=retry_strategy)
    session = requests.Session()
    session.mount("https://", adapter)
    return session.post(url, json=payload, headers=headers)


def _find_queue_url(trigger_response: dict, job_name: str = None) -> Optional[str]:
    """Extract the queue URL from the trigger response for the specified job."""
    for name, job_info in trigger_response.get("jobs", {}).items():
        if job_info.get("triggered") is True:
            if job_name is None or job_name in name:
                return job_info.get("url") or None
    return None


def _poll_for_build_url(config: JenkinsConfig, queue_url: str) -> Optional[str]:
    """Query the Jenkins queue API to get the build URL once the job starts."""
    full_queue_url = f"{config.jenkins_url}/{queue_url}api/json"
    queue_response = requests.get(full_queue_url)
    if not queue_response.ok:
        raise RuntimeError(
            f"Unable to retrieve queue entry for request: {full_queue_url}. "
            f"Does queue entry exist and worker have read permission for Jenkins jobs?"
        )
    return queue_response.json().get("executable", {}).get("url") or None


def wait_for_job_completion(config: JenkinsConfig, trigger_response: dict) -> JobResult:
    global _active_build_url
    total_wait_time = 0

    queue_url = _find_queue_url(trigger_response, job_name=config.job_name)
    logging.info(f"Detected jenkins queue_url: {queue_url}")
    if not queue_url:
        raise RuntimeError(f"Unable to determine queue_url for job: {config.job_name}")

    logging.info("Waiting for Jenkins to start workflow")
    time.sleep(15)

    while total_wait_time <= config.timeout_seconds:
        logging.info("Using queue information to find build number in Jenkins if available")
        workflow_url = _poll_for_build_url(config, queue_url)
        logging.info(f"Jenkins workflow_url: {workflow_url}")

        if workflow_url:
            _active_build_url = workflow_url
            logging.info("Waiting for Jenkins to complete the run")

            while total_wait_time <= config.timeout_seconds:
                logging.info(f"Still running, wait for another 30 seconds before checking again,"
                             f" max timeout {config.timeout_seconds}")
                total_wait_time += 30
                logging.info(f"Total time waiting: {total_wait_time}")
                time.sleep(30)

                workflow_response = requests.get(f"{workflow_url}api/json")
                building = workflow_response.json().get("building", False)
                logging.info(f"Workflow currently in progress: {building}")
                if not building:
                    logging.info("Run completed, checking results now...")
                    result = requests.get(f"{workflow_url}api/json").json().get("result")
                    return JobResult(status=result, workflow_url=workflow_url)

            logging.warning(f"Workflow has exceeded its {config.timeout_seconds} second limit")
            return JobResult(status="TIMED_OUT", workflow_url=workflow_url)
        else:
            logging.info("Job not started yet. Waiting for 60 seconds before next attempt.")
            total_wait_time += 60
            logging.info(f"Total time waiting: {total_wait_time}")
            time.sleep(60)

    return JobResult(status=None, workflow_url=None)


def handle_job_monitoring(config: JenkinsConfig, response: Response) -> JobResult:
    logging.info(f"Webhook triggered successfully: {response.status_code}")
    body = response.json()
    logging.info(f"Received response body: {body}")

    triggered_jobs = [
        name for name, info in body.get("jobs", {}).items()
        if info.get("triggered") is True
    ]
    logging.info(f"The following pipelines were started: {triggered_jobs}")

    if not triggered_jobs:
        return JobResult(status="NO_JOBS_TRIGGERED", workflow_url=None)

    if len(triggered_jobs) > 1:
        logging.info(
            f"Multiple pipelines were triggered: {triggered_jobs}. "
            f"Monitoring only the specified job: {config.job_name}"
        )

    return wait_for_job_completion(config=config, trigger_response=body)


def trigger_and_wait_for_job(config: JenkinsConfig) -> None:
    global _active_config
    _active_config = config

    response: Optional[Response] = None
    try:
        response = perform_request(config.webhook_url, config.payload, config.auth_headers)

        if response.ok:
            job_result = handle_job_monitoring(config=config, response=response)
            logging.info(f"Action Result: {job_result.status}. Please check jenkins url for "
                         f"logs: {job_result.workflow_url}")
            if not job_result.is_success:
                sys.exit(1)
        else:
            response.raise_for_status()

    except requests.exceptions.RequestException:
        response_body: object = "{}"
        if response is not None:
            try:
                response_body = response.json()
            except Exception as parse_exception:
                logging.warning(f"Unable to parse trigger request response: {parse_exception}")
        logging.error(f"Failed to trigger webhook for URL: {config.webhook_url} and payload: {config.payload} "
                      f"with response body: {response_body}")
        logging.info("Action Result: FAILURE")
        raise
    except Exception:
        logging.info("Action Result: FAILURE")
        raise


def parse_key_value_pairs(input_str: str) -> dict[str, str]:
    if not input_str:
        return {}
    result: dict[str, str] = {}
    for pair in input_str.split(","):
        key, value = pair.split("=", 1)
        result[key] = value
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Trigger a Jenkins workflow with a generic webhook.")
    parser.add_argument("--pipeline_token", type=str, required=True,
                        help="The token for authenticating with the Jenkins generic webhook")
    parser.add_argument("--jenkins_url", type=str, required=True,
                        help="Jenkins URL including http/https protocol")
    parser.add_argument("--job_name", type=str, required=True,
                        help="The job name to trigger in Jenkins")
    parser.add_argument("--job_params", type=parse_key_value_pairs, required=False, default={},
                        help='Job parameters as comma-separated key=value pairs, e.g. '
                             '"GIT_REPO_URL=https://github.com/example/repo.git,GIT_BRANCH=main"')
    parser.add_argument("--job_timeout_minutes", default=120, type=int,
                        help="Max time (minutes) to wait for completion. Default is 120 minutes")
    parser.add_argument("--jenkins_user", type=str, default="",
                        help="Jenkins username for API auth (required for cancellation)")
    parser.add_argument("--jenkins_api_token", type=str, default="",
                        help="Jenkins API token for API auth (required for cancellation)")

    args = parser.parse_args()
    config = JenkinsConfig(
        jenkins_url=args.jenkins_url,
        pipeline_token=args.pipeline_token,
        job_name=args.job_name,
        job_params=args.job_params,
        job_timeout_minutes=args.job_timeout_minutes,
        jenkins_user=args.jenkins_user,
        jenkins_api_token=args.jenkins_api_token,
    )
    logging.info(f"Using following payload for workflow trigger: {config.payload}")
    trigger_and_wait_for_job(config)


if __name__ == "__main__":
    main()
