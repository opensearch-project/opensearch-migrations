import argparse
import logging
import requests
import time

from requests import Response
from requests.adapters import HTTPAdapter
from urllib3.util import Retry

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)


class JobResult:
    def __init__(self, status: str, workflow_url: str):
        self.status = status
        self.workflow_url = workflow_url


def perform_request(jenkins_webhook_url: str, payload: dict, headers: dict, retries=3, backoff_factor=1):
    retry_strategy = Retry(
        total=retries,  # Maximum number of retries
        backoff_factor=backoff_factor,
        status_forcelist=[429, 500, 502, 503, 504],  # HTTP status codes to retry on
    )
    # Create an HTTP adapter with the retry strategy and mount it to session
    adapter = HTTPAdapter(max_retries=retry_strategy)

    session = requests.Session()
    session.mount('https://', adapter)

    # Make a request using the session object
    response = session.post(jenkins_webhook_url, json=payload, headers=headers)
    return response


def wait_for_job_completion(jenkins_url: str, trigger_job_response_body: dict, job_name: str,
                            job_timeout_minutes: int) -> JobResult:
    total_wait_time = 0
    timeout_seconds = job_timeout_minutes * 60
    result_status = None
    workflow_url = None

    # Parse Jenkins request and get the queue URL
    queue_url = trigger_job_response_body.get("jobs", {}).get(job_name, {}).get("url", None)
    logging.info(f"Detected jenkins queue_url: {queue_url}")
    if not queue_url:
        raise RuntimeError(f"Unable to determine queue_url for job: {job_name}")

    logging.info("Waiting for Jenkins to start workflow")
    time.sleep(15)

    while result_status is None and total_wait_time <= timeout_seconds:
        logging.info("Using queue information to find build number in Jenkins if available")

        full_queue_url = f"{jenkins_url}/{queue_url}api/json"
        queue_response = requests.get(full_queue_url)
        if not queue_response.ok:
            raise RuntimeError(f"Unable to retrieve queue entry for request: {full_queue_url}. Does queue entry exist "
                               f"and worker have read permission for Jenkins jobs? ")
        workflow_url = queue_response.json().get("executable", {}).get("url", None)
        logging.info(f"Jenkins workflow_url: {workflow_url}")

        if workflow_url:
            workflow_in_progress = True
            logging.info("Waiting for Jenkins to complete the run")

            while workflow_in_progress and total_wait_time <= timeout_seconds:
                logging.info(f"Still running, wait for another 30 seconds before checking again,"
                             f" max timeout {timeout_seconds}")
                total_wait_time += 30
                logging.info(f"Total time waiting: {total_wait_time}")
                time.sleep(30)

                # Check if the workflow is still running
                workflow_response = requests.get(f"{workflow_url}api/json")
                workflow_in_progress = workflow_response.json().get("building", False)
                logging.info(f"Workflow currently in progress: {workflow_in_progress}")

            if workflow_in_progress:
                logging.warning(f"Workflow has exceeded its {timeout_seconds} second limit")
                result_status = "TIMED_OUT"
            else:
                logging.info("Run completed, checking results now...")
                result_status = requests.get(f"{workflow_url}api/json").json().get("result")
        else:
            logging.info("Job not started yet. Waiting for 60 seconds before next attempt.")
            total_wait_time += 60
            logging.info(f"Total time waiting: {total_wait_time}")
            time.sleep(60)
    return JobResult(status=result_status, workflow_url=workflow_url)


def handle_job_monitoring(response: Response, target_job_name: str, job_timeout_minutes: int,
                          jenkins_url: str) -> JobResult:
    logging.info(f"Webhook triggered successfully: {response.status_code}")
    body = response.json()
    logging.info(f"Received response body: {body}")
    jobs = body.get("jobs", {})
    triggered_jobs = []
    for job_name, job_info in jobs.items():
        if job_info.get("triggered") is True:
            triggered_jobs.append(job_name)
    logging.info(f"The following pipelines were started: {triggered_jobs}")
    if not triggered_jobs:
        return JobResult(status="NO_JOBS_TRIGGERED", workflow_url="None")
    else:
        if len(triggered_jobs) > 1:
            logging.warning(f"This tool currently only supports triggering a single pipeline, but these "
                            f"pipelines were triggered: {triggered_jobs}. Only the specified job will be "
                            f"checked.")
        return wait_for_job_completion(trigger_job_response_body=body, job_name=target_job_name,
                                       job_timeout_minutes=job_timeout_minutes, jenkins_url=jenkins_url)


def trigger_and_wait_for_job(jenkins_url: str, pipeline_token: str, payload: dict, target_job_name: str,
                             job_timeout_minutes: int):
    jenkins_webhook_url = f"{jenkins_url}/generic-webhook-trigger/invoke"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {pipeline_token}"
    }

    response = None
    action_result_name = "Action Result"
    try:
        response = perform_request(jenkins_webhook_url, payload, headers)

        if response.ok:
            job_result = handle_job_monitoring(response=response, target_job_name=target_job_name,
                                               job_timeout_minutes=job_timeout_minutes, jenkins_url=jenkins_url)
            logging.info(f"{action_result_name}: {job_result.status}. Please check jenkins url for "
                         f"logs: {job_result.workflow_url}")
            if job_result.status != "SUCCESS":
                exit(1)
        else:
            response.raise_for_status()

    except requests.exceptions.RequestException as e:
        response_body = "{}" if response is None else response.json()
        logging.error(f"Failed to trigger webhook for URL: {jenkins_webhook_url} and payload: {payload} with "
                      f"response body: {response_body}")
        logging.info(f"{action_result_name}: FAILURE")
        raise e
    except Exception as e:
        logging.info(f"{action_result_name}: FAILURE")
        raise e


def parse_key_value(arg_string):
    key, value = arg_string.split('=')
    return key, value


def main():
    parser = argparse.ArgumentParser(description="Trigger a Jenkins workflow with a generic webhook.")
    parser.add_argument("--pipeline_token", type=str, help="The token for authenticating with the Jenkins webhook.")
    parser.add_argument("--jenkins_url", type=str, help="The Jenkins server URL.")
    parser.add_argument("--job_name", type=str, help="The job name to trigger in Jenkins. This will "
                                                     "automatically be added as a job_param.")
    parser.add_argument('--job_param', action='append', type=parse_key_value, required=False,
                        help="A job parameter to provide to a Jenkins workflow (format: key=value). Can be used "
                             "multiple times")
    parser.add_argument("--job_timeout_minutes", default=60, type=int,
                        help="The amount of time in minutes to wait for job completion")

    args = parser.parse_args()
    payload = dict(args.job_param) if args.job_param else {}
    payload['job_name'] = args.job_name
    logging.info(f"Using following payload for workflow trigger: {payload}")

    trigger_and_wait_for_job(pipeline_token=args.pipeline_token, payload=payload, target_job_name=args.job_name,
                             job_timeout_minutes=args.job_timeout_minutes, jenkins_url=args.jenkins_url)


if __name__ == "__main__":
    main()
