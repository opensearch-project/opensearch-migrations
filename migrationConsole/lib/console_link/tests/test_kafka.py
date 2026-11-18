import pytest

from console_link.models import kafka as kafka_module
from console_link.models.command_result import CommandResult
from console_link.models.factories import UnsupportedKafkaError, get_kafka
from console_link.models.kafka import Kafka, MSK, StandardKafka, ScramKafka


@pytest.fixture(autouse=True)
def stub_kafka_tool_paths(mocker):
    mocker.patch.object(kafka_module, 'resolve_kafka_tool',
                        side_effect=lambda script_name: f'/root/kafka-tools/kafka/bin/{script_name}')
    mocker.patch.object(kafka_module, 'resolve_msk_auth_config',
                        return_value='/root/kafka-tools/aws/msk-iam-auth.properties')


def _assert_kafka_subprocess_run(mock, command):
    mock.assert_called_once()
    assert mock.call_args.args[0] == command
    assert mock.call_args.kwargs["capture_output"] is True
    assert mock.call_args.kwargs["text"] is True
    assert mock.call_args.kwargs["check"] is True
    assert mock.call_args.kwargs["env"]["KAFKA_OPTS"].split()[-1] == kafka_module.DISABLE_JAVA_PERFDATA_OPT


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
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--create',
         '--topic', 'new_topic', '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ])


def test_standard_kafka_create_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.create_topic(topic_name='new_topic')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--create',
         '--topic', 'new_topic'
         ])


def test_msk_kafka_list_topics(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_topics()

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--list',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ])


def test_standard_kafka_list_topics(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_topics()

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--list'
         ])


def test_kafka_command_result_disables_java_perfdata(monkeypatch, mocker):
    monkeypatch.delenv("KAFKA_OPTS", raising=False)
    mock = mocker.patch('subprocess.run', autospec=True)

    result = kafka_module.get_result_for_command(['kafka-topics.sh'], 'List Topics')

    assert result.success
    assert mock.call_args.kwargs["env"]["KAFKA_OPTS"] == kafka_module.DISABLE_JAVA_PERFDATA_OPT


def test_kafka_command_result_preserves_existing_kafka_opts(monkeypatch, mocker):
    monkeypatch.setenv("KAFKA_OPTS", "-Dexisting=true")
    mock = mocker.patch('subprocess.run', autospec=True)

    result = kafka_module.get_result_for_command(['kafka-topics.sh'], 'List Topics')

    assert result.success
    assert mock.call_args.kwargs["env"]["KAFKA_OPTS"] == "-Dexisting=true -XX:-UsePerfData"


def test_msk_kafka_delete_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.delete_topic(topic_name='new_topic')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--delete',
         '--topic', 'new_topic', '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ])


def test_standard_kafka_delete_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.delete_topic(topic_name='new_topic')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-topics.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--delete',
         '--topic', 'new_topic'
         ])


def test_msk_kafka_describe_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.describe_topic_records(topic_name='new_topic')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-run-class.sh', 'org.apache.kafka.tools.GetOffsetShell',
         '--bootstrap-server', f"{config['broker_endpoints']}",
         '--topic', 'new_topic',
         '--time', '-1',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ])


def test_standard_kafka_describe_topic(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.describe_topic_records(topic_name='new_topic')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-run-class.sh', 'org.apache.kafka.tools.GetOffsetShell',
         '--bootstrap-server', f"{config['broker_endpoints']}",
         '--topic', 'new_topic',
         '--time', '-1'
         ])


def test_msk_kafka_describe_group(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    # This test only covers the native describe invocation.
    mocker.patch.object(kafka_module, '_augment_describe_output_with_replay_progress',
                        side_effect=lambda result, *args, **kwargs: result)
    result = kafka.describe_consumer_group(group_name='new_group')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--describe',
         '--group', 'new_group',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ])


def test_standard_kafka_describe_group(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    mocker.patch.object(kafka_module, '_augment_describe_output_with_replay_progress',
                        side_effect=lambda result, *args, **kwargs: result)
    result = kafka.describe_consumer_group(group_name='new_group')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--describe',
         '--group', 'new_group',
         ])


def test_standard_kafka_describe_group_can_skip_time_lag(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    augmenter = mocker.patch.object(
        kafka_module,
        '_augment_describe_output_with_replay_progress',
    )

    result = kafka.describe_consumer_group(
        group_name='new_group',
        include_time_lag=False,
    )

    assert result.success
    augmenter.assert_not_called()
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--describe',
         '--group', 'new_group',
         ])


_DESCRIBE_OUTPUT_BASIC = """\
GROUP     TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID  HOST  CLIENT-ID
my-group  logging-topic  0          100             150             50   c1           h1    c
my-group  logging-topic  1          200             200             0    c1           h1    c
my-group  logging-topic  2          0               0               0    -            -     -
"""


def test_parse_consumer_group_describe_basic():
    assert kafka_module.parse_consumer_group_describe(_DESCRIBE_OUTPUT_BASIC) == [
        {
            'topic': 'logging-topic',
            'partition': 0,
            'current_offset': 100,
            'log_end_offset': 150,
            'lag': 50,
        },
        {
            'topic': 'logging-topic',
            'partition': 1,
            'current_offset': 200,
            'log_end_offset': 200,
            'lag': 0,
        },
        {
            'topic': 'logging-topic',
            'partition': 2,
            'current_offset': 0,
            'log_end_offset': 0,
            'lag': 0,
        },
    ]


def test_parse_consumer_group_describe_recovers_missing_lag():
    output = """\
GROUP   TOPIC   PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
g1      t1      0          90              100             -
g1      t1      1          -               100             -
"""
    assert kafka_module.parse_consumer_group_describe(output) == [
        {
            'topic': 't1',
            'partition': 0,
            'current_offset': 90,
            'log_end_offset': 100,
            'lag': 10,
        },
    ]


def test_msk_kafka_list_groups(mocker):
    config = {
        "broker_endpoints": "abc",
        "msk": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_consumer_groups()

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--list',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'
         ])


def test_standard_kafka_list_groups(mocker):
    config = {
        "broker_endpoints": "abc",
        "standard": None
    }
    kafka = get_kafka(config)
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.list_consumer_groups()

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh',
         '--bootstrap-server', f"{config['broker_endpoints']}", '--timeout', '100000', '--list'
         ])


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


def test_replay_progress_augmenter_returns_original_on_failed_result():
    base = CommandResult(success=False, value='boom')
    out = kafka_module._augment_describe_output_with_replay_progress(
        base_result=base,
        group_name='replayer',
        brokers='kafka:9092',
    )
    assert out is base


def test_replay_progress_augmenter_returns_original_on_empty_value():
    base = CommandResult(success=True, value='')
    out = kafka_module._augment_describe_output_with_replay_progress(
        base_result=base,
        group_name='replayer',
        brokers='kafka:9092',
    )
    assert out is base


def test_replay_progress_augmenter_preserves_describe_when_utility_is_missing(mocker):
    mocker.patch.object(kafka_module, 'resolve_kafka_utils', side_effect=FileNotFoundError('missing'))
    base = CommandResult(success=True, value='describe table here\n')
    out = kafka_module._augment_describe_output_with_replay_progress(
        base_result=base,
        group_name='replayer',
        brokers='kafka:9092',
    )
    assert out.value == 'describe table here\n'
    assert out.success is True


def test_replay_progress_augmenter_runs_one_metadata_utility_and_appends_output(mocker):
    mocker.patch.object(kafka_module, 'resolve_kafka_utils', return_value='/root/kafkaUtils/bin/kafkaUtils')
    command = mocker.patch.object(
        kafka_module,
        'get_result_for_command',
        return_value=CommandResult(
            success=True,
            value='REPLAY WATERMARK\ntraffic  0  11  4  2m5s\n',
        ),
    )
    base = CommandResult(success=True, value='native describe table\n')
    out = kafka_module._augment_describe_output_with_replay_progress(
        base_result=base,
        group_name='replayer',
        brokers='kafka:9092',
        kafka_property_file='/tmp/client.properties',
    )

    assert out.success is True
    assert out.value == (
        'native describe table\n\n'
        'REPLAY WATERMARK\ntraffic  0  11  4  2m5s\n'
    )
    command.assert_called_once_with(
        [
            '/root/kafkaUtils/bin/kafkaUtils',
            '--consumerGroupProgress', 'replayer',
            '--kafkaBrokers', 'kafka:9092',
            '--kafkaClientId', 'migration-console-replay-progress',
            '--kafkaPropertyFile', '/tmp/client.properties',
        ],
        kafka_module.DESCRIBE_REPLAY_PROGRESS_OPERATION,
    )


def test_msk_kafka_reset_consumer_group_offsets_to_latest(mocker):
    kafka = get_kafka({"broker_endpoints": "abc", "msk": None})
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.reset_consumer_group_offsets_to_latest(group_name='logging-group-x', topic_name='t')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'abc',
         '--group', 'logging-group-x', '--topic', 't', '--reset-offsets', '--to-latest', '--execute',
         '--command-config', '/root/kafka-tools/aws/msk-iam-auth.properties'])


def test_standard_kafka_reset_consumer_group_offsets_to_latest(mocker):
    kafka = get_kafka({"broker_endpoints": "abc", "standard": None})
    mock = mocker.patch('subprocess.run', autospec=True)
    result = kafka.reset_consumer_group_offsets_to_latest(group_name='logging-group-x', topic_name='t')

    assert result.success
    _assert_kafka_subprocess_run(
        mock,
        ['/root/kafka-tools/kafka/bin/kafka-consumer-groups.sh', '--bootstrap-server', 'abc',
         '--group', 'logging-group-x', '--topic', 't', '--reset-offsets', '--to-latest', '--execute'])
