import json
import pytest

from upgrade_testing_framework.core.expectation import Expectation, load_knowledge_base
import upgrade_testing_framework.core.expectation as expectations


TEST_EXPECTATION = {
    "id": "expectation-id",
    "description": "expectation description",
}

TEST_EXPECTATION_WITH_VERSION = TEST_EXPECTATION | {
    "versions": [{
        "gt": "ES_7_10_2",
        "lte": "OS_1_3_6"
    }]
}

TEST_EXPECTATION_COMPLEX_VERSION = TEST_EXPECTATION | {
    "versions": [
        {
            "gte": "OS_2_0_0"
        },
        {
            "gt": "ES_2_5_0",
            "lt": "ES_7_0_0"
        }
    ]
}

TEST_EXPECTATION_MISSING_ID = {
    "description": "hello world",
    "versions": [{
        "gt": "OS_1_0_0"
    }]
}


@pytest.fixture
def valid_knowledge_base_dir(tmpdir):
    test_kb_dir = tmpdir / "knowledge_base"
    test_kb_dir.mkdir()
    expectation_file_1 = test_kb_dir / "ex1.json"
    expectation_file_2 = test_kb_dir / "ex2.json"
    expectation_file_1.write(json.dumps(TEST_EXPECTATION))
    expectation_file_2.write(json.dumps([TEST_EXPECTATION_WITH_VERSION, TEST_EXPECTATION_COMPLEX_VERSION]))
    return test_kb_dir


@pytest.fixture
def knowledge_base_dir_with_non_parsable_file(tmpdir):
    test_kb_dir = tmpdir / "knowledge_base"
    test_kb_dir.mkdir()
    expectation_file = test_kb_dir / "ex.json"
    expectation_file.write("{[}")
    return test_kb_dir


@pytest.fixture
def knowledge_base_dir_with_empty_file(tmpdir):
    test_kb_dir = tmpdir / "knowledge_base"
    test_kb_dir.mkdir()
    expectation_file = test_kb_dir / "ex.json"
    expectation_file.write(json.dumps({}))
    return test_kb_dir


@pytest.fixture
def knowledge_base_dir_with_expectation_missing_id(tmpdir):
    test_kb_dir = tmpdir / "knowledge_base"
    test_kb_dir.mkdir()
    expectation_file = test_kb_dir / "ex.json"
    expectation_file.write(json.dumps(TEST_EXPECTATION_MISSING_ID))
    return test_kb_dir


@pytest.fixture
def knowledge_base_not_a_dir(tmpdir):
    test_kb = tmpdir / "knowledge_base"
    test_kb.write("")
    return test_kb


@pytest.fixture
def knowledge_base_doesnt_exist(tmpdir):
    test_kb = tmpdir / "knowledge_base"
    test_kb.write("")
    return test_kb


def test_WHEN_load_knowledge_base_called_AND_valid_THEN_returns_it(valid_knowledge_base_dir):
    actual_value = load_knowledge_base(valid_knowledge_base_dir)

    # Check the results
    expected_value = [Expectation(TEST_EXPECTATION),
                      Expectation(TEST_EXPECTATION_WITH_VERSION),
                      Expectation(TEST_EXPECTATION_COMPLEX_VERSION)]

    assert expected_value == actual_value


def test_WHEN_load_knowledge_base_called_AND_non_readable_file_THEN_raises(valid_knowledge_base_dir):
    # Set up our test
    unreadable_file = valid_knowledge_base_dir / "unreadable.json"
    unreadable_file.write("")
    unreadable_file.chmod(0o200)  # Make the file unreadable

    # Run our test
    with pytest.raises(expectations.ExpectationCantReadFileException):
        load_knowledge_base(valid_knowledge_base_dir)


def test_WHEN_load_knowledge_base_called_AND_non_valid_json_THEN_raises(knowledge_base_dir_with_non_parsable_file):
    with pytest.raises(expectations.ExpectationFileNotJSONException):
        load_knowledge_base(knowledge_base_dir_with_non_parsable_file)


def test_WHEN_load_knowledge_base_called_AND_no_expectation_id_THEN_raises(
        knowledge_base_dir_with_expectation_missing_id):
    with pytest.raises(expectations.ExpectationMissingIdException):
        load_knowledge_base(knowledge_base_dir_with_expectation_missing_id)


def test_WHEN_load_knowledge_base_called_AND_not_a_dir_THEN_raises(knowledge_base_not_a_dir):
    with pytest.raises(expectations.KnowledgeBaseDirectoryDoesntExistException):
        load_knowledge_base(knowledge_base_not_a_dir)


def test_WHEN_load_knowledge_base_called_AND_doesnt_exist_THEN_raises(knowledge_base_doesnt_exist):
    with pytest.raises(expectations.KnowledgeBaseDirectoryDoesntExistException):
        load_knowledge_base(knowledge_base_doesnt_exist)


def test_WHEN_no_version_filter_THEN_matches_all():
    expectation = Expectation(TEST_EXPECTATION)
    for version in ["ES_2_5_0", "ES_7_10_2", "OS_1_0_0", "OS_1_3_6", "OS_2_0_0"]:
        assert expectation.applies_to_version(version)


def test_WHEN_version_filter_THEN_matches_correctly():
    expectation = Expectation(TEST_EXPECTATION_WITH_VERSION)
    for version in ["ES_7_10_3", "OS_1_0_0", "OS_1_3_6"]:
        assert expectation.applies_to_version(version)
    for version in ["ES_2_5_0", "ES_7_10_2", "OS_1_3_7", "OS_2_4_0"]:
        assert not expectation.applies_to_version(version)


def test_WHEN_complex_version_filter_THEN_matches_correctly():
    expectation = Expectation(TEST_EXPECTATION_COMPLEX_VERSION)
    for version in ["ES_2_5_1", "ES_6_10_2", "OS_2_0_0", "OS_2_4_0"]:
        assert expectation.applies_to_version(version)
    for version in ["ES_2_5_0", "ES_7_0_0", "ES_7_10_3", "OS_1_0_0"]:
        assert not expectation.applies_to_version(version)
