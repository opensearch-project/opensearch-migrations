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
	// Also register China regions (separate AWS partition)
	registerChinaRegions()
	// Register test Secret regions (aws-iso-b partition)
	RegisterTestSecretRegions(map[string]RegionInfo{
		"US ISO EAST (Ohio)": {
			ID:          "us-isob-east-1",
			DisplayName: "US ISO EAST (Ohio)",
			Continent:   "Secret regions",
			Currency:    "USD",
		},
	})
	// Register test Top Secret regions (aws-iso partition)
	RegisterTestTopSecretRegions(map[string]RegionInfo{
		"US ISO East": {
			ID:          "us-iso-east-1",
			DisplayName: "US ISO East",
			Continent:   "Top Secret regions",
			Currency:    "USD",
		},
	})
}

func TestCreateRegionInfo_DisplayName(t *testing.T) {
	setupTestMappings()

	ri := CreateRegionInfo("US East (N. Virginia)")
	assert.Equal(t, "us-east-1", ri.ID)
	assert.Equal(t, "US East (N. Virginia)", ri.DisplayName)
	assert.Equal(t, "North America", ri.Continent)
}

func TestCreateRegionInfo_UnknownRegion(t *testing.T) {
	setupTestMappings()

	ri := CreateRegionInfo("Unknown Region")
	// Falls back to display name as ID
	assert.Equal(t, "Unknown Region", ri.ID)
	assert.Equal(t, "Unknown Region", ri.DisplayName)
	assert.Equal(t, "", ri.Continent)
}

func TestNormalizeRegionInput_DisplayName(t *testing.T) {
	setupTestMappings()

	result := NormalizeRegionInput("EU (Ireland)")
	assert.Equal(t, "EU (Ireland)", result)
}

func TestNormalizeRegionInput_RegionCode(t *testing.T) {
	setupTestMappings()

	result := NormalizeRegionInput("eu-west-1")
	assert.Equal(t, "EU (Ireland)", result)
}

func TestNormalizeRegionInput_Unknown(t *testing.T) {
	setupTestMappings()

	result := NormalizeRegionInput("unknown-region")
	assert.Equal(t, "unknown-region", result)
}

func TestGetRegionCodeFromInput_DisplayName(t *testing.T) {
	setupTestMappings()

	code := GetRegionCodeFromInput("Asia Pacific (Tokyo)")
	assert.Equal(t, "ap-northeast-1", code)
}

func TestGetRegionCodeFromInput_RegionCode(t *testing.T) {
	setupTestMappings()

	code := GetRegionCodeFromInput("us-west-2")
	assert.Equal(t, "us-west-2", code)
}

func TestGetRegionCodeFromInput_Unknown(t *testing.T) {
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

func TestGetAllRegionInfos_Empty(t *testing.T) {
	setupTestMappings()

	infos := GetAllRegionInfos([]string{})
	assert.Empty(t, infos)
}

func TestGetAllAvailableRegions(t *testing.T) {
	setupTestMappings()

	regions := GetAllAvailableRegions()
	assert.Len(t, regions, 8) // 4 global + 2 China + 1 Secret + 1 Top Secret

	// Collect all IDs and currencies
	ids := make(map[string]bool)
	currencies := make(map[string]string)
	continents := make(map[string]string)
	for _, r := range regions {
		ids[r.ID] = true
		currencies[r.ID] = r.Currency
		continents[r.ID] = r.Continent
	}
	assert.True(t, ids["us-east-1"])
	assert.True(t, ids["eu-west-1"])
	assert.True(t, ids["ap-northeast-1"])
	assert.True(t, ids["us-west-2"])
	assert.True(t, ids["cn-north-1"])
	assert.True(t, ids["cn-northwest-1"])
	assert.True(t, ids["us-isob-east-1"])
	assert.True(t, ids["us-iso-east-1"])

	// Verify currencies
	assert.Equal(t, "USD", currencies["us-east-1"])
	assert.Equal(t, "CNY", currencies["cn-north-1"])
	assert.Equal(t, "CNY", currencies["cn-northwest-1"])
	assert.Equal(t, "USD", currencies["us-isob-east-1"])
	assert.Equal(t, "USD", currencies["us-iso-east-1"])

	// Verify isolated region continents
	assert.Equal(t, "Secret regions", continents["us-isob-east-1"])
	assert.Equal(t, "Top Secret regions", continents["us-iso-east-1"])
}

func TestNormalizeRegionInput_ChinaRegionCode(t *testing.T) {
	setupTestMappings()

	// cn-north-1 should normalize to "China (Beijing)"
	assert.Equal(t, "China (Beijing)", NormalizeRegionInput("cn-north-1"))
	assert.Equal(t, "China (Ningxia)", NormalizeRegionInput("cn-northwest-1"))
}

func TestNormalizeRegionInput_ChinaDisplayName(t *testing.T) {
	setupTestMappings()

	// Display names should pass through unchanged
	assert.Equal(t, "China (Beijing)", NormalizeRegionInput("China (Beijing)"))
	assert.Equal(t, "China (Ningxia)", NormalizeRegionInput("China (Ningxia)"))
}

func TestGetRegionCodeFromInput_China(t *testing.T) {
	setupTestMappings()

	assert.Equal(t, "cn-north-1", GetRegionCodeFromInput("China (Beijing)"))
	assert.Equal(t, "cn-northwest-1", GetRegionCodeFromInput("China (Ningxia)"))
	assert.Equal(t, "cn-north-1", GetRegionCodeFromInput("cn-north-1"))
}

func TestCreateRegionInfo_ChinaRegion(t *testing.T) {
	setupTestMappings()

	ri := CreateRegionInfo("China (Beijing)")
	assert.Equal(t, "cn-north-1", ri.ID)
	assert.Equal(t, "China (Beijing)", ri.DisplayName)
	assert.Equal(t, "Asia Pacific", ri.Continent)
	assert.Equal(t, "CNY", ri.Currency)
}

func TestIsChinaRegionDisplay(t *testing.T) {
	assert.True(t, IsChinaRegionDisplay("China (Beijing)"))
	assert.True(t, IsChinaRegionDisplay("China (Ningxia)"))
	assert.False(t, IsChinaRegionDisplay("cn-north-1"))
	assert.False(t, IsChinaRegionDisplay("US East (N. Virginia)"))
}

func TestIsSecretRegionDisplay(t *testing.T) {
	setupTestMappings()

	assert.True(t, IsSecretRegionDisplay("US ISO EAST (Ohio)"))
	assert.False(t, IsSecretRegionDisplay("us-isob-east-1"))
	assert.False(t, IsSecretRegionDisplay("US East (N. Virginia)"))
}

func TestNormalizeRegionInput_SecretRegionCode(t *testing.T) {
	setupTestMappings()

	assert.Equal(t, "US ISO EAST (Ohio)", NormalizeRegionInput("us-isob-east-1"))
	assert.Equal(t, "US ISO EAST (Ohio)", NormalizeRegionInput("US ISO EAST (Ohio)"))
}

func TestGetRegionCodeFromInput_Secret(t *testing.T) {
	setupTestMappings()

	assert.Equal(t, "us-isob-east-1", GetRegionCodeFromInput("US ISO EAST (Ohio)"))
	assert.Equal(t, "us-isob-east-1", GetRegionCodeFromInput("us-isob-east-1"))
}

func TestCreateRegionInfo_SecretRegion(t *testing.T) {
	setupTestMappings()

	ri := CreateRegionInfo("US ISO EAST (Ohio)")
	assert.Equal(t, "us-isob-east-1", ri.ID)
	assert.Equal(t, "US ISO EAST (Ohio)", ri.DisplayName)
	assert.Equal(t, "Secret regions", ri.Continent)
	assert.Equal(t, "USD", ri.Currency)
}

func TestIsTopSecretRegionDisplay(t *testing.T) {
	setupTestMappings()

	assert.True(t, IsTopSecretRegionDisplay("US ISO East"))
	assert.False(t, IsTopSecretRegionDisplay("us-iso-east-1"))
	assert.False(t, IsTopSecretRegionDisplay("US ISO EAST (Ohio)"))
}

func TestNormalizeRegionInput_TopSecretRegionCode(t *testing.T) {
	setupTestMappings()

	assert.Equal(t, "US ISO East", NormalizeRegionInput("us-iso-east-1"))
	assert.Equal(t, "US ISO East", NormalizeRegionInput("US ISO East"))
}

func TestCreateRegionInfo_TopSecretRegion(t *testing.T) {
	setupTestMappings()

	ri := CreateRegionInfo("US ISO East")
	assert.Equal(t, "us-iso-east-1", ri.ID)
	assert.Equal(t, "US ISO East", ri.DisplayName)
	assert.Equal(t, "Top Secret regions", ri.Continent)
	assert.Equal(t, "USD", ri.Currency)
}
