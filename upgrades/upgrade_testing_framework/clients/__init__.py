# Pull in all our client versions
from upgrade_testing_framework.clients.rest_client_base import RESTClientBase
from upgrade_testing_framework.clients.rest_client_default import RESTClientDefault

# Now do regular imports
from upgrade_testing_framework.core.versions_engine import EngineVersion

def get_rest_client(engine_version: EngineVersion) -> RESTClientBase:
    # Only have one for now; update when that's no longer true :-)
    return RESTClientDefault()