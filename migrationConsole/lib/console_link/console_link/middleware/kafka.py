import logging
from console_link.models.kafka import Kafka
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)


def create_topic(kafka: Kafka, topic_name: str) -> CommandResult:
    result = kafka.create_topic(topic_name=topic_name)
    return result


def list_topics(kafka: Kafka) -> CommandResult:
    result = kafka.list_topics()
    return result


def delete_topic(kafka: Kafka, topic_name: str) -> CommandResult:
    result = kafka.delete_topic(topic_name=topic_name)
    return result


def describe_consumer_group(kafka: Kafka, group_name: str) -> CommandResult:
    result = kafka.describe_consumer_group(group_name=group_name)
    return result


def list_consumer_groups(kafka: Kafka) -> CommandResult:
    result = kafka.list_consumer_groups()
    return result


def describe_topic_records(kafka: Kafka, topic_name: str) -> CommandResult:
    result = kafka.describe_topic_records(topic_name=topic_name)
    return result


def dump_topic_records(kafka: Kafka, *, namespace: str = "ma", topic: str,
                       mode: str = "dump-both",
                       start_offset=None, end_offset=None,
                       start_time=None, end_time=None,
                       pod_timeout_seconds: int = 600, echo=print) -> CommandResult:
    # Imported here to keep the kubernetes dependency out of the import path
    # for callers that only use the kafka-CLI-shell commands.
    from console_link.models.kafka_dump import launch_dump_pod
    return launch_dump_pod(
        kafka, namespace=namespace, topic=topic, mode=mode,
        start_offset=start_offset, end_offset=end_offset,
        start_time=start_time, end_time=end_time,
        pod_timeout_seconds=pod_timeout_seconds, echo=echo,
    )
