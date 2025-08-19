import logging
from typing import Optional, Union
from fastapi import HTTPException, Path, APIRouter
from pydantic import BaseModel, field_validator, ConfigDict

from console_link.api.sessions import http_safe_find_session
from console_link.models.cluster import Cluster
from console_link.models.version import Version

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

clusters_router = APIRouter(
    prefix="/clusters",
    tags=["clusters"],
)


# Define the auth models as described in the plan
class AuthBase(BaseModel):
    type: str


class NoAuth(AuthBase):
    type: str = "no_auth"


class BasicAuth(AuthBase):
    type: str = "basic_auth"
    username: str


class SigV4Auth(AuthBase):
    type: str = "sigv4_auth"
    region: str
    service: str


# Main cluster info model
class ClusterInfo(BaseModel):
    endpoint: str
    protocol: str
    enable_tls_verification: bool
    auth: Union[NoAuth, BasicAuth, SigV4Auth]
    version_override: Optional[Version] = None
    
    model_config = ConfigDict(
        json_encoders={
            Version: lambda v: str(v)
        }
    )
    
    @field_validator("version_override", mode="before")
    @classmethod
    def parse_version_override(cls, v):
        if v is None:
            return None
        if isinstance(v, Version):
            return v
        if isinstance(v, str):
            return Version.from_string(v)
        return None


def convert_cluster_to_api_model(cluster: Cluster) -> ClusterInfo:
    """
    Converts an internal Cluster model to the API ClusterInfo model.
    """
    if not cluster:
        raise HTTPException(status_code=404, detail="Cluster not found")
    
    # Extract protocol from endpoint
    protocol = "https" if cluster.endpoint.startswith("https://") else "http"
    
    if cluster.auth_type and cluster.auth_type.name == "BASIC_AUTH":
        # For security reasons, we don't return the password in the API
        auth_details = cluster.get_basic_auth_details()
        auth = BasicAuth(type="basic_auth", username=auth_details.username)
    elif cluster.auth_type and cluster.auth_type.name == "SIGV4":
        service_name, region_name = cluster._get_sigv4_details()
        auth = SigV4Auth(type="sigv4_auth", region=region_name, service=service_name)
    else:
        auth = NoAuth(type="no_auth")
    
    version_override = None
    if cluster.version:
        try:
            version_override = Version.from_string(cluster.version)
        except ValueError:
            logger.info(f"Failed to parse version string: {cluster.version}")
            raise HTTPException(status_code=400, detail=f"Failed to parse version string: {cluster.version}")
    
    # Create and return the ClusterInfo model
    return ClusterInfo(
        endpoint=cluster.endpoint,
        protocol=protocol,
        enable_tls_verification=not cluster.allow_insecure,
        auth=auth,
        version_override=version_override,
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
