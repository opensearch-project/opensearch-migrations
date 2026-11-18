"""Validation helpers shared by external-reference discovery and edit forms."""

import re


def is_k8s_name(value: str) -> bool:
    return bool(re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*", value))


def is_config_map_key(value: str) -> bool:
    return bool(re.fullmatch(r"(?!\.{1,2}$)(?!\.\.)[A-Za-z0-9._-]+", value))


def looks_like_pem_certificate_chain(value: str) -> bool:
    return bool(re.search(r"-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----", value.strip()))


def looks_like_pem_private_key(value: str) -> bool:
    return bool(re.search(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]+?-----END [A-Z ]*PRIVATE KEY-----", value.strip()))


def looks_like_log4j_properties(value: str) -> bool:
    lines = [
        line.strip()
        for line in value.splitlines()
        if line.strip() and not line.lstrip().startswith(("#", "!"))
    ]
    return bool(lines) and any("=" in line for line in lines)
