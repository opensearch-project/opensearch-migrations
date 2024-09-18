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
