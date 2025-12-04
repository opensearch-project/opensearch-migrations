import pytest
from console_link.models.utils import map_cluster_from_workflow_config


class TestMapFromWorkflowConfig:
    """Test suite for mapping from workflow config format to services.yaml format."""

    def test_minimal_valid_config_with_endpoint_only(self):
        """Test mapping with only required endpoint field."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200"
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "no_auth": None
        }
        assert result == expected

    def test_endpoint_with_allow_insecure_true(self):
        """Test mapping allowInsecure field to allow_insecure."""
        workflow_config = {
            "endpoint": "https://opensearch-cluster:9200",
            "allowInsecure": True
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://opensearch-cluster:9200",
            "allow_insecure": True,
            "no_auth": None
        }
        assert result == expected

    def test_endpoint_with_allow_insecure_false(self):
        """Test mapping allowInsecure field set to false."""
        workflow_config = {
            "endpoint": "https://opensearch-cluster:9200",
            "allowInsecure": False
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://opensearch-cluster:9200",
            "allow_insecure": False,
            "no_auth": None
        }
        assert result == expected

    def test_basic_auth_with_username_and_password(self):
        """Test mapping basic auth with direct username and password."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "username": "admin",
                    "password": "admin"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allow_insecure": True,
            "basic_auth": {
                "username": "admin",
                "password": "admin"
            }
        }
        assert result == expected

    def test_basic_auth_with_secret_name_only(self):
        """Test mapping basic auth with only secretName."""
        workflow_config = {
            "endpoint": "https://opensearch-cluster-master-headless:9200",
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "secretName": "target1-creds"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://opensearch-cluster-master-headless:9200",
            "allow_insecure": True,
            "basic_auth": {
                "k8s_secret_name": "target1-creds"
            }
        }
        assert result == expected

    def test_basic_auth_secret_name_overrides_username_password(self):
        """Test that secretName takes precedence over username/password when both are provided."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "secretName": "source1-creds",
                    "username": "admin",
                    "password": "admin"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allow_insecure": True,
            "basic_auth": {
                "k8s_secret_name": "source1-creds"
            }
        }
        assert result == expected

    def test_sigv4_auth_with_region_and_service(self):
        """Test mapping sigv4 auth with both region and service."""
        workflow_config = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "allowInsecure": False,
            "authConfig": {
                "sigv4": {
                    "region": "us-east-1",
                    "service": "aoss"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "allow_insecure": False,
            "sigv4": {
                "region": "us-east-1",
                "service": "aoss"
            }
        }
        assert result == expected

    def test_sigv4_auth_with_region_only(self):
        """Test mapping sigv4 auth with only region (service should default)."""
        workflow_config = {
            "endpoint": "https://search-mydomain.us-west-2.es.amazonaws.com",
            "authConfig": {
                "sigv4": {
                    "region": "us-west-2"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://search-mydomain.us-west-2.es.amazonaws.com",
            "sigv4": {
                "region": "us-west-2"
            }
        }
        assert result == expected

    def test_sigv4_auth_with_service_only(self):
        """Test mapping sigv4 auth with only service."""
        workflow_config = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "authConfig": {
                "sigv4": {
                    "service": "es"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "sigv4": {
                "service": "es"
            }
        }
        assert result == expected

    def test_sigv4_auth_empty_config(self):
        """Test mapping sigv4 auth with empty config object."""
        workflow_config = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "authConfig": {
                "sigv4": {}
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "sigv4": None
        }
        assert result == expected

    def test_mtls_auth_not_supported_defaults_raises_a_not_implemented_error(self):
        """Test that mTLS auth config (not implemented) raises an error"""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allowInsecure": True,
            "authConfig": {
                "mtls": {
                    "caCert": "-----BEGIN CERTIFICATE-----...",
                    "clientSecretName": "client-cert-secret"
                }
            }
        }
        # This should actually raise a not_implemented error
        with pytest.raises(NotImplementedError):
            map_cluster_from_workflow_config(workflow_config)

    def test_unknown_auth_type_fails(self):
        """Test that unknown auth types default to no_auth."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "authConfig": {
                "oauth": {
                    "clientId": "my-client-id",
                    "clientSecret": "my-client-secret"
                }
            }
        }
        # expect to raise an error
        with pytest.raises(ValueError):
            map_cluster_from_workflow_config(workflow_config)

    def test_null_auth_config_defaults_to_no_auth(self):
        """Test that null authConfig defaults to no_auth."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "authConfig": None
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "no_auth": None
        }
        assert result == expected

    def test_missing_auth_config_defaults_to_no_auth(self):
        """Test that missing authConfig defaults to no_auth."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allowInsecure": True
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allow_insecure": True,
            "no_auth": None
        }
        assert result == expected

    def test_version_field_is_ignored(self):
        """Test that version field in workflow config is ignored in mapping."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "version": "ES 7.10",
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "username": "admin",
                    "password": "password123"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allow_insecure": True,
            "basic_auth": {
                "username": "admin",
                "password": "password123"
            }
        }
        assert result == expected
        assert "version" not in result

    def test_additional_fields_are_ignored(self):
        """Test that additional fields like snapshotRepo and proxy are ignored."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "secretName": "source1-creds",
                    "username": "admin",
                    "password": "admin"
                }
            },
            "snapshotRepo": {
                "awsRegion": "us-east-2",
                "endpoint": "localstack://localstack.ma.svc.cluster.local:4566",
                "s3RepoPathUri": "s3://migrations-default-123456789012-dev-us-east-2"
            },
            "proxy": {}
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allow_insecure": True,
            "basic_auth": {
                "k8s_secret_name": "source1-creds"
            }
        }
        assert result == expected
        assert "snapshotRepo" not in result
        assert "proxy" not in result

    # Error condition tests
    def test_missing_endpoint_raises_value_error(self):
        """Test that missing endpoint field raises ValueError."""
        workflow_config = {
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "username": "admin",
                    "password": "password"
                }
            }
        }

        with pytest.raises(ValueError,
                           match="The cluster data from the workflow config does not contain an 'endpoint' field"):
            map_cluster_from_workflow_config(workflow_config)

    def test_empty_config_raises_value_error(self):
        """Test that empty config raises ValueError."""
        workflow_config = {}

        with pytest.raises(ValueError,
                           match="The cluster data from the workflow config does not contain an 'endpoint' field"):
            map_cluster_from_workflow_config(workflow_config)

    def test_non_dict_auth_config_defaults_to_no_auth(self):
        """Test that non-dict authConfig defaults to no_auth."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "authConfig": "invalid-auth-config"
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "no_auth": None
        }
        assert result == expected

    def test_basic_auth_non_dict_raises_value_error(self):
        """Test that non-dict basic auth config raises ValueError."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "authConfig": {
                "basic": "invalid-basic-config"
            }
        }

        with pytest.raises(ValueError, match="authConfig.basic must be a dictionary"):
            map_cluster_from_workflow_config(workflow_config)

    def test_basic_auth_empty_dict_raises_value_error(self):
        """Test that empty basic auth config raises ValueError."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "authConfig": {
                "basic": {}
            }
        }

        with pytest.raises(ValueError, match="authConfig.basic must contain either a secret or username/password"):
            map_cluster_from_workflow_config(workflow_config)

    def test_basic_auth_username_only_raises_value_error(self):
        """Test that basic auth with only username raises ValueError."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "authConfig": {
                "basic": {
                    "username": "admin"
                }
            }
        }

        with pytest.raises(ValueError, match="authConfig.basic must contain either a secret or username/password"):
            map_cluster_from_workflow_config(workflow_config)

    def test_basic_auth_password_only_raises_value_error(self):
        """Test that basic auth with only password raises ValueError."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "authConfig": {
                "basic": {
                    "password": "password123"
                }
            }
        }

        with pytest.raises(ValueError, match="authConfig.basic must contain either a secret or username/password"):
            map_cluster_from_workflow_config(workflow_config)

    # Edge cases for different endpoint formats
    def test_http_endpoint_mapping(self):
        """Test mapping with HTTP (non-secure) endpoint."""
        workflow_config = {
            "endpoint": "http://elasticsearch-master:9200"
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "http://elasticsearch-master:9200",
            "no_auth": None
        }
        assert result == expected

    def test_endpoint_with_port_mapping(self):
        """Test mapping with endpoint including port number."""
        workflow_config = {
            "endpoint": "https://my-cluster.example.com:9243",
            "allowInsecure": False
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://my-cluster.example.com:9243",
            "allow_insecure": False,
            "no_auth": None
        }
        assert result == expected

    def test_endpoint_with_path_mapping(self):
        """Test mapping with endpoint including path."""
        workflow_config = {
            "endpoint": "https://proxy.example.com/elasticsearch"
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://proxy.example.com/elasticsearch",
            "no_auth": None
        }
        assert result == expected

    # Complex scenario tests
    def test_complete_elasticsearch_source_config(self):
        """Test mapping complete elasticsearch source configuration from example."""
        workflow_config = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "secretName": "source1-creds",
                    "username": "admin",
                    "password": "admin"
                }
            },
            "snapshotRepo": {
                "awsRegion": "us-east-2",
                "endpoint": "localstack://localstack.ma.svc.cluster.local:4566",
                "s3RepoPathUri": "s3://migrations-default-123456789012-dev-us-east-2"
            },
            "proxy": {}
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://elasticsearch-master-headless:9200",
            "allow_insecure": True,
            "basic_auth": {
                "k8s_secret_name": "source1-creds"
            }
        }
        assert result == expected

    def test_complete_opensearch_target_config(self):
        """Test mapping complete opensearch target configuration from example."""
        workflow_config = {
            "endpoint": "https://opensearch-cluster-master-headless:9200",
            "allowInsecure": True,
            "authConfig": {
                "basic": {
                    "secretName": "target1-creds",
                    "username": "admin",
                    "password": "admin"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://opensearch-cluster-master-headless:9200",
            "allow_insecure": True,
            "basic_auth": {
                "k8s_secret_name": "target1-creds"
            }
        }
        assert result == expected

    def test_aws_elasticsearch_service_config(self):
        """Test mapping AWS Elasticsearch Service configuration."""
        workflow_config = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "allowInsecure": False,
            "authConfig": {
                "sigv4": {
                    "region": "us-east-1",
                    "service": "es"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://search-mydomain.us-east-1.es.amazonaws.com",
            "allow_insecure": False,
            "sigv4": {
                "region": "us-east-1",
                "service": "es"
            }
        }
        assert result == expected

    def test_aws_opensearch_serverless_config(self):
        """Test mapping AWS OpenSearch Serverless configuration."""
        workflow_config = {
            "endpoint": "https://my-collection.us-west-2.aoss.amazonaws.com",
            "authConfig": {
                "sigv4": {
                    "region": "us-west-2",
                    "service": "aoss"
                }
            }
        }

        result = map_cluster_from_workflow_config(workflow_config)

        expected = {
            "endpoint": "https://my-collection.us-west-2.aoss.amazonaws.com",
            "sigv4": {
                "region": "us-west-2",
                "service": "aoss"
            }
        }
        assert result == expected
