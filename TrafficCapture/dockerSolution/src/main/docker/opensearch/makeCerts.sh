#!/usr/bin/env bash
openssl req -x509 -newkey rsa:4096 -keyout esnode-key.pem -out esnode.pem -days 3650 -nodes -subj "/CN=opensearch_target"

