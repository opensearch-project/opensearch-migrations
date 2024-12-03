from console_link.models.client_options import ClientOptions
from console_link.models.osi_utils import (create_pipeline_from_env, start_pipeline, stop_pipeline,
                                           OpenSearchIngestionMigrationProps)
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from typing import Dict, Optional
from cerberus import Validator

from console_link.models.utils import create_boto3_client

OSI_SCHEMA = {
    'pipeline_role_arn': {
        'type': 'string',
        'required': True
    },
    'vpc_subnet_ids': {
        'type': 'list',
        'required': True,
        'schema': {
            'type': 'string',
        }
    },
    'security_group_ids': {
        'type': 'list',
        'required': True,
        'schema': {
            'type': 'string',
        }
    },
    'aws_region': {
        'type': 'string',
        'required': True
    },
    'pipeline_name': {
        'type': 'string',
        'required': False
    },
    'index_regex_selection': {
        'type': 'list',
        'required': False,
        'schema': {
            'type': 'string',
        }
    },
    'log_group_name': {
        'type': 'string',
        'required': False
    },
    'tags': {
        'type': 'list',
        'required': False,
        'schema': {
            'type': 'string',
        }
    }
}


class OpenSearchIngestionBackfill(Backfill):
    """
    A migration manager for an OpenSearch Ingestion pipeline.
    """

    def __init__(self, config: Dict, source_cluster: Cluster, target_cluster: Cluster,
                 client_options: Optional[ClientOptions] = None) -> None:
        super().__init__(config)
        self.client_options = client_options
        config = config["opensearch_ingestion"]

        v = Validator(OSI_SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for OpenSearchIngestion migration", v.errors)
        self.osi_props = OpenSearchIngestionMigrationProps(config=config)
        self.osi_client = create_boto3_client(aws_service_name='osis', client_options=self.client_options)
        self.source_cluster = source_cluster
        self.target_cluster = target_cluster

    def create(self, pipeline_template_path='/root/osiPipelineTemplate.yaml',
               print_config_only=False):
        create_pipeline_from_env(osi_client=self.osi_client,
                                 pipeline_template_path=pipeline_template_path,
                                 osi_props=self.osi_props,
                                 source_cluster=self.source_cluster,
                                 target_cluster=self.target_cluster,
                                 print_config_only=print_config_only)

    def start(self, pipeline_name=None):
        if pipeline_name is None:
            pipeline_name = self.osi_props.pipeline_name
        start_pipeline(osi_client=self.osi_client, pipeline_name=pipeline_name)

    def pause(self, pipeline_name=None) -> CommandResult:
        raise NotImplementedError()

    def stop(self, pipeline_name=None):
        if pipeline_name is None:
            pipeline_name = self.osi_props.pipeline_name
        stop_pipeline(osi_client=self.osi_client, pipeline_name=pipeline_name)

    def get_status(self, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()

    def archive(self, pipeline_name=None) -> CommandResult:
        raise NotImplementedError()
