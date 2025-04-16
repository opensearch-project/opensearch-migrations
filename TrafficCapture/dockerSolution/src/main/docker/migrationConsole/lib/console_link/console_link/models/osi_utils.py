#!/usr/bin/env python3
from console_link.models.cluster import AuthMethod, Cluster
from pathlib import Path
from typing import List, Dict, Optional
from urllib.parse import urlparse
import boto3
import logging
import re

logger = logging.getLogger(__name__)
DEFAULT_PIPELINE_NAME = "migration-assistant-pipeline"

AWS_SECRET_CONFIG_PLACEHOLDER = "<AWS_SECRET_CONFIG_PLACEHOLDER>"
INDEX_SELECTION_OPTIONS_PLACEHOLDER = "<INDEX_SELECTION_OPTIONS_PLACEHOLDER>"
SOURCE_ENDPOINT_PLACEHOLDER = "<SOURCE_CLUSTER_ENDPOINT_PLACEHOLDER>"
TARGET_ENDPOINT_PLACEHOLDER = "<TARGET_CLUSTER_ENDPOINT_PLACEHOLDER>"
SOURCE_AUTH_OPTIONS_PLACEHOLDER = "<SOURCE_AUTH_OPTIONS_PLACEHOLDER>"
TARGET_AUTH_OPTIONS_PLACEHOLDER = "<TARGET_AUTH_OPTIONS_PLACEHOLDER>"
SOURCE_BASIC_AUTH_CONFIG_TEMPLATE = """
      username: "${{aws_secrets:source-secret-config:username}}"
      password: "${{aws_secrets:source-secret-config:password}}"
"""
SOURCE_DEFAULT_INDEX_TEMPLATE = """
        exclude:
          - index_name_regex: \.*
"""  # noqa: W605 - invalid escape sequence -- this is a required regex pattern for OSI template


class InvalidAuthParameters(Exception):
    pass


class OpenSearchIngestionMigrationProps:
    """
    OpenSearch Ingestion pipeline specific props.
    """

    pipeline_role_arn: str = ""
    aws_region: str = ""
    vpc_subnet_ids: List[str]
    security_group_ids: List[str]
    pipeline_name: Optional[str] = None
    index_regex_selection: Optional[List[str]] = None
    log_group_name: Optional[str] = None
    tags: Optional[List[Dict[str, str]]] = None

    def __init__(self, config: Dict) -> None:
        self.pipeline_role_arn = config.get('pipeline_role_arn')
        self.pipeline_name = config.get('pipeline_name')
        self.vpc_subnet_ids = config.get('vpc_subnet_ids')
        self.security_group_ids = config.get('security_group_ids')
        self.index_regex_selection = config.get('index_regex_selection')
        self.log_group_name = config.get('log_group_name')
        self.aws_region = config.get('aws_region')

        tags = config.get('tags')
        if tags:
            self.tags = convert_str_tags_to_dict(tags)


def get_assume_role_session(role_arn, session_name) -> boto3.Session:
    sts_client = boto3.client('sts')
    response = sts_client.assume_role(RoleArn=role_arn, RoleSessionName=session_name)

    # Create a new session with the assumed role's credentials
    credentials = response['Credentials']
    assumed_session = boto3.Session(
        aws_access_key_id=credentials['AccessKeyId'],
        aws_secret_access_key=credentials['SecretAccessKey'],
        aws_session_token=credentials['SessionToken']
    )
    return assumed_session


# Allowing ability to remove port, as port is currently not supported by OpenSearch Ingestion
def sanitize_endpoint(endpoint: str, remove_port: bool):
    url = urlparse(endpoint)
    scheme = url.scheme
    hostname = url.hostname
    port = url.port
    if scheme is None or hostname is None:
        raise RuntimeError(f"Provided endpoint does not have proper endpoint formatting: {endpoint}")

    if port is not None and not remove_port:
        return f"{scheme}://{hostname}:{port}"

    return f"{scheme}://{hostname}"


def validate_index_regex_list(regex_list):
    if regex_list is not None:
        for regex_string in regex_list:
            try:
                re.compile(regex_string)
            except Exception as e:
                logger.error(f"Unable to compile provided index inclusion regex string: {regex_string}")
                raise e


def convert_str_tags_to_dict(tag_list: List[str]) -> List[Dict[str, str]]:
    tag_dict_list = []
    for pair in tag_list:
        key, value = pair.split("=")
        tag_dict_list.append({'Key': key, 'Value': value})
    return tag_dict_list


def generate_source_secret_config(source_auth_secret, pipeline_role_arn, aws_region):
    return f"""
pipeline_configurations:
  aws:
    secrets:
      source-secret-config:
        secret_id: {source_auth_secret}
        region: {aws_region}
        sts_role_arn: {pipeline_role_arn}
""".strip()


def generate_source_sigv4_auth_config(pipeline_role_arn, aws_region):
    return f"""
      aws:
        region: {aws_region}
        sts_role_arn: {pipeline_role_arn}
""".strip()


def generate_target_sigv4_auth_config(pipeline_role_arn, aws_region):
    return f"""
        aws:
          region: {aws_region}
          sts_role_arn: {pipeline_role_arn}
""".strip()


def generate_source_index_config(include_index_regex_list=None):
    if include_index_regex_list is not None:
        include_template_str = "include:\n"
        for regex_str in include_index_regex_list:
            include_template_str = include_template_str + f"          - index_name_regex: {regex_str}\n"
        # Return template after removing last new line character
        return include_template_str[:-1]
    else:
        # Return template after removing any leading or trailing new line
        return SOURCE_DEFAULT_INDEX_TEMPLATE.strip()


def validate_pipeline_config_arguments(source_auth_type: AuthMethod, target_auth_type: AuthMethod,
                                       source_auth_secret=None, pipeline_role_arn=None, include_index_regex_list=None,
                                       aws_region=None):
    # Validation of auth options provided
    if aws_region is None and (source_auth_type == AuthMethod.SIGV4 or target_auth_type == AuthMethod.SIGV4):
        raise InvalidAuthParameters('AWS region must be provided for a source or target auth type of SIGV4')

    if pipeline_role_arn is None and source_auth_type == AuthMethod.SIGV4:
        raise InvalidAuthParameters('Source pipeline role ARN must be provided for an auth type of SIGV4')

    if pipeline_role_arn is None and target_auth_type == AuthMethod.SIGV4:
        raise InvalidAuthParameters('Target pipeline role ARN must be provided for an auth type of SIGV4')

    if (source_auth_secret is None or pipeline_role_arn is None) and source_auth_type == AuthMethod.BASIC_AUTH:
        raise InvalidAuthParameters('Source auth secret and pipeline role ARN to access secret, must be provided '
                                    'for an auth type of BASIC_AUTH')
    validate_index_regex_list(include_index_regex_list)


def construct_pipeline_config(pipeline_config_file_path: str, source_endpoint: str, target_endpoint: str,
                              source_auth_type: AuthMethod, target_auth_type: AuthMethod, source_auth_secret=None,
                              pipeline_role_arn=None, include_index_regex_list=None, aws_region=None):
    validate_pipeline_config_arguments(source_auth_type=source_auth_type, target_auth_type=target_auth_type,
                                       source_auth_secret=source_auth_secret,
                                       pipeline_role_arn=pipeline_role_arn,
                                       include_index_regex_list=include_index_regex_list,
                                       aws_region=aws_region)
    pipeline_config = Path(pipeline_config_file_path).read_text()

    # Fill in index selection config
    index_config = generate_source_index_config(include_index_regex_list)
    pipeline_config = pipeline_config.replace(INDEX_SELECTION_OPTIONS_PLACEHOLDER, index_config)

    # Fill in OSI pipeline template file from provided options
    if source_auth_type == AuthMethod.BASIC_AUTH:
        secret_config = generate_source_secret_config(source_auth_secret, pipeline_role_arn, aws_region)
        pipeline_config = pipeline_config.replace(AWS_SECRET_CONFIG_PLACEHOLDER, secret_config)
        pipeline_config = pipeline_config.replace(SOURCE_AUTH_OPTIONS_PLACEHOLDER,
                                                  SOURCE_BASIC_AUTH_CONFIG_TEMPLATE.strip())
    else:
        pipeline_config = pipeline_config.replace(AWS_SECRET_CONFIG_PLACEHOLDER, "")

    if source_auth_type == AuthMethod.SIGV4:
        aws_source_config = generate_source_sigv4_auth_config(pipeline_role_arn, aws_region)
        pipeline_config = pipeline_config.replace(SOURCE_AUTH_OPTIONS_PLACEHOLDER, aws_source_config)

    if target_auth_type == AuthMethod.SIGV4:
        aws_target_config = generate_target_sigv4_auth_config(pipeline_role_arn, aws_region)
        pipeline_config = pipeline_config.replace(TARGET_AUTH_OPTIONS_PLACEHOLDER, aws_target_config)

    pipeline_config = pipeline_config.replace(SOURCE_ENDPOINT_PLACEHOLDER, source_endpoint)
    pipeline_config = pipeline_config.replace(TARGET_ENDPOINT_PLACEHOLDER, target_endpoint)
    return pipeline_config


# TODO: Reconcile status with internal status (https://opensearch.atlassian.net/browse/MIGRATIONS-1958)
def get_status(osi_client, pipeline_name: str):
    name = pipeline_name if pipeline_name is not None else DEFAULT_PIPELINE_NAME
    logger.info(f"Getting status of pipeline: {name}")
    get_pipeline_response = osi_client.get_pipeline(
        PipelineName=name
    )

    return {
        'status': get_pipeline_response['Pipeline']['Status'],
        'statusMessage': get_pipeline_response['Pipeline']['StatusReason']['Description']
    }


def start_pipeline(osi_client, pipeline_name: str):
    name = pipeline_name if pipeline_name is not None else DEFAULT_PIPELINE_NAME
    logger.info(f"Starting pipeline: {name}")
    osi_client.start_pipeline(
        PipelineName=name
    )


def stop_pipeline(osi_client, pipeline_name: str):
    name = pipeline_name if pipeline_name is not None else DEFAULT_PIPELINE_NAME
    logger.info(f"Stopping pipeline: {name}")
    osi_client.stop_pipeline(
        PipelineName=name
    )


def delete_pipeline(osi_client, pipeline_name: str):
    name = pipeline_name if pipeline_name is not None else DEFAULT_PIPELINE_NAME
    logger.info(f"Deleting pipeline: {name}")
    osi_client.delete_pipeline(
        PipelineName=name
    )


def create_pipeline(osi_client, pipeline_name: str, pipeline_config: str, subnet_ids: List[str],
                    security_group_ids: List[str], cw_log_group_name: str, tags: List[Dict[str, str]]):
    logger.info(f"Creating pipeline: {pipeline_name}")
    pipe_name = pipeline_name if pipeline_name else DEFAULT_PIPELINE_NAME
    pipe_tags = tags if tags else []
    cw_log_options = {
        'IsLoggingEnabled': False
    }
    # Currently limited that CW log groups must already be created and follow naming pattern
    # /aws/vendedlogs/<name>
    if cw_log_group_name is not None:
        cw_log_options = {
            'IsLoggingEnabled': True,
            'CloudWatchLogDestination': {
                'LogGroup': cw_log_group_name
            }
        }

    osi_client.create_pipeline(
        PipelineName=pipe_name,
        MinUnits=2,
        MaxUnits=4,
        PipelineConfigurationBody=pipeline_config,
        LogPublishingOptions=cw_log_options,
        VpcOptions={
            'SubnetIds': subnet_ids,
            'SecurityGroupIds': security_group_ids
        },
        # Documentation lists this as a requirement but does not seem to be the case from testing:
        # https://boto3.amazonaws.com/v1/documentation/api/latest/reference/services/osis/client/create_pipeline.html
        # BufferOptions={
        #    'PersistentBufferEnabled': False
        # },
        Tags=pipe_tags
    )


def create_pipeline_from_env(osi_client,
                             osi_props: OpenSearchIngestionMigrationProps,
                             source_cluster: Cluster,
                             target_cluster: Cluster,
                             pipeline_template_path: str,
                             print_config_only: bool):
    source_endpoint_clean = sanitize_endpoint(endpoint=source_cluster.endpoint, remove_port=False)
    # Target endpoints for OSI are not currently allowed a port
    target_endpoint_clean = sanitize_endpoint(target_cluster.endpoint, True)
    source_auth_secret = None
    if source_cluster.auth_details and "password_from_secret_arn" in source_cluster.auth_details:
        source_auth_secret = source_cluster.auth_details["password_from_secret_arn"]
    pipeline_config_string = construct_pipeline_config(pipeline_config_file_path=pipeline_template_path,
                                                       source_endpoint=source_endpoint_clean,
                                                       source_auth_type=source_cluster.auth_type,
                                                       source_auth_secret=source_auth_secret,
                                                       target_endpoint=target_endpoint_clean,
                                                       target_auth_type=target_cluster.auth_type,
                                                       pipeline_role_arn=osi_props.pipeline_role_arn,
                                                       include_index_regex_list=osi_props.index_regex_selection,
                                                       aws_region=osi_props.aws_region)
    if print_config_only:
        print(pipeline_config_string)
        exit(0)

    create_pipeline(osi_client=osi_client,
                    pipeline_name=osi_props.pipeline_name,
                    pipeline_config=pipeline_config_string,
                    subnet_ids=osi_props.vpc_subnet_ids,
                    security_group_ids=osi_props.security_group_ids,
                    cw_log_group_name=osi_props.log_group_name,
                    tags=osi_props.tags)


def create_pipeline_from_json(osi_client, input_json: Dict, pipeline_template_path: str):
    source_provider = input_json.get('SourceDataProvider')
    remove_source_port = False
    source_auth_type = AuthMethod[source_provider.get('AuthType')]
    if source_auth_type == AuthMethod.SIGV4:
        remove_source_port = True
    # Ports are not currently allowed for OSI SIGV4 sources or sinks
    source_endpoint_clean = sanitize_endpoint(
        endpoint=source_provider.get('Uri'),
        remove_port=remove_source_port)
    source_auth_secret = source_provider.get('SecretArn')

    target_provider = input_json.get('TargetDataProvider')
    # Target endpoints for OSI are not currently allowed a port
    target_endpoint_clean = sanitize_endpoint(target_provider.get('Uri'), True)
    target_auth_type = AuthMethod[target_provider.get('AuthType')]

    pipeline_role_arn = input_json.get('PipelineRoleArn')
    pipeline_name = input_json.get('PipelineName')
    aws_region = input_json.get('AwsRegion')
    log_group_name = input_json.get('LogGroupName')
    include_index_regex_list = input_json.get('IndexRegexSelections')
    tags_value = input_json.get('Tags')
    tags = convert_str_tags_to_dict(tags_value) if tags_value is not None else None
    subnet_ids = input_json.get('VpcSubnetIds')
    security_group_ids = input_json.get('VpcSecurityGroupIds')

    pipeline_config_string = construct_pipeline_config(pipeline_config_file_path=pipeline_template_path,
                                                       source_endpoint=source_endpoint_clean,
                                                       source_auth_type=source_auth_type,
                                                       source_auth_secret=source_auth_secret,
                                                       target_endpoint=target_endpoint_clean,
                                                       target_auth_type=target_auth_type,
                                                       pipeline_role_arn=pipeline_role_arn,
                                                       include_index_regex_list=include_index_regex_list,
                                                       aws_region=aws_region)
    create_pipeline(osi_client=osi_client,
                    pipeline_name=pipeline_name,
                    pipeline_config=pipeline_config_string,
                    subnet_ids=subnet_ids,
                    security_group_ids=security_group_ids,
                    cw_log_group_name=log_group_name,
                    tags=tags)
