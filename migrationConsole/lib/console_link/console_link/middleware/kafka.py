import logging
from console_link.models.kafka import Kafka
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)


def create_topic(kafka: Kafka, topic_name: str) -> CommandResult:
    result = kafka.create_topic(topic_name=topic_name)
    return result


def delete_topic(kafka: Kafka, topic_name: str) -> CommandResult:
    result = kafka.delete_topic(topic_name=topic_name)
    return result


def describe_consumer_group(kafka: Kafka, group_name: str) -> CommandResult:
    result = kafka.describe_consumer_group(group_name=group_name)
    return result


def describe_topic_records(kafka: Kafka, topic_name: str) -> CommandResult:
    result = kafka.describe_topic_records(topic_name=topic_name)
    return result
