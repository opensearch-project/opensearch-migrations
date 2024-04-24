#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

from typing import Union

from requests_aws4auth import AWS4Auth


# Class that encapsulates endpoint information for an OpenSearch/Elasticsearch cluster
class EndpointInfo:
    # Private member variables
    __url: str
    # "|" operator is only supported in 3.10+
    __auth: Union[AWS4Auth, tuple, None]
    __verify_ssl: bool

    def __init__(self, url: str, auth: Union[AWS4Auth, tuple, None] = None, verify_ssl: bool = True):
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

    def get_auth(self) -> Union[AWS4Auth, tuple, None]:
        return self.__auth

    def is_verify_ssl(self) -> bool:
        return self.__verify_ssl
