// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package regions

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"go.uber.org/zap"
)

// RegionInfo contains both AWS region identifier and display name
type RegionInfo struct {
	ID          string `json:"id"`          // AWS region code (e.g., "us-east-1")
	DisplayName string `json:"displayName"` // Full region name (e.g., "US East (N. Virginia)")
	Continent   string `json:"continent"`   // Continent name (e.g., "North America")
	Currency    string `json:"currency"`    // Currency code (e.g., "USD", "CNY")
}

// AWSLocationData represents the structure of a single location from AWS locations endpoint
type AWSLocationData struct {
	Name      string `json:"name"`
	Code      string `json:"code"`
	Type      string `json:"type"`
	Label     string `json:"label"`
	Continent string `json:"continent"`
}

const (
	// AWSLocationsEndpoint is the official AWS endpoint for locations data
	AWSLocationsEndpoint = "https://b0.p.awsstatic.com/locations/1.0/aws/current/locations.json"

	// AWSRegionType is the type value for AWS Regions in the locations data
	AWSRegionType = "AWS Region"
)

// RegionMapping maps region display names to their AWS region codes
var RegionMapping = map[string]string{}

// RegionContinentMapping maps region display names to their continent
var RegionContinentMapping = map[string]string{}

// ReverseRegionMapping maps AWS region codes back to display names
var ReverseRegionMapping = map[string]string{}

var (
	initOnce sync.Once
	initErr  error
)

// fetchAWSRegions fetches the AWS regions from the official AWS locations endpoint
func fetchAWSRegions() error {
	// Make HTTP request to the AWS locations endpoint
	client := &http.Client{Timeout: 5 * time.Minute}
	resp, err := client.Get(AWSLocationsEndpoint)
	if err != nil {
		return fmt.Errorf("failed to fetch AWS locations: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("received non-OK response from AWS locations endpoint: %d", resp.StatusCode)
	}

	// Read the response body
	const maxResponseSize = 10 << 20 // 10 MB
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseSize))
	if err != nil {
		return fmt.Errorf("failed to read response body: %w", err)
	}

	// Parse the JSON response
	var locationsMap map[string]AWSLocationData
	if err := json.Unmarshal(body, &locationsMap); err != nil {
		return fmt.Errorf("failed to unmarshal AWS locations data: %w", err)
	}

	// Extract only AWS Regions (not Wavelength Zones or Local Zones)
	regionCount := 0
	for displayName, locationData := range locationsMap {
		if locationData.Type == AWSRegionType {
			RegionMapping[displayName] = locationData.Code
			RegionContinentMapping[displayName] = locationData.Continent
			ReverseRegionMapping[locationData.Code] = displayName
			regionCount++
		}
	}

	if len(RegionMapping) == 0 {
		return fmt.Errorf("no AWS regions found in the locations data")
	}

	return nil
}

// registerChinaRegions adds China regions to the global mapping tables.
// China regions are in a separate AWS partition and not included in the
// global AWS locations endpoint.
func registerChinaRegions() {
	for displayName, info := range chinaRegions {
		RegionMapping[displayName] = info.ID
		RegionContinentMapping[displayName] = info.Continent
		ReverseRegionMapping[info.ID] = displayName
	}
}

// fetchIsolatedRegions fetches locations for an isolated AWS partition and registers
// them in the global mapping tables with the given continent label.
func fetchIsolatedRegions(endpoint, continentLabel string, targetMap map[string]RegionInfo) {
	client := &http.Client{Timeout: 30 * time.Second}
	resp, err := client.Get(endpoint)
	if err != nil {
		zap.L().Warn("failed to fetch isolated region locations",
			zap.String("continent", continentLabel), zap.Error(err))
		return
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		zap.L().Warn("isolated region locations endpoint returned non-OK status",
			zap.String("continent", continentLabel), zap.Int("status", resp.StatusCode))
		return
	}

	const maxIsolatedResponseSize = 10 << 20 // 10 MB
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxIsolatedResponseSize))
	if err != nil {
		zap.L().Warn("failed to read isolated region locations response",
			zap.String("continent", continentLabel), zap.Error(err))
		return
	}

	var locationsMap map[string]AWSLocationData
	if err := json.Unmarshal(body, &locationsMap); err != nil {
		zap.L().Warn("failed to parse isolated region locations",
			zap.String("continent", continentLabel), zap.Error(err))
		return
	}

	var names []string
	for displayName, locationData := range locationsMap {
		if locationData.Type == AWSRegionType {
			RegionMapping[displayName] = locationData.Code
			RegionContinentMapping[displayName] = continentLabel
			ReverseRegionMapping[locationData.Code] = displayName
			targetMap[displayName] = RegionInfo{
				ID:          locationData.Code,
				DisplayName: displayName,
				Continent:   continentLabel,
				Currency:    "USD",
			}
			names = append(names, displayName)
		}
	}

	zap.L().Info("registered isolated regions",
		zap.String("continent", continentLabel),
		zap.Int("count", len(names)),
		zap.Strings("regions", names))
}

// Locations endpoints for isolated AWS partitions.
const (
	SecretLocationsEndpoint    = "https://b0.p.awsstatic.com/locations/1.0/aws-iso-b/current/locations.json"
	TopSecretLocationsEndpoint = "https://b0.p.awsstatic.com/locations/1.0/aws-iso/current/locations.json"
)

// Continent labels for isolated partition grouping.
const (
	SecretRegionContinent    = "Secret regions"
	TopSecretRegionContinent = "Top Secret regions"
)

// Region maps for isolated partitions.
var (
	secretRegionMap    = map[string]RegionInfo{}
	topSecretRegionMap = map[string]RegionInfo{}
)

// IsSecretRegionDisplay returns true if the display name is a Secret (aws-iso-b) region.
func IsSecretRegionDisplay(displayName string) bool {
	_, ok := secretRegionMap[displayName]
	return ok
}

// IsTopSecretRegionDisplay returns true if the display name is a Top Secret (aws-iso) region.
func IsTopSecretRegionDisplay(displayName string) bool {
	_, ok := topSecretRegionMap[displayName]
	return ok
}

// RegisterTestSecretRegions replaces the secretRegionMap for testing purposes.
// Pass nil to clear.
func RegisterTestSecretRegions(regions map[string]RegionInfo) {
	if regions == nil {
		secretRegionMap = map[string]RegionInfo{}
		return
	}
	secretRegionMap = regions
	for displayName, info := range regions {
		RegionMapping[displayName] = info.ID
		RegionContinentMapping[displayName] = info.Continent
		ReverseRegionMapping[info.ID] = displayName
	}
}

// RegisterTestTopSecretRegions replaces the topSecretRegionMap for testing purposes.
// Pass nil to clear.
func RegisterTestTopSecretRegions(regions map[string]RegionInfo) {
	if regions == nil {
		topSecretRegionMap = map[string]RegionInfo{}
		return
	}
	topSecretRegionMap = regions
	for displayName, info := range regions {
		RegionMapping[displayName] = info.ID
		RegionContinentMapping[displayName] = info.Continent
		ReverseRegionMapping[info.ID] = displayName
	}
}

// ensureRegionsLoaded ensures that the region mappings are loaded
func ensureRegionsLoaded() error {
	initOnce.Do(func() {
		initErr = fetchAWSRegions()
		registerChinaRegions()
		fetchIsolatedRegions(SecretLocationsEndpoint, SecretRegionContinent, secretRegionMap)
		fetchIsolatedRegions(TopSecretLocationsEndpoint, TopSecretRegionContinent, topSecretRegionMap)
	})
	return initErr
}

// chinaRegions defines the AWS China regions that are not in the global locations endpoint.
var chinaRegions = map[string]RegionInfo{
	"China (Beijing)": {
		ID:          "cn-north-1",
		DisplayName: "China (Beijing)",
		Continent:   "Asia Pacific",
		Currency:    "CNY",
	},
	"China (Ningxia)": {
		ID:          "cn-northwest-1",
		DisplayName: "China (Ningxia)",
		Continent:   "Asia Pacific",
		Currency:    "CNY",
	},
}

// IsChinaRegionDisplay returns true if the display name is a China region.
func IsChinaRegionDisplay(displayName string) bool {
	_, ok := chinaRegions[displayName]
	return ok
}

// CreateRegionInfo creates a RegionInfo from a region display name
func CreateRegionInfo(regionDisplayName string) RegionInfo {
	// Check China regions first (not in global AWS locations endpoint)
	if info, ok := chinaRegions[regionDisplayName]; ok {
		return info
	}

	// Check isolated partitions (Secret aws-iso-b, Top Secret aws-iso)
	if info, ok := secretRegionMap[regionDisplayName]; ok {
		return info
	}
	if info, ok := topSecretRegionMap[regionDisplayName]; ok {
		return info
	}

	if err := ensureRegionsLoaded(); err != nil {
		// Log the error but continue with fallback behavior
		zap.L().Warn("failed to load AWS regions", zap.Error(err))
	}

	regionCode := RegionMapping[regionDisplayName]
	continent := RegionContinentMapping[regionDisplayName]

	if regionCode == "" {
		// Fallback to display name as ID if no mapping found
		regionCode = regionDisplayName
	}

	return RegionInfo{
		ID:          regionCode,
		DisplayName: regionDisplayName,
		Continent:   continent,
		Currency:    "USD",
	}
}

// GetAllRegionInfos converts a slice of region display names to RegionInfo slice
func GetAllRegionInfos(regionDisplayNames []string) []RegionInfo {
	if err := ensureRegionsLoaded(); err != nil {
		// Log the error but continue with fallback behavior
		zap.L().Warn("failed to load AWS regions", zap.Error(err))
	}

	regions := make([]RegionInfo, 0, len(regionDisplayNames))

	for _, regionName := range regionDisplayNames {
		regions = append(regions, CreateRegionInfo(regionName))
	}

	return regions
}

// GetAllAvailableRegions returns all available AWS regions as RegionInfo objects,
// including China regions which are registered via registerChinaRegions().
func GetAllAvailableRegions() []RegionInfo {
	if err := ensureRegionsLoaded(); err != nil {
		// Log the error but continue with fallback behavior
		zap.L().Warn("failed to load AWS regions", zap.Error(err))
		return []RegionInfo{}
	}

	regions := make([]RegionInfo, 0, len(RegionMapping))

	for displayName := range RegionMapping {
		regionInfo := CreateRegionInfo(displayName)
		regions = append(regions, regionInfo)
	}

	return regions
}

// NormalizeRegionInput accepts either a region display name or AWS region code
// and returns the canonical display name that matches the cache key format
func NormalizeRegionInput(regionInput string) string {
	if err := ensureRegionsLoaded(); err != nil {
		// Log the error but continue with fallback behavior
		zap.L().Warn("failed to load AWS regions", zap.Error(err))
	}

	// First, check if it's already a display name (exists as key in RegionMapping)
	if _, exists := RegionMapping[regionInput]; exists {
		return regionInput
	}

	// If not, check if it's an AWS region code (exists in ReverseRegionMapping)
	if displayName, exists := ReverseRegionMapping[regionInput]; exists {
		return displayName
	}

	// If neither, return the input as-is (fallback behavior)
	return regionInput
}

// GetRegionCodeFromInput accepts either a region display name or AWS region code
// and returns the AWS region code
func GetRegionCodeFromInput(regionInput string) string {
	if err := ensureRegionsLoaded(); err != nil {
		// Log the error but continue with fallback behavior
		zap.L().Warn("failed to load AWS regions", zap.Error(err))
	}

	// First, check if it's a display name
	if regionCode, exists := RegionMapping[regionInput]; exists {
		return regionCode
	}

	// If not, check if it's already an AWS region code
	if _, exists := ReverseRegionMapping[regionInput]; exists {
		return regionInput
	}

	// If neither, return empty string to indicate unknown region
	return ""
}
