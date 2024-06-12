from typing import Set


def contains_one_of(values_to_restrict: Set):
    """
    Generates a validator that checks if a value contains exactly one of the specified keys.
    """
    def one_of(field, value, error):
        found_objects = values_to_restrict.intersection(value.keys())
        if len(found_objects) > 1:
            error(field, f"More than one value is present: {sorted(found_objects)}")
        elif len(found_objects) < 1:
            error(field, f"No values are present from set: {sorted(values_to_restrict)}")
    return one_of
