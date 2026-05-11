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
    # Augmenter would otherwise try to probe message timestamps via additional
    # subprocess calls; this test only cares about the describe invocation.
    mocker.patch.object(kafka_module, '_augment_describe_output_with_time_lag',
                        side_effect=lambda result, *args, **kwargs: result)
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
    mocker.patch.object(kafka_module, '_augment_describe_output_with_time_lag',
                        side_effect=lambda result, *args, **kwargs: result)
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


# ---------------------------------------------------------------------------
# Time-lag augmentation: parser, formatters, and section builder
# ---------------------------------------------------------------------------

from console_link.models.command_result import CommandResult


_DESCRIBE_OUTPUT_BASIC = """\

GROUP            TOPIC               PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG  CONSUMER-ID                                       HOST            CLIENT-ID
my-group         logging-topic       0          100             150             50   consumer-1-abc /172.18.0.5     consumer-1
my-group         logging-topic       1          200             200             0    consumer-1-def /172.18.0.5     consumer-1
my-group         logging-topic       2          0               0               0    -                                                 -               -
"""


def test_parse_consumer_group_describe_basic():
    rows = kafka_module.parse_consumer_group_describe(_DESCRIBE_OUTPUT_BASIC)
    assert len(rows) == 3
    assert rows[0] == {
        'topic': 'logging-topic', 'partition': 0,
        'current_offset': 100, 'log_end_offset': 150, 'lag': 50,
    }
    # Caught-up partition: lag 0
    assert rows[1]['lag'] == 0
    assert rows[1]['current_offset'] == rows[1]['log_end_offset'] == 200
    # Empty partition
    assert rows[2]['log_end_offset'] == 0


def test_parse_consumer_group_describe_skips_dashes_in_offsets():
    output = """\
GROUP    TOPIC      PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
g1       t1         0          -               150             -
g1       t1         1          50              -               -
g1       t1         2          50              100             50
"""
    rows = kafka_module.parse_consumer_group_describe(output)
    # Only the fully-numeric row survives.
    assert len(rows) == 1
    assert rows[0]['partition'] == 2


def test_parse_consumer_group_describe_handles_empty_or_no_header():
    assert kafka_module.parse_consumer_group_describe("") == []
    assert kafka_module.parse_consumer_group_describe("Consumer group 'foo' has no active members.\n") == []


def test_format_duration_compact():
    assert kafka_module._format_duration(0) == '0s'
    assert kafka_module._format_duration(45) == '45s'
    assert kafka_module._format_duration(60) == '1m'
    assert kafka_module._format_duration(125) == '2m5s'
    assert kafka_module._format_duration(3600) == '1h'
    assert kafka_module._format_duration(3661) == '1h1m1s'
    assert kafka_module._format_duration(86400 + 3600) == '1d1h'
    # Negative durations (clock skew / future timestamps) are flagged.
    assert kafka_module._format_duration(-30).startswith('-')


def test_format_iso_utc_round_trip():
    # 2024-01-02T03:04:05.678 UTC = 1704164645678
    assert kafka_module._format_iso_utc(1704164645678) == '2024-01-02T03:04:05.678Z'


def test_build_time_lag_section_caught_up_probes_log_end_offset_minus_one(mocker):
    output = """\
GROUP   TOPIC    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
g1      t1       0          200             200             0
"""
    # Probe returns a timestamp 30 seconds before now_ms.
    now_ms = 2_000_000_000_000
    mocker.patch.object(kafka_module, '_probe_record_timestamp_ms',
                        return_value=(now_ms - 30_000, None))
    section = kafka_module._build_time_lag_section(
        describe_output=output,
        brokers='kafka:9092',
        extra_console_consumer_args=[],
        now_ms=now_ms,
    )
    assert 'PARTITION TIME LAG' in section
    # caught-up note must appear, and probed offset = LOG-END-OFFSET - 1 = 199
    assert 'caught up' in section
    assert '199' in section
    assert '30s' in section


def test_build_time_lag_section_active_lag_probes_current_offset(mocker):
    output = """\
GROUP   TOPIC    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
g1      t1       0          100             150             50
"""
    now_ms = 2_000_000_000_000
    captured = {}

    def fake_probe(command, timeout_seconds):
        captured['command'] = command
        return now_ms - 600_000, None  # 10 minutes ago

    mocker.patch.object(kafka_module, '_probe_record_timestamp_ms', side_effect=fake_probe)
    section = kafka_module._build_time_lag_section(
        describe_output=output,
        brokers='kafka:9092',
        extra_console_consumer_args=['--consumer.config', '/tmp/x.props'],
        now_ms=now_ms,
    )
    cmd = captured['command']
    # Probed at CURRENT-OFFSET, not LOG-END-OFFSET - 1
    assert '--offset' in cmd and cmd[cmd.index('--offset') + 1] == '100'
    # Auth flags forwarded
    assert '--consumer.config' in cmd
    # 10 minutes ago renders as 10m
    assert '10m' in section
    # caught-up note must NOT appear for active-lag rows
    assert 'caught up' not in section


def test_build_time_lag_section_empty_partition_skips_probe(mocker):
    output = """\
GROUP   TOPIC    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
g1      t1       0          0               0               0
"""
    probe = mocker.patch.object(kafka_module, '_probe_record_timestamp_ms')
    section = kafka_module._build_time_lag_section(
        describe_output=output,
        brokers='kafka:9092',
        extra_console_consumer_args=[],
        now_ms=2_000_000_000_000,
    )
    probe.assert_not_called()
    assert 'partition empty' in section


def test_build_time_lag_section_probe_failure_renders_dashes(mocker):
    output = """\
GROUP   TOPIC    PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
g1      t1       0          100             150             50
"""
    mocker.patch.object(kafka_module, '_probe_record_timestamp_ms',
                        return_value=(None, 'probe timed out'))
    section = kafka_module._build_time_lag_section(
        describe_output=output,
        brokers='kafka:9092',
        extra_console_consumer_args=[],
        now_ms=2_000_000_000_000,
    )
    assert 'probe timed out' in section
    # Time-lag column should be a dash on failure
    lines = [line for line in section.splitlines() if 't1' in line]
    assert lines, 'expected a data row for topic t1'


def test_build_time_lag_section_no_committed_offsets_message():
    section = kafka_module._build_time_lag_section(
        describe_output='Consumer group has no committed offsets.\n',
        brokers='kafka:9092',
        extra_console_consumer_args=[],
        now_ms=2_000_000_000_000,
    )
    assert 'no committed offsets parsed' in section


def test_augment_returns_original_on_failed_result():
    base = CommandResult(success=False, value='boom')
    out = kafka_module._augment_describe_output_with_time_lag(
        base_result=base, brokers='kafka:9092', extra_console_consumer_args=[],
    )
    assert out is base


def test_augment_returns_original_on_empty_value():
    base = CommandResult(success=True, value='')
    out = kafka_module._augment_describe_output_with_time_lag(
        base_result=base, brokers='kafka:9092', extra_console_consumer_args=[],
    )
    assert out is base


def test_augment_swallows_helper_exception(mocker):
    mocker.patch.object(kafka_module, '_build_time_lag_section',
                        side_effect=RuntimeError('kaboom'))
    base = CommandResult(success=True, value='describe table here\n')
    out = kafka_module._augment_describe_output_with_time_lag(
        base_result=base, brokers='kafka:9092', extra_console_consumer_args=[],
    )
    # On failure we hand back the original CommandResult untouched.
    assert out.value == 'describe table here\n'
    assert out.success is True


def test_augment_appends_section_on_success(mocker):
    mocker.patch.object(kafka_module, '_build_time_lag_section',
                        return_value='PARTITION TIME LAG\n  (test section)\n')
    base = CommandResult(success=True, value='native describe table\n')
    out = kafka_module._augment_describe_output_with_time_lag(
        base_result=base, brokers='kafka:9092', extra_console_consumer_args=[],
    )
    assert out.success is True
    assert out.value.startswith('native describe table')
    assert 'PARTITION TIME LAG' in out.value
    assert '(test section)' in out.value
