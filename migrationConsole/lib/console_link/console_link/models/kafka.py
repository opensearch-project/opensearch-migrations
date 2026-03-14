import subprocess
import os
import shutil
from pathlib import Path
from typing import List

from cerberus import Validator
import logging
from abc import ABC, abstractmethod
from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)

KAFKA_TOPICS_SCRIPT = 'kafka-topics.sh'
KAFKA_CONSUMER_GROUPS_SCRIPT = 'kafka-consumer-groups.sh'
KAFKA_RUN_CLASS_SCRIPT = 'kafka-run-class.sh'
MSK_IAM_AUTH_PROPERTIES = 'msk-iam-auth.properties'

MSK_SCHEMA = {
    "nullable": True,
}

STANDARD_SCHEMA = {
    "nullable": True,
}

SCHEMA = {
    'kafka': {
        'type': 'dict',
        'schema': {
            'broker_endpoints': {"type": "string", "required": True},
            'msk': MSK_SCHEMA,
            'standard': STANDARD_SCHEMA
        },
        'check_with': contains_one_of({'msk', 'standard'})
    }
}

CONTAINER_KAFKA_TOOLS_HOME = Path('/root/kafka-tools')


def _candidate_kafka_homes() -> list[Path]:
    model_dir = Path(__file__).resolve().parent
    repo_root = model_dir.parents[4]

    candidates: list[Path] = []
    for env_var in ('KAFKA_TOOLS_HOME', 'KAFKA_HOME'):
        value = os.getenv(env_var)
        if value:
            candidates.append(Path(value))

    candidates.extend([
        repo_root / 'kafka-tools',
        repo_root / 'build' / 'kafka-tools',
        repo_root / 'build' / 'dockerContext' / 'kafka-tools',
        CONTAINER_KAFKA_TOOLS_HOME,
    ])
    return candidates


def resolve_kafka_tool(script_name: str) -> str:
    on_path = shutil.which(script_name)
    if on_path:
        return on_path

    for home in _candidate_kafka_homes():
        candidates = [
            home / script_name,
            home / 'bin' / script_name,
            home / 'kafka' / 'bin' / script_name,
        ]
        for candidate in candidates:
            if candidate.exists():
                return str(candidate)

    raise FileNotFoundError(
        f"Could not find Kafka tool '{script_name}'. Set KAFKA_TOOLS_HOME to a directory containing "
        f"'{script_name}', 'bin/{script_name}', or 'kafka/bin/{script_name}', or run from the container image "
        f"where /root/kafka-tools is available."
    )


def resolve_msk_auth_config() -> str:
    for home in _candidate_kafka_homes():
        candidates = [
            home / MSK_IAM_AUTH_PROPERTIES,
            home / 'aws' / MSK_IAM_AUTH_PROPERTIES,
        ]
        for candidate in candidates:
            if candidate.exists():
                return str(candidate)

    candidate = Path(__file__).resolve().parents[4] / MSK_IAM_AUTH_PROPERTIES
    if candidate.exists():
        return str(candidate)

    raise FileNotFoundError(
        f"Could not find {MSK_IAM_AUTH_PROPERTIES}. Set KAFKA_TOOLS_HOME to a directory containing "
        f"'{MSK_IAM_AUTH_PROPERTIES}' or 'aws/{MSK_IAM_AUTH_PROPERTIES}', or run from the container image where "
        "/root/kafka-tools is available."
    )


def get_result_for_command(command: List[str], operation_name: str) -> CommandResult:
    try:
        cmd_output = subprocess.run(command, capture_output=True, text=True, check=True)
        output = cmd_output.stdout
        message = f"{operation_name} command completed successfully"
        logger.info(message)
        if not output:
            output = f"Command for {operation_name} completed successfully.\n"
        return CommandResult(success=True, value=output)
    except subprocess.CalledProcessError as e:
        message = f"Failed to perform {operation_name} command: {str(e)} Standard Error Output: {e.stderr}"
        logger.info(message)
        output = e.stdout
        return CommandResult(success=False, value=output)


def pretty_print_kafka_record_count(data: str) -> str:
    # Split the data into lines
    lines = data.split("\n")

    # Define headers
    headers = ["TOPIC", "PARTITION", "RECORDS"]

    # Initialize the formatted output with headers
    formatted_output = "{:<30} {:<10} {:<10}".format(*headers) + "\n"

    # Format each line of data
    for line in lines:
        if line and line.count(":") == 2:
            topic, partition, records = line.split(":")
            formatted_output += "{:<30} {:<10} {:<10}".format(topic, partition, records) + "\n"
    return formatted_output


class Kafka(ABC):
    """
    Interface for Kafka command line operations
    """

    def __init__(self, config):
        logger.info(f"Initializing Kafka with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate({'kafka': config}):
            logger.error(f"Invalid config: {v.errors}")
            raise ValueError(v.errors)
        self.brokers = config.get('broker_endpoints')

    @abstractmethod
    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        pass

    @abstractmethod
    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        pass

    @abstractmethod
    def list_topics(self) -> CommandResult:
        pass

    @abstractmethod
    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        pass

    @abstractmethod
    def list_consumer_groups(self) -> CommandResult:
        pass

    @abstractmethod
    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        pass


class MSK(Kafka):
    """
    AWS MSK implementation of Kafka operations
    """

    def __init__(self, config):
        super().__init__(config)

    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--delete',
                   '--topic', f'{topic_name}', '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Delete Topic")

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--create',
                   '--topic', f'{topic_name}', '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Create Topic")

    def list_topics(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--list',
                   '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "List Topics")

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--describe', '--group', f'{group_name}',
                   '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Describe Consumer Group")

    def list_consumer_groups(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--list', '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "List Consumer Groups")

    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_RUN_CLASS_SCRIPT),
                   'org.apache.kafka.tools.GetOffsetShell', '--bootstrap-server',
                   f'{self.brokers}', '--topic', f'{topic_name}', '--time', '-1',
                   '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, "Describe Topic Records")
        if result.success and result.value:
            pretty_value = pretty_print_kafka_record_count(result.value)
            return CommandResult(success=result.success, value=pretty_value)
        return result


class StandardKafka(Kafka):
    """
    Standard Kafka distribution implementation of Kafka operations
    """

    def __init__(self, config):
        super().__init__(config)

    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--delete',
                   '--topic', f'{topic_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Delete Topic")

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--create',
                   '--topic', f'{topic_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Create Topic")

    def list_topics(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--list']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "List Topics")

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--describe', '--group', f'{group_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Describe Consumer Group")

    def list_consumer_groups(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--list']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "List Consumer Groups")

    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool('kafka-run-class.sh'),
                   'org.apache.kafka.tools.GetOffsetShell', '--bootstrap-server',
                   f'{self.brokers}', '--topic', f'{topic_name}', '--time', '-1']
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, "Describe Topic Records")
        if result.success and result.value:
            pretty_value = pretty_print_kafka_record_count(result.value)
            return CommandResult(success=result.success, value=pretty_value)
        return result
