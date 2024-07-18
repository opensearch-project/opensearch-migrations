import os
import botocore
from django.test import Client, SimpleTestCase
from moto import mock_aws
from rest_framework import status
from unittest.mock import patch

PIPELINE_NAME = 'test-pipeline'
VALID_CREATE_PAYLOAD = {
    'PipelineRoleArn': 'arn:aws:iam::123456789012:role/pipeline-access-role',
    'PipelineName': PIPELINE_NAME,
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
    'PipelineName': PIPELINE_NAME
}
VALID_ASSUME_ROLE_UPDATE_PAYLOAD = {
    'PipelineName': PIPELINE_NAME,
    'PipelineManagerAssumeRoleArn': 'arn:aws:iam::123456789012:role/testRole',
}

# Original botocore _make_api_call function
orig = botocore.client.BaseClient._make_api_call


# Moto has not yet implemented mocking for OSI API actions, so must mock these API calls manually here
def mock_make_api_call(self, operation_name, kwarg):
    # For example for the Access Analyzer service
    # As you can see the operation_name has the list_analyzers snake_case form but
    # we are using the ListAnalyzers form.
    # Rationale -> https://github.com/boto/botocore/blob/develop/botocore/client.py#L810:L816
    if (operation_name == 'CreatePipeline' or operation_name == 'DeletePipeline' or
            operation_name == 'StartPipeline' or operation_name == 'StopPipeline'):
        return {'Pipeline': {'PipelineName': PIPELINE_NAME}}
    # If we don't want to patch the API call
    return orig(self, operation_name, kwarg)


class OrchestratorViewsTest(SimpleTestCase):

    def setUp(self):
        self.client = Client()
        os.environ['AWS_DEFAULT_REGION'] = 'us-east-1'

    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_create_migration(self):
        response = self.client.post('/orchestrator/osi-create-migration', data=VALID_CREATE_PAYLOAD,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_create_migration_assume_role(self):
        payload = dict(VALID_CREATE_PAYLOAD)
        payload['PipelineManagerAssumeRoleArn'] = 'arn:aws:iam::123456789012:role/testRole'
        response = self.client.post('/orchestrator/osi-create-migration', data=payload,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_create_migration_fails_for_missing_field(self):
        payload = dict(VALID_CREATE_PAYLOAD)
        payload['AwsRegion'] = None
        response = self.client.post('/orchestrator/osi-create-migration', data=payload,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_create_migration_fails_for_invalid_auth(self):
        payload = dict(VALID_CREATE_PAYLOAD)
        payload['SourceDataProvider'].pop('SecretArn')
        response = self.client.post('/orchestrator/osi-create-migration', data=payload,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)

    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_start_migration(self):
        response = self.client.post('/orchestrator/osi-start-migration', data=VALID_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_start_migration_assume_role(self):
        response = self.client.post('/orchestrator/osi-start-migration', data=VALID_ASSUME_ROLE_UPDATE_PAYLOAD,
                                    content_type='application/json')

        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_stop_migration(self):
        response = self.client.post('/orchestrator/osi-stop-migration', data=VALID_UPDATE_PAYLOAD,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_stop_migration_assume_role(self):
        response = self.client.post('/orchestrator/osi-stop-migration', data=VALID_ASSUME_ROLE_UPDATE_PAYLOAD,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_delete_migration(self):
        response = self.client.post('/orchestrator/osi-delete-migration', data=VALID_UPDATE_PAYLOAD,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)

    @mock_aws
    @patch('botocore.client.BaseClient._make_api_call', new=mock_make_api_call)
    def test_osi_delete_migration_assume_role(self):
        response = self.client.post('/orchestrator/osi-delete-migration', data=VALID_ASSUME_ROLE_UPDATE_PAYLOAD,
                                    content_type='application/json')
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertIn('Timestamp', response.data)
