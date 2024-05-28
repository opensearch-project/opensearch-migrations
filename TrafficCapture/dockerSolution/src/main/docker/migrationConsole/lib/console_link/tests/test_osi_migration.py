from pathlib import Path
import unittest
import os

from console_link.models.osi_utils import construct_pipeline_config, InvalidAuthParameters
from console_link.models.cluster import AuthMethod

PIPELINE_TEMPLATE_PATH = f"{Path(__file__).parents[3]}/osiPipelineTemplate.yaml"
TEST_RESOURCES_PATH = f"{Path(__file__).parents[1]}/tests/resources"

# These values should map to static template values in the resources directory
SOURCE_ENDPOINT = 'https://vpc-test-123.com'
TARGET_ENDPOINT = 'https://vpc-test-456.com'
PIPELINE_ROLE_ARN = 'arn=arn:aws:iam::123456789012:role/OSMigrations-aws-integ-us--osisPipelineRole123'
AWS_REGION = 'us-west-2'
SECRET_NAME = 'unit-test-secret'
INDEX_INCLUSION_RULE_1 = 'index*'
INDEX_INCLUSION_RULE_2 = '.*'
INDEX_INCLUSION_RULE_3 = 'sam[a-z]+'


class TestOSIMigration(unittest.TestCase):
    def test_construct_config_sigv4_source_and_sigv4_target(self):
        cwd = os.getcwd()
        print(cwd)
        generated_config = construct_pipeline_config(
            pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
            source_endpoint=SOURCE_ENDPOINT,
            target_endpoint=TARGET_ENDPOINT,
            source_auth_type=AuthMethod.SIGV4,
            target_auth_type=AuthMethod.SIGV4,
            pipeline_role_arn=PIPELINE_ROLE_ARN,
            aws_region=AWS_REGION)
        with open(f"{TEST_RESOURCES_PATH}/sigv4SourceAndSigv4Target.yaml", "r") as expected_file:
            expected_file_contents = expected_file.read()
        self.assertEqual(generated_config, expected_file_contents)

    def test_construct_config_basic_auth_source_and_sigv4_target(self):
        generated_config = construct_pipeline_config(
            pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
            source_endpoint=SOURCE_ENDPOINT,
            target_endpoint=TARGET_ENDPOINT,
            source_auth_type=AuthMethod.BASIC_AUTH,
            source_auth_secret=SECRET_NAME,
            target_auth_type=AuthMethod.SIGV4,
            pipeline_role_arn=PIPELINE_ROLE_ARN,
            aws_region=AWS_REGION)
        with open(f"{TEST_RESOURCES_PATH}/basicAuthSourceAndSigv4Target.yaml", "r") as expected_file:
            expected_file_contents = expected_file.read()
        self.assertEqual(generated_config, expected_file_contents)

    def test_construct_config_basic_auth_source_with_single_index_inclusion_rules(self):
        generated_config = construct_pipeline_config(
            pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
            source_endpoint=SOURCE_ENDPOINT,
            target_endpoint=TARGET_ENDPOINT,
            source_auth_type=AuthMethod.BASIC_AUTH,
            source_auth_secret=SECRET_NAME,
            include_index_regex_list=[INDEX_INCLUSION_RULE_1],
            target_auth_type=AuthMethod.SIGV4,
            pipeline_role_arn=PIPELINE_ROLE_ARN,
            aws_region=AWS_REGION)
        with open(f"{TEST_RESOURCES_PATH}/basicAuthSourceWithSingleIndexInclusionRule.yaml", "r") as expected_file:
            expected_file_contents = expected_file.read()
        self.assertEqual(generated_config, expected_file_contents)

    def test_construct_config_basic_auth_source_with_multiple_index_inclusion_rules(self):
        generated_config = construct_pipeline_config(
            pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
            source_endpoint=SOURCE_ENDPOINT,
            target_endpoint=TARGET_ENDPOINT,
            source_auth_type=AuthMethod.BASIC_AUTH,
            source_auth_secret=SECRET_NAME,
            include_index_regex_list=[INDEX_INCLUSION_RULE_1, INDEX_INCLUSION_RULE_2, INDEX_INCLUSION_RULE_3],
            target_auth_type=AuthMethod.SIGV4,
            pipeline_role_arn=PIPELINE_ROLE_ARN,
            aws_region=AWS_REGION)
        with open(f"{TEST_RESOURCES_PATH}/basicAuthSourceWithMultipleIndexInclusionRule.yaml", "r") as expected_file:
            expected_file_contents = expected_file.read()
        self.assertEqual(generated_config, expected_file_contents)

    def test_construct_config_throws_error_if_secret_not_provided_for_basic_auth(self):
        with self.assertRaises(InvalidAuthParameters):
            construct_pipeline_config(
                pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
                source_endpoint=SOURCE_ENDPOINT,
                target_endpoint=TARGET_ENDPOINT,
                source_auth_type=AuthMethod.BASIC_AUTH,
                target_auth_type=AuthMethod.SIGV4,
                pipeline_role_arn=PIPELINE_ROLE_ARN,
                aws_region=AWS_REGION)

    def test_construct_config_throws_error_if_pipeline_role_not_provided_for_sigv4(self):
        with self.assertRaises(InvalidAuthParameters):
            construct_pipeline_config(
                pipeline_config_file_path=PIPELINE_TEMPLATE_PATH,
                source_endpoint=SOURCE_ENDPOINT,
                target_endpoint=TARGET_ENDPOINT,
                source_auth_type=AuthMethod.SIGV4,
                target_auth_type=AuthMethod.SIGV4,
                aws_region=AWS_REGION)


if __name__ == '__main__':
    unittest.main()
