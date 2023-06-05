#!/bin/sh
sed -i "s/searchguard.ssl.http.enabled: *false/searchguard.ssl.http.enabled: true/g" "$@"