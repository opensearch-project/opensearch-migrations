#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

import logging
import math
from typing import Optional


# Class that tracks metrics on the health and progress of the migration.Specific metric values from the Data Prepper
# metrics API endpoint are retrieved and stored, as well as idle-value tracking via counters that may indicate an
# idle pipeline. Counters are also used to keep track of API failures or missing metric values.
class ProgressMetrics:

    # Private constants
    __IDLE_VALUE_PREFIX: str = "idle-value-"
    _METRIC_API_FAIL_KEY: str = "metric_api_fail"
    _SUCCESS_DOCS_KEY = "success_docs"
    _REC_IN_FLIGHT_KEY = "records_in_flight"
    _NO_PART_KEY = "no_partitions"

    # Private member variables
    __target_doc_count: int
    __idle_threshold: int
    __current_values_map: dict[str, Optional[int]]
    __prev_values_map: dict[str, Optional[int]]
    __counter_map: dict[str, int]

    def __init__(self, doc_count, idle_threshold):
        self.__target_doc_count = doc_count
        self.__idle_threshold = idle_threshold
        self.__current_values_map = dict()
        self.__prev_values_map = dict()
        self.__counter_map = dict()

    def get_target_doc_count(self) -> int:
        return self.__target_doc_count

    def __reset_counter(self, key: str):
        if key in self.__counter_map:
            del self.__counter_map[key]

    def __increment_counter(self, key: str):
        val = self.__counter_map.get(key, 0)
        self.__counter_map[key] = val + 1

    def __get_idle_value_key_name(self, key: str) -> str:
        return self.__IDLE_VALUE_PREFIX + key

    def __get_idle_value_count(self, key: str) -> Optional[int]:
        idle_value_key = self.__get_idle_value_key_name(key)
        return self.__counter_map.get(idle_value_key)

    def __record_value(self, key: str, val: Optional[int]):
        if key in self.__current_values_map:
            # Move current value to previous
            self.__prev_values_map[key] = self.__current_values_map[key]
            # Track idle value metrics
            idle_value_key = self.__get_idle_value_key_name(key)
            if self.__prev_values_map[key] == val:
                self.__increment_counter(idle_value_key)
            else:
                self.__reset_counter(idle_value_key)
        # Store new value
        self.__current_values_map[key] = val

    def __get_current_value(self, key: str) -> Optional[int]:
        return self.__current_values_map.get(key)

    def reset_metric_api_failure(self):
        self.__reset_counter(self._METRIC_API_FAIL_KEY)

    def record_metric_api_failure(self):
        self.__increment_counter(self._METRIC_API_FAIL_KEY)

    def __reset_success_doc_value_failure(self):
        self.__reset_counter(self._SUCCESS_DOCS_KEY)
        # Also reset API falure counter
        self.reset_metric_api_failure()

    def record_success_doc_value_failure(self):
        self.__record_value(self._SUCCESS_DOCS_KEY, None)

    def update_success_doc_count(self, doc_count: int) -> int:
        self.__reset_success_doc_value_failure()
        self.__record_value(self._SUCCESS_DOCS_KEY, doc_count)
        return self.get_doc_completion_percentage()

    def update_records_in_flight_count(self, rec_in_flight: Optional[int]):
        self.__record_value(self._REC_IN_FLIGHT_KEY, rec_in_flight)

    def update_no_partitions_count(self, no_part_count: Optional[int]):
        self.__record_value(self._NO_PART_KEY, no_part_count)

    def get_doc_completion_percentage(self) -> int:
        success_doc_count = self.__get_current_value(self._SUCCESS_DOCS_KEY)
        if success_doc_count is None:
            success_doc_count = 0
        return math.floor((success_doc_count * 100) / self.__target_doc_count)

    def all_docs_migrated(self) -> bool:
        # TODO Add a check for partitionsCompleted = indices
        success_doc_count = self.__get_current_value(self._SUCCESS_DOCS_KEY)
        if success_doc_count is None:
            success_doc_count = 0
        return success_doc_count >= self.__target_doc_count

    def is_migration_complete_success(self) -> bool:
        is_idle_pipeline: bool = False
        rec_in_flight = self.__get_current_value(self._REC_IN_FLIGHT_KEY)
        no_partitions_count = self.__get_current_value(self._NO_PART_KEY)
        prev_no_partitions_count = self.__prev_values_map.get(self._NO_PART_KEY, 0)
        # Check for no records in flight
        if rec_in_flight is not None and rec_in_flight == 0:
            # No-partitions metrics should steadily tick up
            if no_partitions_count is not None and no_partitions_count > prev_no_partitions_count > 0:
                is_idle_pipeline = True
        return is_idle_pipeline and self.all_docs_migrated()

    def is_migration_idle(self) -> bool:
        keys_to_check = [self._NO_PART_KEY, self._SUCCESS_DOCS_KEY]
        for key in keys_to_check:
            val = self.__get_idle_value_count(key)
            if val is not None and val >= self.__idle_threshold:
                logging.warning("Idle pipeline detected because [" + key + "] value was idle above threshold: " +
                                str(self.__idle_threshold))
                return True
        # End of loop
        return False

    def is_too_may_api_failures(self) -> bool:
        return self.__counter_map.get(self._METRIC_API_FAIL_KEY, 0) >= self.__idle_threshold

    def is_in_terminal_state(self) -> bool:
        return self.is_migration_complete_success() or self.is_migration_idle() or self.is_too_may_api_failures()

    def log_idle_pipeline_debug_metrics(self):  # pragma no cover
        if logging.getLogger().isEnabledFor(logging.DEBUG):
            logging.debug("Idle pipeline metrics - " +
                          f"Records in flight: [{self.__get_current_value(self._REC_IN_FLIGHT_KEY)}], " +
                          f"No-partitions counter: [{self.__get_current_value(self._NO_PART_KEY)}]" +
                          f"Previous no-partition value: [{self.__prev_values_map.get(self._NO_PART_KEY)}]")
