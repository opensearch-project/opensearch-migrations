import pytest

from console_link.models import kafka as kafka_module
from console_link.models.factories import UnsupportedKafkaError, get_kafka
from console_link.models.kafka import Kafka, MSK, StandardKafka, ScramKafka


@pytest.fixture(autouse=True)
def stub_kafka_tool_paths(mocker):
    mocker.patch.object(kafka_module, 'resolve_kafka_tool',
                        side_effect=lambda script_name: f'/root/kafka-tools/kafka/bin/{script_name}')
    mocker.patch.object(kafka_module, 'resolve_msk_auth_config',
                        return_value='/root/kafka-tools/aws/msk-iam-auth.properties')


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


def test_msk_kafka_list_topics(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_topics()

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--list',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ], capture_output=True, text=True, check=True)


def test_standard_kafka_list_topics(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_topics()

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--list'
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
         '--bootstrap-server', f"{config['broker_endpoints']}",
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
         '--bootstrap-server', f"{config['broker_endpoints']}",
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


def test_msk_kafka_list_groups(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_consumer_groups()

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--list',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ], capture_output=True, text=True, check=True)


def test_standard_kafka_list_groups(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_consumer_groups()

    assert result.success
    mock.assert_called_once_with(
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--list'
         ], capture_output=True, text=True, check=True)


def test_get_scram_kafka(monkeypatch):
    monkeypatch.setenv("KAFKA_SCRAM_PASSWORD", "test-pass")
    config = {
        "broker_endpoints": "kafka:9093",
        "scram": {
            "username": "my-user",
            "password_env": "KAFKA_SCRAM_PASSWORD",
        }
    }
    kafka = get_kafka(config)
    assert isinstance(kafka, Kafka)
    assert isinstance(kafka, ScramKafka)


def test_scram_kafka_with_inline_password():
    config = {
        "broker_endpoints": "kafka:9093",
        "scram": {
            "username": "my-user",
            "password": "inline-pass",
        }
    }
    kafka = get_kafka(config)
    assert isinstance(kafka, ScramKafka)
    assert kafka.password == "inline-pass"


def test_scram_kafka_missing_password_raises():
    config = {
        "broker_endpoints": "kafka:9093",
        "scram": {
            "username": "my-user",
            "password_env": "NONEXISTENT_ENV_VAR_FOR_TEST",
        }
    }
    with pytest.raises(ValueError, match="SCRAM password not found"):
        get_kafka(config)


def test_scram_kafka_properties_file_content():
    config = {
        "broker_endpoints": "kafka:9093",
        "scram": {
            "username": "my-user",
            "password": "my-pass",
            "ca_cert_path": "/certs/ca.crt",
        }
    }
    kafka = ScramKafka(config)
    with open(kafka._props_file) as f:
        content = f.read()
    assert "security.protocol=SASL_SSL" in content
    assert "sasl.mechanism=SCRAM-SHA-512" in content
    assert 'username="my-user"' in content
    assert 'password="my-pass"' in content
    assert "ssl.truststore.type=PEM" in content
    assert "ssl.truststore.location=/certs/ca.crt" in content


def test_scram_kafka_create_topic(mocker):
    config = {
        "broker_endpoints": "kafka:9093",
        "scram": {
            "username": "my-user",
            "password": "my-pass",
        }
    }
    kafka = ScramKafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.create_topic(topic_name='new_topic')

    assert result.success
    mock.assert_called_once()
    call_args = mock.call_args[0][0]
    assert '--bootstrap-server' in call_args
    assert 'kafka:9093' in call_args
    assert '--create' in call_args
    assert '--topic' in call_args
    assert 'new_topic' in call_args
    assert '--command-config' in call_args
    assert call_args[call_args.index('--command-config') + 1] == kafka._props_file


def test_scram_kafka_list_topics(mocker):
    config = {
        "broker_endpoints": "kafka:9093",
        "scram": {
            "username": "my-user",
            "password": "my-pass",
        }
    }
    kafka = ScramKafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_topics()

    assert result.success
    call_args = mock.call_args[0][0]
    assert '--list' in call_args
    assert '--command-config' in call_args
