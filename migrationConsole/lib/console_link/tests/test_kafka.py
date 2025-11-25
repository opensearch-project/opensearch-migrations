import pytest

from console_link.models.factories import UnsupportedKafkaError, get_kafka
from console_link.models.kafka import Kafka, MSK, StandardKafka


def test_get_msk_kafka():
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    assert isinstance(kafka, Kafka)
    assert isinstance(kafka, MSK)


def test_get_standard_kafka():
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    assert isinstance(kafka, Kafka)
    assert isinstance(kafka, StandardKafka)


def test_unsupported_kafka_type_raises_error():
    config = {
        "broker_endpoints": "abc",
        "new_kafka_type": None
    }
    with pytest.raises(UnsupportedKafkaError) as exc_info:
        get_kafka(config)
    assert 'new_kafka_type' in exc_info.value.args


def test_no_kafka_type_raises_error():
    config = {
        "broker_endpoints": "abc",
    }
    with pytest.raises(UnsupportedKafkaError):
        get_kafka(config)


def test_multiple_kafka_types_raises_error():
    config = {
        "broker_endpoints": "abc",
        "msk": None,
        "standard": None
    }
    with pytest.raises(ValueError) as exc_info:
        get_kafka(config)
    
    assert "More than one value is present" in exc_info.value.args[0]['kafka'][0]


def test_msk_kafka_create_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.create_topic(topic_name='new_topic')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--create',
         '--topic', 'new_topic', '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ], capture_output=True, text=True, check=True)


def test_standard_kafka_create_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.create_topic(topic_name='new_topic')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--create',
         '--topic', 'new_topic'
         ], capture_output=True, text=True, check=True)


def test_msk_kafka_delete_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.delete_topic(topic_name='new_topic')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--delete',
         '--topic', 'new_topic', '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ], capture_output=True, text=True, check=True)


def test_standard_kafka_delete_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.delete_topic(topic_name='new_topic')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--delete',
         '--topic', 'new_topic'
         ], capture_output=True, text=True, check=True)


def test_msk_kafka_describe_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.describe_topic_records(topic_name='new_topic')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-run-class.sh', 'org.apache.kafka.tools.GetOffsetShell',
         '--broker-list', f"{config['broker_endpoints']}",
         '--topic', 'new_topic',
         '--time', '-1',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ], capture_output=True, text=True, check=True)


def test_standard_kafka_describe_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.describe_topic_records(topic_name='new_topic')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-run-class.sh', 'org.apache.kafka.tools.GetOffsetShell',
         '--broker-list', f"{config['broker_endpoints']}",
         '--topic', 'new_topic',
         '--time', '-1'
         ], capture_output=True, text=True, check=True)


def test_msk_kafka_describe_group(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.describe_consumer_group(group_name='new_group')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--describe',
         '--group', 'new_group',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ], capture_output=True, text=True, check=True)


def test_standard_kafka_describe_group(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.describe_consumer_group(group_name='new_group')

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--describe',
         '--group', 'new_group',
         ], capture_output=True, text=True, check=True)
