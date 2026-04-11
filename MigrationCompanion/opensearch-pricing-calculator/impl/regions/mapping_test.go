// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package regions

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// setupTestMappings populates the package-level maps for testing.
// These override whatever was loaded (or not) from the AWS endpoint.
func setupTestMappings() {
	RegionMapping = map[string]string{
		"US East (N. Virginia)":   "us-east-1",
		"EU (Ireland)":           "eu-west-1",
		"Asia Pacific (Tokyo)":   "ap-northeast-1",
		"US West (Oregon)":       "us-west-2",
	}
	RegionContinentMapping = map[string]string{
		"US East (N. Virginia)":   "North America",
		"EU (Ireland)":           "Europe",
		"Asia Pacific (Tokyo)":   "Asia Pacific",
		"US West (Oregon)":       "North America",
	}
	ReverseRegionMapping = map[string]string{
		"us-east-1":      "US East (N. Virginia)",
		"eu-west-1":      "EU (Ireland)",
		"ap-northeast-1": "Asia Pacific (Tokyo)",
		"us-west-2":      "US West (Oregon)",
	}
}

func TestCreateRegionInfoDisplayName(t *testing.T) {
	setupTestMappings()

	ri := CreateRegionInfo("US East (N. Virginia)")
	assert.Equal(t, "us-east-1", ri.ID)
	assert.Equal(t, "US East (N. Virginia)", ri.DisplayName)
	assert.Equal(t, "North America", ri.Continent)
}

func TestCreateRegionInfoUnknownRegion(t *testing.T) {
	setupTestMappings()

	ri := CreateRegionInfo("Unknown Region")
	// Falls back to display name as ID
	assert.Equal(t, "Unknown Region", ri.ID)
	assert.Equal(t, "Unknown Region", ri.DisplayName)
	assert.Equal(t, "", ri.Continent)
}

func TestNormalizeRegionInputDisplayName(t *testing.T) {
	setupTestMappings()

	result := NormalizeRegionInput("EU (Ireland)")
	assert.Equal(t, "EU (Ireland)", result)
}

func TestNormalizeRegionInputRegionCode(t *testing.T) {
	setupTestMappings()

	result := NormalizeRegionInput("eu-west-1")
	assert.Equal(t, "EU (Ireland)", result)
}

func TestNormalizeRegionInputUnknown(t *testing.T) {
	setupTestMappings()

	result := NormalizeRegionInput("unknown-region")
	assert.Equal(t, "unknown-region", result)
}

func TestGetRegionCodeFromInputDisplayName(t *testing.T) {
	setupTestMappings()

	code := GetRegionCodeFromInput("Asia Pacific (Tokyo)")
	assert.Equal(t, "ap-northeast-1", code)
}

func TestGetRegionCodeFromInputRegionCode(t *testing.T) {
	setupTestMappings()

	code := GetRegionCodeFromInput("us-west-2")
	assert.Equal(t, "us-west-2", code)
}

func TestGetRegionCodeFromInputUnknown(t *testing.T) {
	setupTestMappings()

	code := GetRegionCodeFromInput("nonexistent")
	assert.Equal(t, "", code)
}

func TestGetAllRegionInfos(t *testing.T) {
	setupTestMappings()

	names := []string{"US East (N. Virginia)", "EU (Ireland)"}
	infos := GetAllRegionInfos(names)

	assert.Len(t, infos, 2)
	assert.Equal(t, "us-east-1", infos[0].ID)
	assert.Equal(t, "US East (N. Virginia)", infos[0].DisplayName)
	assert.Equal(t, "eu-west-1", infos[1].ID)
	assert.Equal(t, "EU (Ireland)", infos[1].DisplayName)
}

func TestGetAllRegionInfosEmpty(t *testing.T) {
	setupTestMappings()

	infos := GetAllRegionInfos([]string{})
	assert.Empty(t, infos)
}

func TestGetAllAvailableRegions(t *testing.T) {
	setupTestMappings()

	regions := GetAllAvailableRegions()
	assert.Len(t, regions, 4)

	// Collect all IDs
	ids := make(map[string]bool)
	for _, r := range regions {
		ids[r.ID] = true
	}
	assert.True(t, ids["us-east-1"])
	assert.True(t, ids["eu-west-1"])
	assert.True(t, ids["ap-northeast-1"])
	assert.True(t, ids["us-west-2"])
}
