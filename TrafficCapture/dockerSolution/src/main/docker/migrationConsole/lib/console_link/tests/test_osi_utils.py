from pathlib import Path

import botocore.session
import pytest
from botocore.stub import ANY, Stubber
from console_link.models.osi_utils import (InvalidAuthParameters,
                                           OpenSearchIngestionMigrationProps,
                                           construct_pipeline_config,
                                           create_pipeline_from_env,
                                           create_pipeline_from_json,
                                           delete_pipeline,
                                           get_assume_role_session,
                                           start_pipeline,
                                           stop_pipeline,
                                           get_status)
from console_link.models.cluster import AuthMethod
from moto import mock_aws
from tests.utils import create_valid_cluster

PIPELINE_TEMPLATE_PATH = f"{Path(__file__).parents[3]}/osiPipelineTemplate.yaml"
TEST_RESOURCES_PATH = f"{Path(__file__).parents[1]}/tests/resources"

# These values should map to static template values in the resources directory
SOURCE_ENDPOINT = 'https://vpc-test-123.com'
TARGET_ENDPOINT = 'https://vpc-test-456.com'
PIPELINE_ROLE_ARN = 'arn=arn:aws:iam::123456789012:role/OSMigrations-aws-integ-us--osisPipelineRole123'
AWS_REGION = 'us-west-2'
SECRET_NAME = 'unit-test-secret'
INDEX_INCLUSION_RULE_1 = 'index*'
INDEX_INCLUSION_RULE_2 = '.*'
INDEX_INCLUSION_RULE_3 = 'sam[a-z]+'
AWS_TAG_1 = "version=1.0.0"
AWS_TAG_2 = "stage=unittest"
SECURITY_GROUP_1 = "sg-0123456789abcdef0"
SECURITY_GROUP_2 = "sg-abc3456789abcdef0"
VPC_SUBNET_1 = "subnet-123456789abcdef0"
VPC_SUBNET_2 = "subnet-abc456789abcdef0"
PIPELINE_NAME = "unittest-pipeline"
CW_LOG_GROUP_NAME = "/aws/vendedlogs/osi-unittest-default"
SECRET_ARN = "arn:aws:secretsmanager:123456789012:secret/test-secret"


@pytest.fixture
def osi_client_stubber():
    osi_session = botocore.session.get_session().create_client("osis", region_name=AWS_REGION)
    stubber = Stubber(osi_session)
    return stubber


def test_construct_config_sigv4_source_and_sigv4_target():
    generated_config = construct_pipeline_config(
        pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
        source_endpoint=SOURCE_ENDPOINT,
        target_endpoint=TARGET_ENDPOINT,
        source_auth_type=AuthMethod.SIGV4,
        target_auth_type=AuthMethod.SIGV4,
        pipeline_role_arn=PIPELINE_ROLE_ARN,
        aws_region=AWS_REGION)
    with open(f"{TEST_RESOURCES_PATH}/sigv4SourceAndSigv4Target.yaml", "r") as expected_file:
        expected_file_contents = expected_file.read()
    assert generated_config == expected_file_contents


def test_construct_config_basic_auth_source_and_sigv4_target():
    generated_config = construct_pipeline_config(
        pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
        source_endpoint=SOURCE_ENDPOINT,
        target_endpoint=TARGET_ENDPOINT,
        source_auth_type=AuthMethod.BASIC_AUTH,
        source_auth_secret=SECRET_NAME,
        target_auth_type=AuthMethod.SIGV4,
        pipeline_role_arn=PIPELINE_ROLE_ARN,
        aws_region=AWS_REGION)
    with open(f"{TEST_RESOURCES_PATH}/basicAuthSourceAndSigv4Target.yaml", "r") as expected_file:
        expected_file_contents = expected_file.read()
    assert generated_config == expected_file_contents


def test_construct_config_basic_auth_source_with_single_index_inclusion_rules():
    generated_config = construct_pipeline_config(
        pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
        source_endpoint=SOURCE_ENDPOINT,
        target_endpoint=TARGET_ENDPOINT,
        source_auth_type=AuthMethod.BASIC_AUTH,
        source_auth_secret=SECRET_NAME,
        include_index_regex_list=[INDEX_INCLUSION_RULE_1],
        target_auth_type=AuthMethod.SIGV4,
        pipeline_role_arn=PIPELINE_ROLE_ARN,
        aws_region=AWS_REGION)
    with open(f"{TEST_RESOURCES_PATH}/basicAuthSourceWithSingleIndexInclusionRule.yaml", "r") as expected_file:
        expected_file_contents = expected_file.read()
    assert generated_config == expected_file_contents


def test_construct_config_basic_auth_source_with_multiple_index_inclusion_rules():
    generated_config = construct_pipeline_config(
        pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
        source_endpoint=SOURCE_ENDPOINT,
        target_endpoint=TARGET_ENDPOINT,
        source_auth_type=AuthMethod.BASIC_AUTH,
        source_auth_secret=SECRET_NAME,
        include_index_regex_list=[INDEX_INCLUSION_RULE_1, INDEX_INCLUSION_RULE_2, INDEX_INCLUSION_RULE_3],
        target_auth_type=AuthMethod.SIGV4,
        pipeline_role_arn=PIPELINE_ROLE_ARN,
        aws_region=AWS_REGION)
    with open(f"{TEST_RESOURCES_PATH}/basicAuthSourceWithMultipleIndexInclusionRule.yaml", "r") as expected_file:
        expected_file_contents = expected_file.read()
    assert generated_config == expected_file_contents


def test_construct_config_throws_error_if_secret_not_provided_for_basic_auth():
    with pytest.raises(InvalidAuthParameters):
        construct_pipeline_config(
            pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
            source_endpoint=SOURCE_ENDPOINT,
            target_endpoint=TARGET_ENDPOINT,
            source_auth_type=AuthMethod.BASIC_AUTH,
            target_auth_type=AuthMethod.SIGV4,
            pipeline_role_arn=PIPELINE_ROLE_ARN,
            aws_region=AWS_REGION)


def test_construct_config_throws_error_if_pipeline_role_not_provided_for_sigv4():
    with pytest.raises(InvalidAuthParameters):
        construct_pipeline_config(
            pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
            source_endpoint=SOURCE_ENDPOINT,
            target_endpoint=TARGET_ENDPOINT,
            source_auth_type=AuthMethod.SIGV4,
            target_auth_type=AuthMethod.SIGV4,
            aws_region=AWS_REGION)


def test_valid_json_creates_pipeline(osi_client_stubber):
    create_config = {
        "PipelineRoleArn": PIPELINE_ROLE_ARN,
        "PipelineName": PIPELINE_NAME,
        "AwsRegion": AWS_REGION,
        "Tags": [AWS_TAG_1, AWS_TAG_2],
        "IndexRegexSelections": [INDEX_INCLUSION_RULE_1, INDEX_INCLUSION_RULE_2],
        "LogGroupName": CW_LOG_GROUP_NAME,
        "SourceDataProvider": {
            "Uri": "http://source.amazonaws.com:9200",
            "AuthType": AuthMethod.BASIC_AUTH.name,
            "SecretArn": SECRET_ARN
        },
        "TargetDataProvider": {
            "Uri": "https://target.amazonaws.com:443",
            "AuthType": AuthMethod.SIGV4.name
        },
        "VpcSubnetIds": [VPC_SUBNET_1, VPC_SUBNET_2],
        "VpcSecurityGroupIds": [SECURITY_GROUP_1, SECURITY_GROUP_2]
    }
    expected_osi_create_pipeline_body = {
        "MinUnits": ANY,
        "MaxUnits": ANY,
        "PipelineConfigurationBody": ANY,
        "LogPublishingOptions": {
            "CloudWatchLogDestination": {
                "LogGroup": CW_LOG_GROUP_NAME
            },
            "IsLoggingEnabled": True
        },
        "Tags": [
            {"Key": AWS_TAG_1.split("=")[0], "Value": AWS_TAG_1.split("=")[1]},
            {"Key": AWS_TAG_2.split("=")[0], "Value": AWS_TAG_2.split("=")[1]}
        ],
        "PipelineName": PIPELINE_NAME,
        "VpcOptions": {
            "SecurityGroupIds": [SECURITY_GROUP_1, SECURITY_GROUP_2],
            "SubnetIds": [VPC_SUBNET_1, VPC_SUBNET_2]
        }

    }
    osi_client_stubber.add_response("create_pipeline", {}, expected_osi_create_pipeline_body)
    osi_client_stubber.activate()

    create_pipeline_from_json(osi_client=osi_client_stubber.client,
                              input_json=create_config,
                              pipeline_template_path=PIPELINE_TEMPLATE_PATH)

    osi_client_stubber.assert_no_pending_responses()


def test_valid_env_creates_pipeline(osi_client_stubber):
    osi_props_input = {
        "pipeline_role_arn": PIPELINE_ROLE_ARN,
        "vpc_subnet_ids": [
            VPC_SUBNET_1,
            VPC_SUBNET_2
        ],
        "security_group_ids": [
            SECURITY_GROUP_1,
            SECURITY_GROUP_2
        ],
        "aws_region": AWS_REGION,
        "pipeline_name": PIPELINE_NAME,
        "index_regex_selection": [
            INDEX_INCLUSION_RULE_1,
            INDEX_INCLUSION_RULE_2
        ],
        "log_group_name": CW_LOG_GROUP_NAME,
        "tags": [
            AWS_TAG_1,
            AWS_TAG_2
        ]
    }
    mock_cluster = create_valid_cluster(endpoint=SOURCE_ENDPOINT,
                                        auth_type=AuthMethod.SIGV4)
    expected_osi_create_pipeline_body = {
        "MinUnits": ANY,
        "MaxUnits": ANY,
        "PipelineConfigurationBody": ANY,
        "LogPublishingOptions": {
            "CloudWatchLogDestination": {
                "LogGroup": CW_LOG_GROUP_NAME
            },
            "IsLoggingEnabled": True
        },
        "Tags": [
            {"Key": AWS_TAG_1.split("=")[0], "Value": AWS_TAG_1.split("=")[1]},
            {"Key": AWS_TAG_2.split("=")[0], "Value": AWS_TAG_2.split("=")[1]}
        ],
        "PipelineName": PIPELINE_NAME,
        "VpcOptions": {
            "SecurityGroupIds": [SECURITY_GROUP_1, SECURITY_GROUP_2],
            "SubnetIds": [VPC_SUBNET_1, VPC_SUBNET_2]
        }

    }
    osi_client_stubber.add_response("create_pipeline", {}, expected_osi_create_pipeline_body)
    osi_client_stubber.activate()

    create_pipeline_from_env(osi_client=osi_client_stubber.client,
                             osi_props=OpenSearchIngestionMigrationProps(config=osi_props_input),
                             source_cluster=mock_cluster,
                             target_cluster=mock_cluster,
                             pipeline_template_path=PIPELINE_TEMPLATE_PATH,
                             print_config_only=False)

    osi_client_stubber.assert_no_pending_responses()


def test_valid_start_pipeline(osi_client_stubber):
    expected_request_body = {'PipelineName': f'{PIPELINE_NAME}'}
    osi_client_stubber.add_response("start_pipeline", {}, expected_request_body)
    osi_client_stubber.activate()

    start_pipeline(osi_client=osi_client_stubber.client, pipeline_name=PIPELINE_NAME)

    osi_client_stubber.assert_no_pending_responses()


def test_valid_stop_pipeline(osi_client_stubber):
    expected_request_body = {'PipelineName': f'{PIPELINE_NAME}'}
    osi_client_stubber.add_response("stop_pipeline", {}, expected_request_body)
    osi_client_stubber.activate()

    stop_pipeline(osi_client=osi_client_stubber.client, pipeline_name=PIPELINE_NAME)

    osi_client_stubber.assert_no_pending_responses()


def test_valid_delete_pipeline(osi_client_stubber):
    expected_request_body = {'PipelineName': f'{PIPELINE_NAME}'}
    osi_client_stubber.add_response("delete_pipeline", {}, expected_request_body)
    osi_client_stubber.activate()

    delete_pipeline(osi_client=osi_client_stubber.client, pipeline_name=PIPELINE_NAME)

    osi_client_stubber.assert_no_pending_responses()


def test_valid_get_status_pipeline(osi_client_stubber):
    expected_request_body = {'PipelineName': f'{PIPELINE_NAME}'}
    response_status = 'UPDATING'
    response_status_reason = {'Description': 'Pipeline is being updated'}
    service_response_body = {
        'Pipeline':
            {
                'PipelineName': PIPELINE_NAME,
                'Status': response_status,
                'StatusReason': response_status_reason
            }
    }
    osi_client_stubber.add_response("get_pipeline", service_response_body, expected_request_body)
    osi_client_stubber.activate()

    get_status(osi_client=osi_client_stubber.client, pipeline_name=PIPELINE_NAME)

    osi_client_stubber.assert_no_pending_responses()


@mock_aws
def test_valid_get_assume_role_session():
    session_name = 'unittest-session'

    session = get_assume_role_session(PIPELINE_ROLE_ARN, session_name)

    # Validate the session
    credentials = session.get_credentials()
    assert credentials is not None
    assert isinstance(credentials.access_key, str)
    assert isinstance(credentials.secret_key, str)
    assert isinstance(credentials.token, str)
