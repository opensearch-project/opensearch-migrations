from dataclasses import dataclass


@dataclass
class EndpointInfo:
    url: str
    auth: tuple = None
    verify_ssl: bool = True
