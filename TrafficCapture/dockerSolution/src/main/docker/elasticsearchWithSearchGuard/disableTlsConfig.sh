#!/bin/sh
sed -i "s/searchguard.ssl.http.enabled: *true/searchguard.ssl.http.enabled: false/g" "$@"