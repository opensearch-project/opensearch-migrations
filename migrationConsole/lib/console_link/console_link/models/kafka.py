import subprocess
import os
import shutil
import tempfile
from pathlib import Path
from typing import List, Optional

from cerberus import Validator
import logging
from abc import ABC, abstractmethod
from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)

KAFKA_TOPICS_SCRIPT = 'kafka-topics.sh'
KAFKA_CONSUMER_GROUPS_SCRIPT = 'kafka-consumer-groups.sh'
KAFKA_RUN_CLASS_SCRIPT = 'kafka-run-class.sh'
MSK_IAM_AUTH_PROPERTIES = 'msk-iam-auth.properties'
DELETE_TOPIC_OPERATION = "Delete Topic"
CREATE_TOPIC_OPERATION = "Create Topic"
LIST_TOPICS_OPERATION = "List Topics"
DESCRIBE_CONSUMER_GROUP_OPERATION = "Describe Consumer Group"
LIST_CONSUMER_GROUPS_OPERATION = "List Consumer Groups"
DESCRIBE_TOPIC_RECORDS_OPERATION = "Describe Topic Records"
DESCRIBE_REPLAY_PROGRESS_OPERATION = "Describe Replay Progress"
RESET_CONSUMER_GROUP_OFFSETS_OPERATION = "Reset Consumer Group Offsets"
GET_OFFSET_SHELL_CLASS = 'org.apache.kafka.tools.GetOffsetShell'

DISABLE_JAVA_PERFDATA_OPT = "-XX:-UsePerfData"
CONTAINER_KAFKA_UTILS_BIN = Path('/root/kafkaUtils/bin/kafkaUtils')

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


def resolve_kafka_utils() -> str:
    configured = os.getenv('KAFKA_UTILS_BIN')
    if configured:
        return configured

    on_path = shutil.which('kafkaUtils')
    if on_path:
        return on_path

    model_dir = Path(__file__).resolve().parent
    repo_root = model_dir.parents[4]
    candidates = [
        repo_root / 'libraries' / 'kafkaUtils' / 'build' / 'install' / 'kafkaUtils' / 'bin' / 'kafkaUtils',
        CONTAINER_KAFKA_UTILS_BIN,
    ]
    for candidate in candidates:
        if candidate.exists():
            return str(candidate)

    raise FileNotFoundError(
        "Could not find kafkaUtils. Set KAFKA_UTILS_BIN, run "
        "'./gradlew :libraries:kafkaUtils:installDist', or use the migration-console image."
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
        cmd_output = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=True,
            env=_kafka_command_env(),
        )
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


def _kafka_command_env() -> dict[str, str]:
    env = os.environ.copy()
    kafka_opts = env.get("KAFKA_OPTS", "")
    if DISABLE_JAVA_PERFDATA_OPT not in kafka_opts.split():
        env["KAFKA_OPTS"] = f"{kafka_opts} {DISABLE_JAVA_PERFDATA_OPT}".strip()
    return env


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


_DESCRIBE_HEADER_TOKENS = (
    'GROUP', 'TOPIC', 'PARTITION', 'CURRENT-OFFSET',
    'LOG-END-OFFSET', 'LAG',
)


def parse_consumer_group_describe(text: str) -> List[dict]:
    """Parse the native `kafka-consumer-groups.sh --describe` table."""
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
    if all(header in tokens for header in _DESCRIBE_HEADER_TOKENS):
        return {header: tokens.index(header) for header in _DESCRIBE_HEADER_TOKENS}
    return None


def _parse_describe_row(tokens: List[str], header_indices: dict) -> Optional[dict]:
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


def _augment_describe_output_with_replay_progress(
    base_result: CommandResult,
    group_name: str,
    brokers: str,
    kafka_property_file: Optional[str] = None,
) -> CommandResult:
    """Append replay-watermark diagnostics without consuming traffic values."""
    if not base_result.success or not base_result.value:
        return base_result

    try:
        command = [
            resolve_kafka_utils(),
            '--consumerGroupProgress', group_name,
            '--kafkaBrokers', brokers,
            '--kafkaClientId', 'migration-console-replay-progress',
        ]
        if kafka_property_file:
            command.extend(['--kafkaPropertyFile', kafka_property_file])
        progress_result = get_result_for_command(
            command,
            DESCRIBE_REPLAY_PROGRESS_OPERATION,
        )
    except Exception as exc:  # noqa: BLE001 — diagnostics must not mask native group state
        logger.warning("Failed to query replay progress: %s", exc, exc_info=True)
        return base_result
    if not progress_result.success or not progress_result.value:
        return base_result
    return CommandResult(
        success=True,
        value=base_result.value.rstrip() + '\n\n' + progress_result.value,
    )


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
    def list_topics(self) -> CommandResult:
        pass

    @abstractmethod
    def describe_consumer_group(
        self,
        group_name='logging-group-default',
        *,
        include_time_lag=True,
    ) -> CommandResult:
        pass

    @abstractmethod
    def list_consumer_groups(self) -> CommandResult:
        pass

    @abstractmethod
    def reset_consumer_group_offsets_to_latest(
        self,
        group_name: str,
        topic_name='logging-traffic-topic',
    ) -> CommandResult:
        """Point an inactive consumer group at the end of every topic partition."""
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

    def reset_consumer_group_offsets_to_latest(
        self,
        group_name: str,
        topic_name='logging-traffic-topic',
    ) -> CommandResult:
        command = [
            resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT),
            '--bootstrap-server', f'{self.brokers}',
            '--group', group_name,
            '--topic', topic_name,
            '--reset-offsets',
            '--to-latest',
            '--execute',
            '--command-config', resolve_msk_auth_config(),
        ]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, RESET_CONSUMER_GROUP_OFFSETS_OPERATION)

    def describe_consumer_group(
        self,
        group_name='logging-group-default',
        *,
        include_time_lag=True,
    ) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', f'{self.brokers}',
                   '--timeout', '100000', '--describe', '--group', f'{group_name}',
                   '--command-config', resolve_msk_auth_config()]
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, DESCRIBE_CONSUMER_GROUP_OPERATION)
        if not include_time_lag:
            return result
        return _augment_describe_output_with_replay_progress(
            result,
            group_name,
            self.brokers,
            kafka_property_file=resolve_msk_auth_config(),
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

    def reset_consumer_group_offsets_to_latest(
        self,
        group_name: str,
        topic_name='logging-traffic-topic',
    ) -> CommandResult:
        command = self._base_command(KAFKA_CONSUMER_GROUPS_SCRIPT) + [
            '--group', group_name,
            '--topic', topic_name,
            '--reset-offsets',
            '--to-latest',
            '--execute',
        ]
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, RESET_CONSUMER_GROUP_OFFSETS_OPERATION)

    def describe_consumer_group(
        self,
        group_name='logging-group-default',
        *,
        include_time_lag=True,
    ) -> CommandResult:
        command = self._base_command(KAFKA_CONSUMER_GROUPS_SCRIPT) + [
            '--timeout', '100000', '--describe', '--group', group_name]
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, DESCRIBE_CONSUMER_GROUP_OPERATION)
        if not include_time_lag:
            return result
        return _augment_describe_output_with_replay_progress(
            result,
            group_name,
            self.brokers,
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

    def __init__(self, config, password: Optional[str] = None):
        super().__init__(config)
        scram_config = config['scram']
        self.username = scram_config['username']
        self.password = password if password else self._resolve_password(scram_config)
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

    def reset_consumer_group_offsets_to_latest(
        self,
        group_name: str,
        topic_name='logging-traffic-topic',
    ) -> CommandResult:
        command = [
            resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT),
            '--bootstrap-server', self.brokers,
            '--group', group_name,
            '--topic', topic_name,
            '--reset-offsets',
            '--to-latest',
            '--execute',
        ] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        return get_result_for_command(command, RESET_CONSUMER_GROUP_OFFSETS_OPERATION)

    def describe_consumer_group(
        self,
        group_name='logging-group-default',
        *,
        include_time_lag=True,
    ) -> CommandResult:
        command = [resolve_kafka_tool(KAFKA_CONSUMER_GROUPS_SCRIPT), '--bootstrap-server', self.brokers,
                   '--timeout', '100000', '--describe', '--group', group_name] + self._cmd_config_args()
        logger.info(f"Executing command: {command}")
        result = get_result_for_command(command, "Describe Consumer Group")
        if not include_time_lag:
            return result
        return _augment_describe_output_with_replay_progress(
            result,
            group_name,
            self.brokers,
            kafka_property_file=self._props_file,
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
