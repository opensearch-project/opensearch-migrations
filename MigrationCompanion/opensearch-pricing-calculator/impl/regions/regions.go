// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package regions

type Region struct {
	Name      string `json:"name"`
	Code      string `json:"code"`
	Type      string `json:"type"`
	Label     string `json:"label"`
	Continent string `json:"continent"`
}

type Regions struct {
	Regions []Region `json:"regions"`
}
