import unittest
from unittest import mock
from unittest.mock import patch

import default_webhook_trigger

JENKINS_URL="https://www.fake-jenkins.com"
JENKINS_JOB_NAME="unit-test-job"
JENKINS_QUEUE_URL="queue/item/123"
JENKINS_WORKFLOW_URL=f"{JENKINS_URL}/job/{JENKINS_JOB_NAME}/22/"
JENKINS_WEBHOOK_TOKEN="fakeToken"

class DefaultWebhookTriggerTest(unittest.TestCase):

    @patch('requests.Session.post')
    @patch('requests.get')
    def test_successful_webhook_trigger(self, mock_get, mock_session_post):
        # Setup request mocks
        mock_post_job = unittest.mock.Mock()
        mock_post_job.status_code = 200
        mock_post_job.json.return_value = {"jobs": {
            JENKINS_JOB_NAME: {
                "triggered": True,
                "url": JENKINS_QUEUE_URL
            }
        }}
        mock_session_post.return_value = mock_post_job

        mock_get_workflow_url = unittest.mock.Mock()
        mock_get_workflow_url.status_code = 200
        mock_get_workflow_url.json.return_value = {"executable": {"url": JENKINS_WORKFLOW_URL}}

        mock_get_workflow_status = unittest.mock.Mock()
        mock_get_workflow_status.status_code = 200
        mock_get_workflow_status.json.return_value = {"building": False}

        mock_get_workflow_result = unittest.mock.Mock()
        mock_get_workflow_result.status_code = 200
        mock_get_workflow_result.json.return_value = {"result": "SUCCESS"}

        mock_get.side_effect = [mock_get_workflow_url, mock_get_workflow_status, mock_get_workflow_result]

        # Execute webhook trigger
        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME,
                        '--job_params', 'GIT_REPO_URL=https://github.com/opensearch-project/OpenSearch.git,GIT_BRANCH=main,STAGE=dev',
                        '--job_timeout_minutes', '10']
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        # Assertions
        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_get.call_count, 3)

    @patch('requests.Session.post')
    @patch('requests.get')
    def test_min_successful_webhook_trigger(self, mock_get, mock_session_post):
        # Setup request mocks
        mock_post_job = unittest.mock.Mock()
        mock_post_job.status_code = 200
        mock_post_job.json.return_value = {"jobs": {
            JENKINS_JOB_NAME: {
                "triggered": True,
                "url": JENKINS_QUEUE_URL
            }
        }}
        mock_session_post.return_value = mock_post_job

        mock_get_workflow_url = unittest.mock.Mock()
        mock_get_workflow_url.status_code = 200
        mock_get_workflow_url.json.return_value = {"executable": {"url": JENKINS_WORKFLOW_URL}}

        mock_get_workflow_status = unittest.mock.Mock()
        mock_get_workflow_status.status_code = 200
        mock_get_workflow_status.json.return_value = {"building": False}

        mock_get_workflow_result = unittest.mock.Mock()
        mock_get_workflow_result.status_code = 200
        mock_get_workflow_result.json.return_value = {"result": "SUCCESS"}

        mock_get.side_effect = [mock_get_workflow_url, mock_get_workflow_status, mock_get_workflow_result]

        # Execute webhook trigger
        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME]
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        # Assertions
        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_get.call_count, 3)

    @patch('requests.Session.post')
    @patch('requests.get')
    def test_single_job_param_successful_webhook_trigger(self, mock_get, mock_session_post):
        # Setup request mocks
        mock_post_job = unittest.mock.Mock()
        mock_post_job.status_code = 200
        mock_post_job.json.return_value = {"jobs": {
            JENKINS_JOB_NAME: {
                "triggered": True,
                "url": JENKINS_QUEUE_URL
            }
        }}
        mock_session_post.return_value = mock_post_job

        mock_get_workflow_url = unittest.mock.Mock()
        mock_get_workflow_url.status_code = 200
        mock_get_workflow_url.json.return_value = {"executable": {"url": JENKINS_WORKFLOW_URL}}

        mock_get_workflow_status = unittest.mock.Mock()
        mock_get_workflow_status.status_code = 200
        mock_get_workflow_status.json.return_value = {"building": False}

        mock_get_workflow_result = unittest.mock.Mock()
        mock_get_workflow_result.status_code = 200
        mock_get_workflow_result.json.return_value = {"result": "SUCCESS"}

        mock_get.side_effect = [mock_get_workflow_url, mock_get_workflow_status, mock_get_workflow_result]

        # Execute webhook trigger
        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME,
                        '--job_params', 'STAGE=dev']
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        # Assertions
        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_get.call_count, 3)

    @patch('requests.Session.post')
    @patch('requests.get')
    def test_empty_job_param_successful_webhook_trigger(self, mock_get, mock_session_post):
        # Setup request mocks
        mock_post_job = unittest.mock.Mock()
        mock_post_job.status_code = 200
        mock_post_job.json.return_value = {"jobs": {
            JENKINS_JOB_NAME: {
                "triggered": True,
                "url": JENKINS_QUEUE_URL
            }
        }}
        mock_session_post.return_value = mock_post_job

        mock_get_workflow_url = unittest.mock.Mock()
        mock_get_workflow_url.status_code = 200
        mock_get_workflow_url.json.return_value = {"executable": {"url": JENKINS_WORKFLOW_URL}}

        mock_get_workflow_status = unittest.mock.Mock()
        mock_get_workflow_status.status_code = 200
        mock_get_workflow_status.json.return_value = {"building": False}

        mock_get_workflow_result = unittest.mock.Mock()
        mock_get_workflow_result.status_code = 200
        mock_get_workflow_result.json.return_value = {"result": "SUCCESS"}

        mock_get.side_effect = [mock_get_workflow_url, mock_get_workflow_status, mock_get_workflow_result]

        # Execute webhook trigger
        command_args = ['default_webhook_trigger',
                        '--pipeline_token', JENKINS_WEBHOOK_TOKEN,
                        '--jenkins_url', JENKINS_URL,
                        '--job_name', JENKINS_JOB_NAME,
                        '--job_params', ""]
        with unittest.mock.patch('sys.argv', command_args):
            default_webhook_trigger.main()

        # Assertions
        self.assertEqual(mock_session_post.call_count, 1)
        self.assertEqual(mock_get.call_count, 3)
