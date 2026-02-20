import subprocess
from typing import List

from cerberus import Validator
import logging
from abc import ABC, abstractmethod
from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)

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

KAFKA_TOPICS_COMMAND = '/root/kafka-tools/kafka/bin/kafka-topics.sh'
MSK_AUTH_PARAMETERS = ['--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties']


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
    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
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
        command = [KAFKA_TOPICS_COMMAND, '--bootstrap-server', f'{self.brokers}', '--delete',
                   '--topic', f'{topic_name}'] + MSK_AUTH_PARAMETERS
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Delete Topic")

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [KAFKA_TOPICS_COMMAND, '--bootstrap-server', f'{self.brokers}', '--create',
                   '--topic', f'{topic_name}'] + MSK_AUTH_PARAMETERS
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Create Topic")

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--describe', '--group', f'{group_name}'] + MSK_AUTH_PARAMETERS
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Describe Consumer Group")

    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-run-class.sh',
                   'org.apache.kafka.tools.GetOffsetShell', '--broker-list',
                   f'{self.brokers}', '--topic', f'{topic_name}', '--time', '-1'] + MSK_AUTH_PARAMETERS
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
        command = [KAFKA_TOPICS_COMMAND, '--bootstrap-server', f'{self.brokers}', '--delete',
                   '--topic', f'{topic_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Delete Topic")

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [KAFKA_TOPICS_COMMAND, '--bootstrap-server', f'{self.brokers}', '--create',
                   '--topic', f'{topic_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Create Topic")

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--describe', '--group', f'{group_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Describe Consumer Group")

    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-run-class.sh',
                   'org.apache.kafka.tools.GetOffsetShell', '--broker-list',
                   f'{self.brokers}', '--topic', f'{topic_name}', '--time', '-1']
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, "Describe Topic Records")
        if result.success and result.value:
            pretty_value = pretty_print_kafka_record_count(result.value)
            return CommandResult(success=result.success, value=pretty_value)
        return result
