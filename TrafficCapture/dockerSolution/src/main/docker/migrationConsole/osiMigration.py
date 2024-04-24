#!/usr/bin/env python3

from pathlib import Path
import argparse
import logging
import coloredlogs
import boto3
import os

logger = logging.getLogger(__name__)
DEFAULT_PIPELINE_NAME = "migration-assistant-pipeline"
DEFAULT_PIPELINE_CONFIG_PATH = "./osiPipelineTemplate.yaml"
SOURCE_ENDPOINT_PLACEHOLDER = "<SOURCE_CLUSTER_ENDPOINT_PLACEHOLDER>"
TARGET_ENDPOINT_PLACEHOLDER = "<TARGET_CLUSTER_ENDPOINT_PLACEHOLDER>"
AWS_REGION_PLACEHOLDER = "<AWS_REGION_PLACEHOLDER>"
SOURCE_STS_ROLE_ARN_PLACEHOLDER = "<SOURCE_STS_ROLE_ARN_PLACEHOLDER>"
TARGET_STS_ROLE_ARN_PLACEHOLDER = "<TARGET_STS_ROLE_ARN_PLACEHOLDER>"


def _initialize_logging():
    root_logger = logging.getLogger()
    root_logger.handlers = []  # Make sure we're starting with a clean slate
    root_logger.setLevel(logging.DEBUG)

    # Send INFO+ level logs to stdout, and enable colorized messages
    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_formatter = coloredlogs.ColoredFormatter('%(asctime)s [%(levelname)s] %(message)s')
    console_handler.setFormatter(console_formatter)
    root_logger.addHandler(console_handler)


def parse_args():
    parser = argparse.ArgumentParser(description="Script to control migration OSI pipeline operations.")
    subparsers = parser.add_subparsers(dest="subcommand")
    parser_start_command = subparsers.add_parser('start-pipeline', help='Operation to start a given OSI pipeline')
    parser_start_command.add_argument('--name', type=str, help='The name of the OSI pipeline',
                                      default=DEFAULT_PIPELINE_NAME)
    parser_stop_command = subparsers.add_parser('stop-pipeline', help='Operation to stop a given OSI pipeline')
    parser_stop_command.add_argument('--name', type=str, help='The name of the OSI pipeline',
                                     default=DEFAULT_PIPELINE_NAME)
    parser_create_pipeline_command = subparsers.add_parser('create-pipeline',
                                                           help='Operation to create an OSI pipeline')
    parser_create_pipeline_command.add_argument('--name', type=str, help='The name of the OSI pipeline',
                                                default=DEFAULT_PIPELINE_NAME)
    return parser.parse_args()


def get_private_subnets(vpc_id):
    ec2 = boto3.client('ec2')

    response = ec2.describe_subnets(Filters=[{'Name': 'vpc-id', 'Values': [vpc_id]}])
    subnets = response['Subnets']

    private_subnets = []

    for subnet in subnets:
        response = ec2.describe_route_tables(Filters=[{'Name': 'association.subnet-id', 'Values': [subnet['SubnetId']]}])
        for route_table in response['RouteTables']:
            for route in route_table['Routes']:
                if route.get('NatGatewayId'):
                    private_subnets.append(subnet['SubnetId'])
                    break  # No need to check other route tables for this subnet
            else:
                continue  # Only executed if the inner loop did NOT break
            break  # No need to check other route tables for this subnet

    return private_subnets


def construct_pipeline_config(pipeline_config_file_path: str):
    region: str = os.environ.get("AWS_REGION")
    pipeline_config = Path(pipeline_config_file_path).read_text()
    pipeline_config = pipeline_config.replace(SOURCE_ENDPOINT_PLACEHOLDER, "http://opense-clust-qdZr6y0XiExC-3a3b2a6d93316d7a.elb.us-west-2.amazonaws.com:9200")
    #pipeline_config = pipeline_config.replace(SOURCE_ENDPOINT_PLACEHOLDER, "https://vpc-test-es-source-osi-qd5nmp6ol7anmvom5bnpta6mfe.us-west-2.es.amazonaws.com")
    pipeline_config = pipeline_config.replace(TARGET_ENDPOINT_PLACEHOLDER, "https://vpc-opensearch-cluster-aws-integ-k7fmj5yryuxbxovs3laua64k5y.us-west-2.es.amazonaws.com")
    pipeline_config = pipeline_config.replace(AWS_REGION_PLACEHOLDER, region)
    pipeline_config = pipeline_config.replace(SOURCE_STS_ROLE_ARN_PLACEHOLDER, "arn:aws:iam::594430375790:role/OpenSearchIngestionPipelineRole")
    pipeline_config = pipeline_config.replace(TARGET_STS_ROLE_ARN_PLACEHOLDER, "arn:aws:iam::594430375790:role/OpenSearchIngestionPipelineRole")
    return pipeline_config


def start_pipeline(osi_client, pipeline_name: str):
    logger.info(f"Starting pipeline: {pipeline_name}")
    osi_client.start_pipeline(
        PipelineName=pipeline_name
    )


def stop_pipeline(osi_client, pipeline_name: str):
    logger.info(f"Stopping pipeline: {pipeline_name}")
    osi_client.stop_pipeline(
        PipelineName=pipeline_name
    )


def create_pipeline(osi_client, pipeline_name: str, pipeline_config: str):
    logger.info(f"Creating pipeline: {pipeline_name}")
    vpc_id_key = "/migration/aws-integ/default/vpcId"
    target_sg_id_key = "/migration/aws-integ/default/osAccessSecurityGroupId"
    source_sg_id_key = "/migration/aws-integ/default/serviceConnectSecurityGroupId"
    ssm_client = boto3.client('ssm')
    param_response = ssm_client.get_parameters(Names=[vpc_id_key, target_sg_id_key, source_sg_id_key])
    parameters = param_response['Parameters']
    param_dict = {}
    for param in parameters:
        param_dict[param['Name']] = param['Value']
    vpc_id = param_dict[vpc_id_key]
    security_groups = [param_dict[target_sg_id_key], param_dict[source_sg_id_key]]
    subnet_ids = get_private_subnets(vpc_id)

    response = osi_client.create_pipeline(
        PipelineName=pipeline_name,
        MinUnits=2,
        MaxUnits=4,
        PipelineConfigurationBody=pipeline_config,
        LogPublishingOptions={
            'IsLoggingEnabled': False,
            # TODO provide
            #'CloudWatchLogDestination': {
            #    'LogGroup': 'string'
            #}
        },
        VpcOptions={
            'SubnetIds': subnet_ids,
            'SecurityGroupIds': security_groups
        },
        BufferOptions={
            # TODO review
            'PersistentBufferEnabled': False
        },
        Tags=[
            {
                'Key': 'migration_deployment',
                # TODO pass in
                'Value': '1.0.3'
            },
        ]
    )


if __name__ == "__main__":
    args = parse_args()
    _initialize_logging()
    logger.info(f"Incoming args: {args}")

    client = boto3.client('osis')

    if args.subcommand == "start-pipeline":
        start_pipeline(client, args.name)
    elif args.subcommand == "stop-pipeline":
        stop_pipeline(client, args.name)
    elif args.subcommand == "create-pipeline":
        pipeline_config_loaded = construct_pipeline_config(DEFAULT_PIPELINE_CONFIG_PATH)
        #print(pipeline_config_loaded)
        create_pipeline(client, args.name, pipeline_config_loaded)
