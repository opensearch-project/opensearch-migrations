import subprocess
from typing import List

from cerberus import Validator
import logging
import json
from abc import ABC, abstractmethod
from dataclasses import dataclass
from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import list_schema
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
            'broker_endpoints': list_schema(required=True),
            'msk': MSK_SCHEMA,
            'standard': STANDARD_SCHEMA
        },
        'check_with': contains_one_of({'msk', 'standard'})
    }
}


@dataclass
class KafkaCommandResultValue:
    message: str
    command_output: str

    def __str__(self):
        # Create a dictionary of the class attributes
        class_dict = {key: value for key, value in self.__dict__.items()}
        # Convert the dictionary to a single-line JSON string
        return json.dumps(class_dict)


def get_result_for_command(command: List[str], operation_name: str) -> CommandResult:
    try:
        # Pass None to stdout and stderr to not capture output and show in terminal
        cmd_output = subprocess.run(command, capture_output=True, text=True, check=True)
        output = cmd_output.stdout
        if output is not None:
            output = ' '.join(output.split())
        message = f"{operation_name} command completed successfully"
        logger.info(message)
        return CommandResult(success=True, value=KafkaCommandResultValue(message=message, command_output=output))
    except subprocess.CalledProcessError as e:
        logger.error(e.stdout)
        message = f"Failed to perform {operation_name} command: {str(e)}"
        logger.info(message)
        output = e.stderr
        if output is not None:
            output = ' '.join(output.split())
        return CommandResult(success=False, value=KafkaCommandResultValue(message=message, command_output=output))


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
        broker_list = config.get('broker_endpoints')
        self.brokers = ','.join(broker_list)

    @abstractmethod
    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        pass

    @abstractmethod
    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        pass

    @abstractmethod
    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        pass


class MSK(Kafka):
    """
    AWS MSK implementation of Kafka operations
    """

    def __init__(self, config):
        super().__init__(config)

    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-topics.sh', '--bootstrap-server', f'{self.brokers}', '--delete',
                   '--topic', f'{topic_name}', '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Delete Topic")

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-topics.sh', '--bootstrap-server', f'{self.brokers}', '--create',
                   '--topic', f'{topic_name}', '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Create Topic")

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-topics.sh', '--bootstrap-server', f'{self.brokers}', '--timeout',
                   '100000', '--describe', '--group', f'{group_name}', '--command-config',
                   '/root/kafka-tools/aws/msk-iam-auth.properties']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Describe Consumer Group")


class StandardKafka(Kafka):
    """
    Standard Kafka distribution implementation of Kafka operations
    """

    def __init__(self, config):
        super().__init__(config)

    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-topics.sh', '--bootstrap-server', f'{self.brokers}', '--delete',
                   '--topic', f'{topic_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Delete Topic")

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-topics.sh', '--bootstrap-server', f'{self.brokers}', '--create',
                   '--topic', f'{topic_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Create Topic")

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--describe', '--group', f'{group_name}']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Describe Consumer Group")
