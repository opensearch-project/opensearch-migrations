from typing import Optional

from requests_aws4auth import AWS4Auth


# Class that encapsulates endpoint information for an OpenSearch/Elasticsearch cluster
class EndpointInfo:
    # Private member variables
    __url: str
    __auth: Optional[tuple] | AWS4Auth
    __verify_ssl: bool

    def __init__(self, url: str, auth: tuple | AWS4Auth = None, verify_ssl: bool = True):
        self.__url = url
        # Normalize url value to have trailing slash
        if not url.endswith("/"):
            self.__url += "/"
        self.__auth = auth
        self.__verify_ssl = verify_ssl

    def __eq__(self, obj):
        return isinstance(obj, EndpointInfo) and \
            self.__url == obj.__url and \
            self.__auth == obj.__auth and \
            self.__verify_ssl == obj.__verify_ssl

    def add_path(self, path: str) -> str:
        # Remove leading slash if present
        if path.startswith("/"):
            path = path[1:]
        return self.__url + path

    def get_url(self) -> str:
        return self.__url

    def get_auth(self) -> Optional[tuple] | AWS4Auth:
        return self.__auth

    def is_verify_ssl(self) -> bool:
        return self.__verify_ssl
