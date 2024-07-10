import os
from django.test import Client, SimpleTestCase
from moto import mock_aws
from rest_framework import status
from unittest.mock import patch


VALID_CREATE_PAYLOAD = {
    'PipelineRoleArn': 'arn:aws:iam::123456789012:role/pipeline-access-role',
    'PipelineName': 'test-pipeline',
    'AwsRegion': 'us-east-1',
    'Tags': ['test-tag=123'],
    'IndexRegexSelections': ['index-test-*', 'stored-*'],
    'LogGroupName': '/aws/vendedlogs/osi-logs',
    'SourceDataProvider': {
        'Uri': 'http://vpc-test-source-domain.com:9200',
        'AuthType': 'BASIC_AUTH',
        'SecretArn': 'arn:aws:secretsmanager:123456789012:secret:source-secret'
    },
    'TargetDataProvider': {
        'Uri': 'https://vpc-test-target-domain.com:443',
        'AuthType': 'SIGV4'
    },
    'VpcSubnetIds': ['subnet-123456789', 'subnet-789012345'],
    'VpcSecurityGroupIds': ['sg-123456789', 'sg-789012345']
}
VALID_UPDATE_PAYLOAD = {
    'PipelineName': 'test-pipeline'
}
VALID_ASSUME_ROLE_UPDATE_PAYLOAD = {
    'PipelineName': 'test-pipeline',
    'PipelineManagerAssumeRoleArn': 'arn:aws:iam::123456789012:role/testRole',
}


# Moto has not yet implemented mocking for OSI API actions, using @patch for these calls
class OrchestratorViewsTest(SimpleTestCase):

    def setUp(self):
        self.client = Client()
        os.environ['AWS_DEFAULT_REGION'] = 'us-east-1'

    @patch('console_api.apps.orchestrator.views.create_pipeline_from_json')
    def test_osi_create_migration(self, mock_create_pipeline_from_json):
        mock_create_pipeline_from_json.return_value = None

        response = self.client.post('/orchestrator/osi-create-migration', data=VALID_CREATE_PAYLOAD,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('console_api.apps.orchestrator.views.create_pipeline_from_json')
    def test_osi_create_migration_assume_role(self, mock_create_pipeline_from_json):
        mock_create_pipeline_from_json.return_value = None

        payload = dict(VALID_CREATE_PAYLOAD)
        payload['PipelineManagerAssumeRoleArn'] = 'arn:aws:iam::123456789012:role/testRole'
        response = self.client.post('/orchestrator/osi-create-migration', data=payload,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @patch('console_api.apps.orchestrator.views.create_pipeline_from_json')
    def test_osi_create_migration_fails_for_missing_field(self, mock_create_pipeline_from_json):
        mock_create_pipeline_from_json.return_value = None

        payload = dict(VALID_CREATE_PAYLOAD)
        payload['AwsRegion'] = None
        response = self.client.post('/orchestrator/osi-create-migration', data=payload,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    @patch('console_api.apps.orchestrator.views.start_pipeline')
    def test_osi_start_migration(self, mock_start_pipeline):
        mock_start_pipeline.return_value = None

        response = self.client.post('/orchestrator/osi-start-migration', data=VALID_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('console_api.apps.orchestrator.views.start_pipeline')
    def test_osi_start_migration_assume_role(self, mock_start_pipeline):
        mock_start_pipeline.return_value = None

        response = self.client.post('/orchestrator/osi-start-migration', data=VALID_ASSUME_ROLE_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @patch('console_api.apps.orchestrator.views.stop_pipeline')
    def test_osi_stop_migration(self, mock_stop_pipeline):
        mock_stop_pipeline.return_value = None

        response = self.client.post('/orchestrator/osi-stop-migration', data=VALID_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('console_api.apps.orchestrator.views.stop_pipeline')
    def test_osi_stop_migration_assume_role(self, mock_stop_pipeline):
        mock_stop_pipeline.return_value = None

        response = self.client.post('/orchestrator/osi-stop-migration', data=VALID_ASSUME_ROLE_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @patch('console_api.apps.orchestrator.views.delete_pipeline')
    def test_osi_delete_migration(self, mock_delete_pipeline):
        mock_delete_pipeline.return_value = None

        response = self.client.post('/orchestrator/osi-delete-migration', data=VALID_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('console_api.apps.orchestrator.views.delete_pipeline')
    def test_osi_delete_migration_assume_role(self, mock_delete_pipeline):
        mock_delete_pipeline.return_value = None

        response = self.client.post('/orchestrator/osi-delete-migration', data=VALID_ASSUME_ROLE_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)
