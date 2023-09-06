from dataclasses import dataclass


@dataclass
class PreMigrationParams:
    config_file_path: str
    output_file: str = ""
    report: bool = False
    dryrun: bool = False
