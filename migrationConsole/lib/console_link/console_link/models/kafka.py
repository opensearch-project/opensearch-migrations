import subprocess
import os
import re
import shutil
import tempfile
import time
from pathlib import Path
from typing import List, Optional, Tuple

from cerberus import Validator
import logging
from abc import ABC, abstractmethod
from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)

KAFKA_TOPICS_SCRIPT = 'kafka-topics.sh'
KAFKA_CONSUMER_GROUPS_SCRIPT = 'kafka-consumer-groups.sh'
KAFKA_CONSOLE_CONSUMER_SCRIPT = 'kafka-console-consumer.sh'
KAFKA_RUN_CLASS_SCRIPT = 'kafka-run-class.sh'
MSK_IAM_AUTH_PROPERTIES = 'msk-iam-auth.properties'
DELETE_TOPIC_OPERATION = "Delete Topic"
CREATE_TOPIC_OPERATION = "Create Topic"
LIST_TOPICS_OPERATION = "List Topics"
DESCRIBE_CONSUMER_GROUP_OPERATION = "Describe Consumer Group"
LIST_CONSUMER_GROUPS_OPERATION = "List Consumer Groups"
DESCRIBE_TOPIC_RECORDS_OPERATION = "Describe Topic Records"
GET_OFFSET_SHELL_CLASS = 'org.apache.kafka.tools.GetOffsetShell'

# Default per-partition probe timeout when reading the record at CURRENT-OFFSET
# to derive its timestamp. Kept short because we only ever need one record.
TIME_LAG_PROBE_TIMEOUT_MS = 5000

MSK_SCHEMA = {
    "nullable": True,
}

STANDARD_SCHEMA = {
    "nullable": True,
}

SCRAM_SCHEMA = {
    "type": "dict",
    "schema": {
        "username": {"type": "string", "required": True},
        "password": {"type": "string", "required": False},
        "password_env": {"type": "string", "required": False},
        "ca_cert_path": {"type": "string", "required": False},
    },
}

SCHEMA = {
    'kafka': {
        'type': 'dict',
        'schema': {
            'broker_endpoints': {"type": "string", "required": True},
            'msk': MSK_SCHEMA,
            'standard': STANDARD_SCHEMA,
            'scram': SCRAM_SCHEMA,
        },
        'check_with': contains_one_of({'msk', 'standard', 'scram'})
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


# --------------------------------------------------------------------------- #
# Time-lag augmentation for `describe_consumer_group`.
#
# `kafka-consumer-groups.sh --describe` already reports lag in messages
# (LOG-END-OFFSET - CURRENT-OFFSET). Operators usually also need to know HOW
# OLD that lag is in wall-clock terms — i.e. the timestamp of the record the
# consumer is sitting on, and how long ago that was. There is no Kafka CLI
# that returns offset->timestamp directly, so we resolve it by consuming a
# single record at CURRENT-OFFSET via kafka-console-consumer.sh and parsing
# the `CreateTime:`/`LogAppendTime:` prefix.
#
# For caught-up partitions (LAG == 0), CURRENT-OFFSET points one past the last
# record, so the probe would hang waiting for a not-yet-appended record. In
# that case we probe (LOG-END-OFFSET - 1) to surface the age of the most
# recently appended record, marked clearly. Empty partitions (LOG-END-OFFSET
# == 0) are reported without a probe.
# --------------------------------------------------------------------------- #

# Native describe header layout (Kafka 2.x+):
#   GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID
# Whitespace between columns varies. We anchor on the header row to discover
# column indices, then split data rows by whitespace.
_DESCRIBE_HEADER_TOKENS = (
    'GROUP', 'TOPIC', 'PARTITION', 'CURRENT-OFFSET',
    'LOG-END-OFFSET', 'LAG',
)
_TIMESTAMP_PREFIX_RE = re.compile(r'^(CreateTime|LogAppendTime):(-?\d+)\b')


def parse_consumer_group_describe(text: str) -> List[dict]:
    """Parse `kafka-consumer-groups.sh --describe` table output.

    Returns a list of {topic, partition, current_offset, log_end_offset, lag}
    dicts, one per data row. Rows where any of those four numeric fields are
    missing or '-' (e.g. an inactive group with no committed offset yet) are
    skipped silently — the time-lag section can't add anything for them.
    """
    rows: List[dict] = []
    header_indices: Optional[dict] = None
    for raw_line in text.splitlines():
        tokens = raw_line.split()
        if not tokens:
            continue
        if header_indices is None:
            header_indices = _detect_describe_header(tokens)
            continue
        row = _parse_describe_row(tokens, header_indices)
        if row is not None:
            rows.append(row)
    return rows


def _detect_describe_header(tokens: List[str]) -> Optional[dict]:
    """Return a column->index map if `tokens` is the describe header row."""
    if all(h in tokens for h in _DESCRIBE_HEADER_TOKENS):
        return {h: tokens.index(h) for h in _DESCRIBE_HEADER_TOKENS}
    return None


def _parse_describe_row(tokens: List[str], header_indices: dict) -> Optional[dict]:
    """Extract one data row, or None if the row is malformed/incomplete."""
    if len(tokens) <= header_indices['LAG']:
        return None
    try:
        topic = tokens[header_indices['TOPIC']]
        partition = int(tokens[header_indices['PARTITION']])
        current_offset = _parse_offset(tokens[header_indices['CURRENT-OFFSET']])
        log_end_offset = _parse_offset(tokens[header_indices['LOG-END-OFFSET']])
        lag = _parse_offset(tokens[header_indices['LAG']])
    except (ValueError, IndexError):
        return None
    if current_offset is None or log_end_offset is None:
        return None
    return {
        'topic': topic,
        'partition': partition,
        'current_offset': current_offset,
        'log_end_offset': log_end_offset,
        'lag': lag if lag is not None else log_end_offset - current_offset,
    }


def _parse_offset(token: str) -> Optional[int]:
    if token in ('-', ''):
        return None
    try:
        return int(token)
    except ValueError:
        return None


def _format_duration(seconds: float) -> str:
    """Format a positive duration as a compact human string, e.g. '2h13m4s'.

    Negative durations (clock skew or future-timestamped messages) are
    returned with a leading '-'. Zero is rendered as '0s'.
    """
    if seconds == 0:
        return '0s'
    sign = '-' if seconds < 0 else ''
    seconds = abs(seconds)
    days, rem = divmod(int(seconds), 86400)
    hours, rem = divmod(rem, 3600)
    minutes, secs = divmod(rem, 60)
    parts: List[str] = []
    if days:
        parts.append(f'{days}d')
    if hours:
        parts.append(f'{hours}h')
    if minutes:
        parts.append(f'{minutes}m')
    if secs or not parts:
        parts.append(f'{secs}s')
    return sign + ''.join(parts)


def _format_iso_utc(epoch_ms: int) -> str:
    secs, ms = divmod(epoch_ms, 1000)
    return time.strftime('%Y-%m-%dT%H:%M:%S', time.gmtime(secs)) + f'.{ms:03d}Z'


def _probe_record_timestamp_ms(
    command: List[str],
    timeout_seconds: float,
) -> Tuple[Optional[int], Optional[str]]:
    """Run the prepared kafka-console-consumer command and parse the timestamp
    prefix from its first line of stdout.

    Returns (epoch_ms, error). Exactly one is None.
    """
    try:
        proc = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout_seconds,
        )
    except subprocess.TimeoutExpired:
        return None, 'probe timed out'
    except OSError as exc:
        return None, f'probe failed: {exc}'

    for line in (proc.stdout or '').splitlines():
        match = _TIMESTAMP_PREFIX_RE.match(line)
        if match:
            try:
                ts = int(match.group(2))
            except ValueError:
                continue
            if ts < 0:
                # Kafka reports -1 when the message has no timestamp set
                # (timestamp.type=NoTimestampType). Surface it as an error.
                return None, 'record has no timestamp'
            return ts, None

    stderr_summary = (proc.stderr or '').strip().splitlines()
    if stderr_summary:
        return None, f'probe failed: {stderr_summary[-1][:120]}'
    return None, 'no record returned'


def _build_time_lag_section(
    describe_output: str,
    brokers: str,
    extra_console_consumer_args: List[str],
    probe_timeout_ms: int = TIME_LAG_PROBE_TIMEOUT_MS,
    now_ms: Optional[int] = None,
) -> str:
    """Build the per-partition TIME LAG report appended after the native
    describe output.

    `extra_console_consumer_args` carries auth flags (e.g. --consumer.config
    for SCRAM/MSK) so this helper stays auth-agnostic.
    """
    rows = parse_consumer_group_describe(describe_output)
    if not rows:
        return ('PARTITION TIME LAG\n'
                '  (no committed offsets parsed from the describe output above; '
                'nothing to probe)\n')

    if now_ms is None:
        now_ms = int(time.time() * 1000)

    try:
        consumer_script = resolve_kafka_tool(KAFKA_CONSOLE_CONSUMER_SCRIPT)
    except FileNotFoundError as exc:
        return ('PARTITION TIME LAG\n'
                f'  (skipped: {exc})\n')

    headers = ['TOPIC', 'PARTITION', 'PROBED-OFFSET', 'TIMESTAMP (UTC)',
               'TIME-LAG', 'NOTE']
    # Probe each partition. We deliberately keep this serial: typical groups
    # have a handful of partitions and serializing avoids surprising fan-out
    # of subprocess invocations from a console command.
    formatted_rows: List[List[str]] = [
        _format_partition_row(
            row, now_ms, consumer_script, brokers,
            extra_console_consumer_args, probe_timeout_ms,
        )
        for row in rows
    ]

    # Width-aligned table render. Width = max(header, all cells) per column.
    widths = [len(h) for h in headers]
    for r in formatted_rows:
        for i, cell in enumerate(r):
            widths[i] = max(widths[i], len(cell))

    def _render(cells: List[str]) -> str:
        return '  '.join(cell.ljust(widths[i]) for i, cell in enumerate(cells)).rstrip()

    lines = ['PARTITION TIME LAG', _render(headers)]
    lines.extend(_render(r) for r in formatted_rows)
    lines.append('')  # trailing newline-friendly
    return '\n'.join(lines) + '\n'


def _format_partition_row(
    row: dict,
    now_ms: int,
    consumer_script: str,
    brokers: str,
    extra_console_consumer_args: List[str],
    probe_timeout_ms: int,
) -> List[str]:
    """Compute a single TIME LAG table row for one partition.

    Empty partitions (LOG-END-OFFSET == 0) skip the probe. Caught-up
    partitions probe LOG-END-OFFSET-1 to surface last-record age.
    """
    topic = row['topic']
    partition = row['partition']
    current_offset = row['current_offset']
    log_end_offset = row['log_end_offset']

    if log_end_offset == 0:
        return [topic, str(partition), '-', '-', '-', 'partition empty']

    caught_up = current_offset >= log_end_offset
    probed_offset = (log_end_offset - 1) if caught_up else current_offset
    note = 'caught up; showing last record' if caught_up else ''

    command = [
        consumer_script,
        '--bootstrap-server', brokers,
        '--topic', topic,
        '--partition', str(partition),
        '--offset', str(probed_offset),
        '--max-messages', '1',
        '--timeout-ms', str(probe_timeout_ms),
        '--property', 'print.timestamp=true',
        '--property', 'print.value=false',
        '--property', 'print.key=false',
    ] + extra_console_consumer_args
    # debug-level: this fires once per partition and would flood operator-facing
    # output on a topic with many partitions.
    logger.debug("Probing record timestamp: %s", command)

    # Allow a small buffer over Kafka's --timeout-ms so the JVM can exit cleanly.
    wall_timeout = (probe_timeout_ms / 1000.0) + 5.0
    ts_ms, err = _probe_record_timestamp_ms(command, timeout_seconds=wall_timeout)
    if ts_ms is None:
        err_note = (note + '; ' if note else '') + (err or 'unknown error')
        return [topic, str(partition), str(probed_offset), '-', '-', err_note]

    timestamp_str = _format_iso_utc(ts_ms)
    delta_seconds = (now_ms - ts_ms) / 1000.0
    return [
        topic, str(partition), str(probed_offset),
        timestamp_str, _format_duration(delta_seconds), note,
    ]


def _augment_describe_output_with_time_lag(
    base_result: CommandResult,
    brokers: str,
    extra_console_consumer_args: List[str],
    probe_timeout_ms: int = TIME_LAG_PROBE_TIMEOUT_MS,
) -> CommandResult:
    """Append a TIME LAG section to a successful describe-consumer-group
    CommandResult. On failure (or empty output) the original result is
    returned untouched.
    """
    if not base_result.success or not base_result.value:
        return base_result
    try:
        section = _build_time_lag_section(
            describe_output=base_result.value,
            brokers=brokers,
            extra_console_consumer_args=extra_console_consumer_args,
            probe_timeout_ms=probe_timeout_ms,
        )
    except Exception as exc:  # noqa: BLE001 — never let a probe failure mask the describe output
        logger.warning("Failed to build time-lag section: %s", exc, exc_info=True)
        return base_result
    return CommandResult(success=True, value=base_result.value.rstrip() + '\n\n' + section)


class Kafka(ABC):
    """
    Interface for Kafka command line operations
    """

    def __init__(self, config):
        v = Validator(SCHEMA)
        if not v.validate({'kafka': config}):
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
        return get_result_for_command(command, DELETE_TOPIC_OPERATION)

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--create',
                   '--topic', f'{topic_name}', '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, CREATE_TOPIC_OPERATION)

    def list_topics(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', f'{self.brokers}', '--list',
                   '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, LIST_TOPICS_OPERATION)

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--describe', '--group', f'{group_name}',
                   '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, DESCRIBE_CONSUMER_GROUP_OPERATION)
        return _augment_describe_output_with_time_lag(
            result, self.brokers,
            extra_console_consumer_args=['--consumer.config', resolve_msk_auth_config()],
        )

    def list_consumer_groups(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--list', '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, LIST_CONSUMER_GROUPS_OPERATION)

    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_RUN_CLASS_SCRIPT),
                   GET_OFFSET_SHELL_CLASS, '--bootstrap-server',
                   f'{self.brokers}', '--topic', f'{topic_name}', '--time', '-1',
                   '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, DESCRIBE_TOPIC_RECORDS_OPERATION)
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

    def _base_command(self, script: str) -> List[str]:
        return [resolve_kafka_tool(script), '--bootstrap-server', self.brokers]

    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = self._base_command(KAFKA_TOPICS_SCRIPT) + ['--delete', '--topic', topic_name]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, DELETE_TOPIC_OPERATION)

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = self._base_command(KAFKA_TOPICS_SCRIPT) + ['--create', '--topic', topic_name]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, CREATE_TOPIC_OPERATION)

    def list_topics(self) -> CommandResult:
        command = self._base_command(KAFKA_TOPICS_SCRIPT) + ['--list']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, LIST_TOPICS_OPERATION)

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = self._base_command(KAFKA_CONSUMER_GROUPS_SCRIPT) + [
            '--timeout', '100000', '--describe', '--group', group_name]
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, DESCRIBE_CONSUMER_GROUP_OPERATION)
        return _augment_describe_output_with_time_lag(
            result, self.brokers, extra_console_consumer_args=[],
        )

    def list_consumer_groups(self) -> CommandResult:
        command = self._base_command(KAFKA_CONSUMER_GROUPS_SCRIPT) + ['--timeout', '100000', '--list']
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, LIST_CONSUMER_GROUPS_OPERATION)

    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_RUN_CLASS_SCRIPT),
                   GET_OFFSET_SHELL_CLASS, '--bootstrap-server',
                   self.brokers, '--topic', topic_name, '--time', '-1']
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, DESCRIBE_TOPIC_RECORDS_OPERATION)
        if result.success and result.value:
            pretty_value = pretty_print_kafka_record_count(result.value)
            return CommandResult(success=result.success, value=pretty_value)
        return result


class ScramKafka(Kafka):
    """
    SCRAM-SHA-512 authenticated Kafka implementation.
    Generates a client properties file with SASL_SSL + SCRAM-SHA-512 config
    and passes --command-config to all Kafka CLI commands.
    """

    def __init__(self, config):
        super().__init__(config)
        scram_config = config['scram']
        self.username = scram_config['username']
        self.password = self._resolve_password(scram_config)
        self.ca_cert_path: Optional[str] = scram_config.get('ca_cert_path')
        self._props_file = self._write_properties_file()

    @staticmethod
    def _resolve_password(scram_config: dict) -> str:
        if 'password' in scram_config and scram_config['password']:
            return scram_config['password']
        env_var = scram_config.get('password_env', 'KAFKA_SCRAM_PASSWORD')
        password = os.environ.get(env_var)
        if not password:
            raise ValueError(
                f"SCRAM password not found. Set '{env_var}' environment variable "
                f"or provide 'password' in the scram config."
            )
        return password

    def _write_properties_file(self) -> str:
        lines = [
            'security.protocol=SASL_SSL',
            'sasl.mechanism=SCRAM-SHA-512',
            f'sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required '
            f'username="{self.username}" password="{self.password}";',
        ]
        if self.ca_cert_path:
            lines.append('ssl.truststore.type=PEM')
            lines.append(f'ssl.truststore.location={self.ca_cert_path}')
        fd, path = tempfile.mkstemp(prefix='kafka-scram-', suffix='.properties')
        with os.fdopen(fd, 'w') as f:
            f.write('\n'.join(lines) + '\n')
        return path

    def _cmd_config_args(self) -> List[str]:
        return ['--command-config', self._props_file]

    def delete_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', self.brokers,
                   '--delete', '--topic', topic_name] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Delete Topic")

    def create_topic(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', self.brokers,
                   '--create', '--topic', topic_name] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "Create Topic")

    def list_topics(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_TOPICS_SCRIPT), '--bootstrap-server', self.brokers,
                   '--list'] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "List Topics")

    def describe_consumer_group(self, group_name='logging-group-default') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', self.brokers,
                   '--timeout', '100000', '--describe', '--group', group_name] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, "Describe Consumer Group")
        return _augment_describe_output_with_time_lag(
            result, self.brokers,
            extra_console_consumer_args=['--consumer.config', self._props_file],
        )

    def list_consumer_groups(self) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', self.brokers,
                   '--timeout', '100000', '--list'] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, "List Consumer Groups")

    def describe_topic_records(self, topic_name='logging-traffic-topic') -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_RUN_CLASS_SCRIPT),
                   'org.apache.kafka.tools.GetOffsetShell', '--bootstrap-server',
                   self.brokers, '--topic', topic_name, '--time', '-1'] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, "Describe Topic Records")
        if result.success and result.value:
            pretty_value = pretty_print_kafka_record_count(result.value)
            return CommandResult(success=result.success, value=pretty_value)
        return result

    def __del__(self):
        try:
            os.unlink(self._props_file)
        except (OSError, AttributeError):
            pass
