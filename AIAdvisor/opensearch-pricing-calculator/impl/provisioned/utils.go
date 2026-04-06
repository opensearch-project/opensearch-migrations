// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"strconv"
)

// formatFloat converts a float64 to a string with 2 decimal places
func formatFloat(f float64) string {
	return strconv.FormatFloat(f, 'f', 2, 64)
}

// formatInt converts an int to a string
func formatInt(i int) string {
	return strconv.Itoa(i)
}
