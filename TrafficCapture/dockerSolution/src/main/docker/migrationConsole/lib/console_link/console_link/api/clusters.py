import logging
from fastapi import HTTPException, APIRouter

from console_link.api.sessions import http_safe_find_session
from console_link.models.cluster import BasicAuth, BasicAuthArn, Cluster, ClusterInfo, NoAuth, SigV4Auth

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

clusters_router = APIRouter(
    prefix="/clusters",
    tags=["clusters"],
)


def convert_cluster_to_api_model(cluster: Cluster) -> ClusterInfo:
    """
    Converts an internal Cluster model to the API ClusterInfo model.
    """
    if not cluster:
        raise HTTPException(status_code=404, detail="Cluster not found")
    
    # Extract protocol from endpoint
    protocol = "https" if cluster.endpoint.startswith("https://") else "http"
    
    if cluster.auth_type and cluster.auth_type.name == "BASIC_AUTH":
        if cluster.auth_details and "user_secret_arn" in cluster.auth_details:
            auth_details = cluster.auth_details["user_secret_arn"]
            auth = BasicAuthArn(type="basic_auth_arn", user_secret_arn=auth_details["user_secret_arn"])
        else:
            auth_details = cluster.get_basic_auth_details()
            auth = BasicAuth(type="basic_auth", username=auth_details.username, password=auth_details.password)
    elif cluster.auth_type and cluster.auth_type.name == "SIGV4":
        service_name, region_name = cluster._get_sigv4_details()
        auth = SigV4Auth(type="sigv4_auth", region=region_name, service=service_name)
    else:
        auth = NoAuth(type="no_auth")
    
    # Create and return the ClusterInfo model
    return ClusterInfo(
        endpoint=cluster.endpoint,
        protocol=protocol,
        enable_tls_verification=not cluster.allow_insecure,
        auth=auth,
        version_override=cluster.version,
    )


@clusters_router.get("/source", response_model=ClusterInfo, operation_id="clusterSource")
def get_source_cluster(session_name: str):
    session = http_safe_find_session(session_name)
    
    if not session.env or not session.env.source_cluster:
        raise HTTPException(status_code=404, detail="Source cluster not defined for this session")
    
    return convert_cluster_to_api_model(session.env.source_cluster)


@clusters_router.get("/target", response_model=ClusterInfo, operation_id="clusterTarget")
def get_target_cluster(session_name: str):
    session = http_safe_find_session(session_name)
    if not session.env or not session.env.target_cluster:
        raise HTTPException(status_code=404, detail="Target cluster not defined for this session")
    
    return convert_cluster_to_api_model(session.env.target_cluster)
