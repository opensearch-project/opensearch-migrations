from console_link.models.cluster import AuthMethod, Cluster
from integ_test.test_cases.cdc_base import PROXY_ENDPOINT, make_proxy_cluster


def test_make_proxy_cluster_signs_sigv4_requests_with_source_endpoint():
    source = Cluster({
        "endpoint": "https://search-source.us-east-1.es.amazonaws.com",
        "allow_insecure": True,
        "version": "ES_7.10",
        "sigv4": {
            "region": "us-east-1",
            "service": "es",
        },
    })

    proxy = make_proxy_cluster(source)

    assert proxy.endpoint == PROXY_ENDPOINT
    assert proxy.allow_insecure
    assert proxy.auth_type == AuthMethod.SIGV4
    assert proxy.config["sigv4_signing_endpoint"] == source.endpoint
