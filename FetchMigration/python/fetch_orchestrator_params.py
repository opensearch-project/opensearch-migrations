LOCAL_ENDPOINT_HTTP: str = "http://localhost:"
LOCAL_ENDPOINT_HTTPS: str = "https://localhost:"


class FetchOrchestratorParams:
    data_prepper_path: str
    pipeline_file_path: str
    local_port: int
    is_insecure: bool
    is_dry_run: bool
    is_create_only: bool

    def __init__(self, dp_path: str, config_path: str, port: int = 4900, insecure: bool = False, dryrun: bool = False,
                 create_only: bool = False):
        self.data_prepper_path = dp_path
        self.pipeline_file_path = config_path
        self.local_port = port
        self.is_insecure = insecure
        self.is_dry_run = dryrun
        self.is_create_only = create_only

    def get_local_endpoint(self) -> str:
        if self.is_insecure:
            return LOCAL_ENDPOINT_HTTP + str(self.local_port)
        else:
            return LOCAL_ENDPOINT_HTTPS + str(self.local_port)

    def is_only_metadata_migration(self) -> bool:
        return self.is_dry_run or self.is_create_only
