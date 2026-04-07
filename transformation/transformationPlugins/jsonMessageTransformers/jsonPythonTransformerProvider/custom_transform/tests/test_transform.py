"""Tests for the custom document transformation."""
from custom_transform.transform import DocTransformConfig, create_transform


def test_index_rewrite():
    config = DocTransformConfig(
        index_rewrites=[{
            "source_prefix": "logs-2024",
            "target_prefix": "migrated-logs-2024"
        }]
    )
    transform = create_transform(config)

    docs = [{
        "operation": {"_index": "logs-2024.01", "_id": "1"},
        "document": {"message": "hello"}
    }]

    result = transform(docs)
    assert result[0]["operation"]["_index"] == "migrated-logs-2024.01"
    assert result[0]["operation"]["_id"] == "1"
    assert result[0]["document"]["message"] == "hello"


def test_add_fields():
    config = DocTransformConfig(add_fields={"migrated": True, "version": 2})
    transform = create_transform(config)

    docs = [{
        "operation": {"_index": "idx", "_id": "1"},
        "document": {"field": "value"}
    }]

    result = transform(docs)
    assert result[0]["document"]["migrated"] is True
    assert result[0]["document"]["version"] == 2
    assert result[0]["document"]["field"] == "value"


def test_no_op_with_empty_config():
    config = DocTransformConfig()
    transform = create_transform(config)

    docs = [{
        "operation": {"_index": "idx", "_id": "1"},
        "document": {"field": "value"}
    }]

    result = transform(docs)
    assert result == docs


def test_rewrite_only_matches_prefix():
    config = DocTransformConfig(
        index_rewrites=[{
            "source_prefix": "logs-",
            "target_prefix": "new-logs-"
        }]
    )
    transform = create_transform(config)

    docs = [
        {
            "operation": {"_index": "logs-2024", "_id": "1"},
            "document": {}
        },
        {
            "operation": {"_index": "metrics-2024", "_id": "2"},
            "document": {}
        }
    ]

    result = transform(docs)
    assert result[0]["operation"]["_index"] == "new-logs-2024"
    assert result[1]["operation"]["_index"] == "metrics-2024"


def test_config_validation_rejects_bad_input():
    try:
        DocTransformConfig(index_rewrites=[{"bad_key": "value"}])
        assert False, "Should have raised validation error"
    except Exception:
        pass
