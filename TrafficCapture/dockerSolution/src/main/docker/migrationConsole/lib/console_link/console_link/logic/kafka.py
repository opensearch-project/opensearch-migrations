import logging
from console_link.models.kafka import Kafka
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)


def create_topic(kafka: Kafka, topic_name: str) -> CommandResult:
    return kafka.create_topic(topic_name=topic_name)


def delete_topic(kafka: Kafka, topic_name: str) -> CommandResult:
    return kafka.delete_topic(topic_name=topic_name)


def describe_consumer_group(kafka: Kafka, group_name: str) -> CommandResult:
    return kafka.describe_consumer_group(group_name=group_name)
