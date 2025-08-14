import json
from fastapi import HTTPException, Body, APIRouter
from pydantic import BaseModel
from typing import List, Optional, Dict, Any
import logging
from datetime import datetime, timezone

from console_link.models.command_result import CommandResult
from console_link.api.sessions import find_session, existence_check, StepState
from tinydb import Query, TinyDB

metadata_router = APIRouter(
    prefix="/metadata",
    tags=["metadata"],
)

logger = logging.getLogger(__name__)


# Initialize TinyDB for metadata results
db = TinyDB("metadata_results_db.json")
# TODO: https://opensearch.atlassian.net/browse/MIGRATIONS-2666
metadata_results_table = db.table("metadata_results")


class MetadataRequest(BaseModel):
    """Request model for metadata migration operations."""
    index_allowlist: Optional[List[str]] = None
    index_template_allowlist: Optional[List[str]] = None
    component_template_allowlist: Optional[List[str]] = None
    dry_run: bool = True


class ClusterInfo(BaseModel):
    """Cluster information model."""
    type: Optional[str] = None
    version: Optional[str] = None
    uri: Optional[str] = None
    protocol: Optional[str] = None
    insecure: Optional[bool] = None
    awsSpecificAuthentication: Optional[bool] = None
    disableCompression: Optional[bool] = None
    localRepository: Optional[str] = None


class ClustersInfo(BaseModel):
    """Clusters information model."""
    source: ClusterInfo
    target: ClusterInfo


class FailureInfo(BaseModel):
    """Failure information model."""
    type: Optional[str] = None
    message: Optional[str] = None
    fatal: Optional[bool] = None


class ItemResult(BaseModel):
    """Individual item result model."""
    name: str
    successful: bool
    failure: Optional[FailureInfo] = None


class ItemsInfo(BaseModel):
    """Items migration information model."""
    dryRun: bool
    indexTemplates: List[ItemResult]
    componentTemplates: List[ItemResult]
    indexes: List[ItemResult]
    aliases: List[ItemResult]


class TransformationInfo(BaseModel):
    """Transformation information model."""
    transformers: List[Dict[str, Any]]


class MetadataResponse(BaseModel):
    """Response model for metadata migration operations."""
    success: bool
    session_name: str
    status: Optional[StepState] = StepState.PENDING
    started: Optional[datetime] = None
    finished: Optional[datetime] = None
    clusters: Optional[ClustersInfo] = None
    items: Optional[ItemsInfo] = None
    transformations: Optional[TransformationInfo] = None
    errors: Optional[List[str]] = None
    errorCount: Optional[int] = None
    errorCode: Optional[int] = None
    errorMessage: Optional[str] = None


def parse_metadata_result(result: CommandResult) -> Dict[str, Any]:
    """Parse the metadata operation result into a structured format."""
    logger.info(f"Result response: {result}")
    if result.output and result.output.stdout:
        result_str = result.output.stdout
    elif result.value:
        result_str = str(result.value)
    else:
        return {"success": result.success}
    
    # Try to parse as JSON
    try:
        for line in result_str.split('\n'):
            line = line.strip()
            if line and (line.startswith('{') or line.startswith('[')):
                try:
                    parsed_json = json.loads(line)
                    if isinstance(parsed_json, dict):
                        parsed_json["success"] = result.success
                        return parsed_json
                except json.JSONDecodeError:
                    continue
        
        if result_str.strip().startswith('{'):
            parsed_json = json.loads(result_str)
            if isinstance(parsed_json, dict):
                parsed_json["success"] = result.success
                logger.info("Able to parse json and return it directlry")
                return parsed_json
            
    except (json.JSONDecodeError, AttributeError):
        logger.info("Could not parse result as JSON, using fallback structure")
    
    # Fallback: return basic structure with success status
    logger.warn("Unable to parse response")
    return {
        "success": result.success,
        "result": result_str if result_str else None
    }


def store_metadata_result(
    session_name: str,
    result: Dict[str, Any],
    start_time: datetime,
    end_time: datetime,
    dry_run: bool
) -> None:
    """Store metadata operation result for later retrieval."""
    metadata_result = {
        "session_name": session_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "started": start_time.isoformat(),
        "finished": end_time.isoformat(),
        "dry_run": dry_run,
        "success": result.get("success", False),
        "status": "Completed" if result.get("success", False) else "Failed",
        "result": result
    }
    
    # Store in database
    metadata_results_table.insert(metadata_result)
    logger.info(f"Stored metadata result for session {session_name}")


def get_latest_metadata_result(session_name: str) -> Dict[str, Any]:
    """Get the most recent metadata result for a session."""
    metadata_query = Query()
    results = metadata_results_table.search(metadata_query.session_name == session_name)
    
    if not results:
        return {
            "session_name": session_name,
            "success": True,
            "status": "Pending"
        }
    
    # Sort by timestamp and get the most recent
    latest_result = sorted(results, key=lambda x: x["timestamp"], reverse=True)[0]
    return latest_result


def build_extra_args(request: MetadataRequest) -> List[str]:
    """Build extra args list from the request parameters."""
    extra_args = []
    
    if request.index_allowlist:
        extra_args.extend(["--index-allowlist", ",".join(request.index_allowlist)])
    
    if request.index_template_allowlist:
        extra_args.extend(["--index-template-allowlist", ",".join(request.index_template_allowlist)])
    
    if request.component_template_allowlist:
        extra_args.extend(["--component-template-allowlist", ",".join(request.component_template_allowlist)])
    
    extra_args.extend(["--output", "json"])

    return extra_args


def build_metadata_response(session_name: str,
                            result: Dict[str, Any],
                            success: bool,
                            started: Optional[datetime],
                            finished: Optional[datetime]) -> MetadataResponse:
    """Build a structured metadata response from the command result."""
    # Parse the result to get structured data
    
    # Get status and timing information
    status = StepState.COMPLETED if success else StepState.FAILED
    
    logger.info(f"Building response for {session_name} with {result}")

    response = MetadataResponse(
        success=success,
        session_name=session_name,
        status=status,
        started=started,
        finished=finished,
        clusters=ClustersInfo(**result.get("clusters", {})) if "clusters" in result else None,
        items=ItemsInfo(**result.get("items", {})) if "items" in result else None,
        transformations=TransformationInfo(**result.get("transformations", {}))
        if "transformations" in result else None,
        errors=result.get("errors", []),
        errorCount=result.get("errorCount", 0),
        errorCode=result.get("errorCode", 0),
        errorMessage=result.get("errorMessage", None),
    )
    
    return response


def build_status_response_from_result(latest_result: Dict[str, Any]) -> MetadataResponse:
    """Build a metadata response from a stored result."""
    if not latest_result:
        return MetadataResponse(
            success=True,
            session_name="unknown",
            status=StepState.PENDING
        )
    
    # Extract the basic fields
    session_name = latest_result.get("session_name", "unknown")
    success = latest_result.get("success", False)
    
    return build_metadata_response(
        session_name=session_name,
        success=success,
        result=latest_result.get("result", {}),
        started=(lambda s: datetime.fromisoformat(s) if s else None)(latest_result.get("started")),
        finished=(lambda s: datetime.fromisoformat(s) if s else None)(latest_result.get("finished")),
    )


@metadata_router.post("/migrate",
                      response_model=MetadataResponse,
                      operation_id="metadataMigrate")
def migrate_metadata(session_name: str, request: MetadataRequest = Body(...)):
    """
    Migrate metadata for the given session.
    If dry_run=True, only evaluates the migration without making changes.
    """
    # Validate session exists
    session = existence_check(find_session(session_name))
    env = session.env
    
    try:
        # Check if metadata is configured
        if not env or not env.metadata:
            raise HTTPException(
                status_code=500,
                detail="Metadata migration is not configured in the environment"
            )
        
        # Build extra arguments from request
        extra_args = build_extra_args(request)
        extra_args.extend(["--multi-type-behavior", "union"])  # Add standard options
        
        # Execute metadata migration or evaluation based on dry_run
        operation_type = "evaluation" if request.dry_run else "migration"
        logger.info(f"Starting metadata {operation_type} for session {session_name}")
        
        start_time = datetime.now(timezone.utc)
        # Use the unified middleware function to perform the operation and store the result
        result = env.metadata.migrate_or_evaluate("migrate" if not request.dry_run else "evaluate", extra_args)
        end_time = datetime.now(timezone.utc)

        parsed_data = parse_metadata_result(result)

        store_metadata_result(session_name, parsed_data, start_time, end_time, dry_run=request.dry_run)
        # Build structured response
        return build_metadata_response(session_name, parsed_data, result.success, start_time, end_time)
            
    except HTTPException:
        # Re-raise HTTPExceptions (like 404, 500)
        raise
    except Exception as e:
        logger.error(f"Unexpected error during metadata {operation_type} for session {session_name}: {e}")
        raise HTTPException(
            status_code=500,
            detail=f"Unexpected error during metadata {operation_type}: {str(e)}"
        )


@metadata_router.get("/status",
                     response_model=MetadataResponse,
                     operation_id="metadataStatus")
def get_metadata_status(session_name: str):
    """Get the status of the most recent metadata operation for the session."""
    try:
        # Validate session exists
        existence_check(find_session(session_name))
        
        # Get the latest metadata result from the database
        latest_result = get_latest_metadata_result(session_name)
        
        # Build and return the response
        return build_status_response_from_result(latest_result)
        
    except HTTPException:
        # Re-raise HTTPExceptions (like 404, 500)
        raise
    except Exception as e:
        logger.error(f"Unexpected error getting metadata status for session {session_name}: {e}")
        import traceback
        error_detail = traceback.format_exc()
        raise HTTPException(
            status_code=500,
            detail=f"Unexpected error getting metadata status: {error_detail}"
        )
