from jsondiff import diff


# Utility method to make a comma-separated string from a set.
# If the set is empty, "[]" is returned for clarity.
def string_from_set(s: set[str]) -> str:
    result = "["
    if s:
        result += ", ".join(s)
    return result + "]"


# Utility method to compare the JSON contents of a key in two dicts.
# This method handles checking if the key exists in either dict.
def has_differences(key: str, dict1: dict, dict2: dict) -> bool:
    if key not in dict1 and key not in dict2:
        return False
    elif key in dict1 and key in dict2:
        data_diff = diff(dict1[key], dict2[key])
        return bool(data_diff)
    else:
        return True
