import logging
import unittest
from unittest.mock import patch

import requests

import default_webhook_trigger
from default_webhook_trigger import JenkinsConfig

JENKINS_URL = "https://www.fake-jenkins.com"
JENKINS_JOB_NAME = "unit-test-job"
JENKINS_QUEUE_URL = "queue/item/123"
JENKINS_WORKFLOW_URL = f"{JENKINS_URL}/job/{JENKINS_JOB_NAME}/22/"
JENKINS_WEBHOOK_TOKEN = "fakeToken"


def _make_response(status_code, json_value=None, text=None):
    resp = unittest.mock.Mock()
    resp.status_code = status_code
    resp.ok = 200 <= status_code < 300
    if json_value is not None:
        resp.json.return_value = json_value
    elif text is not None:
        resp.json.side_effect = ValueError("Expecting value: line 1 column 1 (char 0)")
        resp.text = text
    else:
        resp.json.return_value = {}
        resp.text = ""

    if not resp.ok:
        http_error = requests.exceptions.HTTPError(f"{status_code} Client Error", response=resp)
        resp.raise_for_status.side_effect = http_error
    else:
        resp.raise_for_status.return_value = None
    return resp


@patch('default_webhook_trigger.time.sleep')
class DefaultWebhookTriggerTest(unittest.TestCase):

    @patch('requests.Session.post')
    @patch('requests.Session.get')
    def test_successful_webhook_trigger(self, mock_session_get, mock_session_post, _mock_sleep):
        mock_session_post.return_value = _make_response(200, {"jobs": {
            JENKINS_JOB_NAME: {"triggered": True, "url": JENKINS_QUEUE_URL}
        }})
        mock_session_get.side_effect = [
            _make_response(200, {"executable": {"url": JENKINS_WORKFLOW_URL}}),
            _make_response(200, {"building": False}),
            _make_response(200, {"result": "SUCCESS"}),
        ]

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME,
                        '--job_params',
                        'GIT_REPO_URL=https://github.com/opensearch-project/OpenSearch.git,'
                        'GIT_BRANCH=main,STAGE=dev',
                        '--job_timeout_minutes', '10']
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_session_get.call_count, 3)

    @patch('requests.Session.post')
    @patch('requests.Session.get')
    def test_min_successful_webhook_trigger(self, mock_session_get, mock_session_post, _mock_sleep):
        mock_session_post.return_value = _make_response(200, {"jobs": {
            JENKINS_JOB_NAME: {"triggered": True, "url": JENKINS_QUEUE_URL}
        }})
        mock_session_get.side_effect = [
            _make_response(200, {"executable": {"url": JENKINS_WORKFLOW_URL}}),
            _make_response(200, {"building": False}),
            _make_response(200, {"result": "SUCCESS"}),
        ]

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_session_get.call_count, 3)

    @patch('requests.Session.post')
    @patch('requests.Session.get')
    def test_single_job_param_successful_webhook_trigger(self, mock_session_get, mock_session_post, _mock_sleep):
        mock_session_post.return_value = _make_response(200, {"jobs": {
            JENKINS_JOB_NAME: {"triggered": True, "url": JENKINS_QUEUE_URL}
        }})
        mock_session_get.side_effect = [
            _make_response(200, {"executable": {"url": JENKINS_WORKFLOW_URL}}),
            _make_response(200, {"building": False}),
            _make_response(200, {"result": "SUCCESS"}),
        ]

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME,
                        '--job_params', 'STAGE=dev']
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_session_get.call_count, 3)

    @patch('requests.Session.post')
    @patch('requests.Session.get')
    def test_empty_job_param_successful_webhook_trigger(self, mock_session_get, mock_session_post, _mock_sleep):
        mock_session_post.return_value = _make_response(200, {"jobs": {
            JENKINS_JOB_NAME: {"triggered": True, "url": JENKINS_QUEUE_URL}
        }})
        mock_session_get.side_effect = [
            _make_response(200, {"executable": {"url": JENKINS_WORKFLOW_URL}}),
            _make_response(200, {"building": False}),
            _make_response(200, {"result": "SUCCESS"}),
        ]

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME,
                        '--job_params', ""]
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_session_get.call_count, 3)


class WebhookUrlAuthTest(unittest.TestCase):
    """Verify the trigger uses the ?token= query parameter, not a Bearer header.

    The Jenkins Generic Webhook Trigger plugin authenticates only via the URL query
    parameter — sending the token as Authorization: Bearer leaks 403 Forbidden.
    """

    def _config(self) -> JenkinsConfig:
        return JenkinsConfig(
            jenkins_url=JENKINS_URL,
            pipeline_token=JENKINS_WEBHOOK_TOKEN,
            job_name=JENKINS_JOB_NAME,
            job_params={},
            job_timeout_minutes=10,
        )

    def test_webhook_url_includes_token_query_param(self):
        config = self._config()
        self.assertIn(f"token={JENKINS_WEBHOOK_TOKEN}", config.webhook_url)
        self.assertTrue(config.webhook_url.endswith(f"?token={JENKINS_WEBHOOK_TOKEN}"))

    def test_webhook_base_url_does_not_include_token(self):
        config = self._config()
        self.assertNotIn(JENKINS_WEBHOOK_TOKEN, config.webhook_base_url)

    def test_auth_headers_no_longer_include_authorization(self):
        config = self._config()
        self.assertNotIn("Authorization", config.auth_headers)
        self.assertEqual(config.auth_headers, {"Content-Type": "application/json"})

    def test_special_chars_in_token_are_url_encoded(self):
        config = JenkinsConfig(
            jenkins_url=JENKINS_URL,
            pipeline_token="my token+with/special&chars",
            job_name=JENKINS_JOB_NAME,
            job_params={},
            job_timeout_minutes=10,
        )
        self.assertIn("token=my+token%2Bwith%2Fspecial%26chars", config.webhook_url)

    @patch('requests.Session.post')
    def test_post_target_url_carries_token(self, mock_session_post):
        mock_session_post.return_value = _make_response(200, {"jobs": {}})
        config = self._config()
        try:
            default_webhook_trigger.trigger_and_wait_for_job(config)
        except SystemExit:
            pass
        called_url = mock_session_post.call_args[0][0]
        self.assertIn(f"token={JENKINS_WEBHOOK_TOKEN}", called_url)
        # Headers must not carry the token
        called_headers = mock_session_post.call_args.kwargs.get("headers", {})
        self.assertNotIn("Authorization", called_headers)


class TriggerErrorReportingTest(unittest.TestCase):
    """Failed trigger requests must surface the Jenkins response body and not leak the token."""

    @patch('default_webhook_trigger.time.sleep')
    @patch('requests.Session.post')
    def test_403_response_logs_body_and_status(self, mock_session_post, _mock_sleep):
        mock_session_post.return_value = _make_response(
            403,
            text="Did not find any jobs with GenericTrigger configured!\nA token was supplied.\n",
        )

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args), \
                self.assertLogs(level=logging.WARNING) as captured, \
                self.assertRaises(requests.exceptions.HTTPError):
            default_webhook_trigger.main()

        joined = "\n".join(captured.output)
        self.assertIn("status=403", joined)
        self.assertIn("Did not find any jobs", joined)

    @patch('default_webhook_trigger.time.sleep')
    @patch('requests.Session.post')
    def test_failed_request_does_not_leak_token_in_url_log(self, mock_session_post, _mock_sleep):
        mock_session_post.return_value = _make_response(403, text="forbidden")

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args), \
                self.assertLogs(level=logging.WARNING) as captured, \
                self.assertRaises(requests.exceptions.HTTPError):
            default_webhook_trigger.main()

        joined = "\n".join(captured.output)
        self.assertNotIn(JENKINS_WEBHOOK_TOKEN, joined)
        self.assertIn("/generic-webhook-trigger/invoke", joined)

    @patch('default_webhook_trigger.time.sleep')
    @patch('requests.Session.post')
    def test_html_response_does_not_mask_status(self, mock_session_post, _mock_sleep):
        # Earlier behavior buried the real 401 under "Expecting value: line 1 column 1".
        # 401 is not in WEBHOOK_TRANSIENT_STATUSES, so it fails fast (no retry).
        mock_session_post.return_value = _make_response(
            401,
            text="<html><body>401 Unauthorized</body></html>",
        )

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args), \
                self.assertLogs(level=logging.ERROR) as captured, \
                self.assertRaises(requests.exceptions.HTTPError):
            default_webhook_trigger.main()

        joined = "\n".join(captured.output)
        self.assertIn("status=401", joined)
        # The old "Unable to parse trigger request response: Expecting value..." warning is gone.
        self.assertNotIn("Expecting value", joined)


class WebhookRetryTest(unittest.TestCase):
    """Transient 403 from the Generic Webhook Trigger plugin must be retried, not failed."""

    @patch('default_webhook_trigger.time.sleep')
    @patch('requests.Session.get')
    @patch('requests.Session.post')
    def test_403_then_success_recovers(self, mock_session_post, mock_session_get, _mock_sleep):
        # First call: transient 403 (the Jenkins plugin flake).
        # Second call: 200 with a triggered job — the action should NOT fail.
        mock_session_post.side_effect = [
            _make_response(403, text="Did not find any jobs with GenericTrigger configured!"),
            _make_response(200, {"jobs": {
                JENKINS_JOB_NAME: {"triggered": True, "url": JENKINS_QUEUE_URL}
            }}),
        ]
        mock_session_get.side_effect = [
            _make_response(200, {"executable": {"url": JENKINS_WORKFLOW_URL}}),
            _make_response(200, {"building": False}),
            _make_response(200, {"result": "SUCCESS"}),
        ]

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args), \
                self.assertLogs(level=logging.WARNING) as captured:
            default_webhook_trigger.main()

        self.assertEqual(mock_session_post.call_count, 2)
        joined = "\n".join(captured.output)
        # The first failed attempt should be visible in logs so the operator
        # knows we retried, with attempt N/M context.
        self.assertIn("attempt 1/", joined)
        self.assertIn("status=403", joined)

    @patch('default_webhook_trigger.time.sleep')
    @patch('requests.Session.post')
    def test_persistent_403_eventually_fails(self, mock_session_post, _mock_sleep):
        # If 403 never goes away, the action should still fail (with the real error).
        mock_session_post.return_value = _make_response(
            403, text="Did not find any jobs with GenericTrigger configured!"
        )

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args), \
                self.assertLogs(level=logging.WARNING), \
                self.assertRaises(requests.exceptions.HTTPError):
            default_webhook_trigger.main()

        # Default retry count is 5 — every attempt should hit the server.
        self.assertEqual(mock_session_post.call_count, 5)

    @patch('default_webhook_trigger.time.sleep')
    @patch('requests.Session.post')
    def test_401_does_not_retry(self, mock_session_post, _mock_sleep):
        # 401 is real auth failure, not a plugin flake — should fail fast.
        mock_session_post.return_value = _make_response(401, text="Unauthorized")

        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args), \
                self.assertLogs(level=logging.ERROR), \
                self.assertRaises(requests.exceptions.HTTPError):
            default_webhook_trigger.main()

        # Single attempt, no retry.
        self.assertEqual(mock_session_post.call_count, 1)

    @patch('default_webhook_trigger.time.sleep')
    @patch('requests.Session.post')
    def test_connection_error_retries(self, mock_session_post, _mock_sleep):
        # Network blip on attempt 1, success on attempt 2.
        mock_session_post.side_effect = [
            requests.exceptions.ConnectionError("Connection reset by peer"),
            _make_response(200, {"jobs": {}}),
        ]

        config = JenkinsConfig(
            jenkins_url=JENKINS_URL,
            pipeline_token=JENKINS_WEBHOOK_TOKEN,
            job_name=JENKINS_JOB_NAME,
            job_params={},
            job_timeout_minutes=10,
        )
        result = default_webhook_trigger.perform_request(
            config.webhook_url, config.payload, config.auth_headers
        )
        self.assertEqual(result.status_code, 200)
        self.assertEqual(mock_session_post.call_count, 2)


class SummarizeResponseTest(unittest.TestCase):

    def test_json_response(self):
        resp = _make_response(200, {"foo": "bar"})
        self.assertIn("foo", default_webhook_trigger._summarize_response(resp))
        self.assertIn("status=200", default_webhook_trigger._summarize_response(resp))

    def test_non_json_response(self):
        resp = _make_response(500, text="<html>boom</html>")
        summary = default_webhook_trigger._summarize_response(resp)
        self.assertIn("status=500", summary)
        self.assertIn("boom", summary)

    def test_truncates_long_body(self):
        resp = _make_response(500, text="x" * 5000)
        summary = default_webhook_trigger._summarize_response(resp, max_chars=100)
        self.assertIn("truncated", summary)
        self.assertIn("5000 chars total", summary)

    def test_handles_none(self):
        self.assertEqual(default_webhook_trigger._summarize_response(None), "<no response>")


if __name__ == "__main__":
    unittest.main()
