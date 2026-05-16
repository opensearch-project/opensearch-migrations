// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGetRateCodeAndPrice(t *testing.T) {
	tests := []struct {
		name             string
		input            map[string]interface{}
		expectedRateCode string
		expectedPrice    float64
	}{
		{
			name: "valid rate code and price",
			input: map[string]interface{}{
				"rateCode": "ABC123",
				"price":    "0.245",
			},
			expectedRateCode: "ABC123",
			expectedPrice:    0.245,
		},
		{
			name: "only rate code",
			input: map[string]interface{}{
				"rateCode": "XYZ789",
			},
			expectedRateCode: "XYZ789",
			expectedPrice:    0,
		},
		{
			name: "only price",
			input: map[string]interface{}{
				"price": "1.50",
			},
			expectedRateCode: "",
			expectedPrice:    1.50,
		},
		{
			name:             "empty map",
			input:            map[string]interface{}{},
			expectedRateCode: "",
			expectedPrice:    0,
		},
		{
			name: "extra keys are ignored",
			input: map[string]interface{}{
				"rateCode":    "RC001",
				"price":       "3.14",
				"description": "some description",
				"unit":        "Hrs",
			},
			expectedRateCode: "RC001",
			expectedPrice:    3.14,
		},
		{
			name: "invalid price string defaults to zero",
			input: map[string]interface{}{
				"rateCode": "RC002",
				"price":    "not-a-number",
			},
			expectedRateCode: "RC002",
			expectedPrice:    0,
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rateCode, price := getRateCodeAndPrice(tc.input)
			assert.Equal(t, tc.expectedRateCode, rateCode)
			assert.Equal(t, tc.expectedPrice, price)
		})
	}
}

func TestDownloadJson_Success(t *testing.T) {
	// Create a test server that returns a valid pricing JSON
	pricingData := map[string]interface{}{
		"regions": map[string]interface{}{
			"US East (N. Virginia)": map[string]interface{}{
				"item1": map[string]interface{}{
					"rateCode": "RC1",
					"price":    "0.10",
				},
			},
		},
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(pricingData)
	}))
	defer server.Close()

	result, err := downloadJson(server.URL)
	require.NoError(t, err)
	require.NotNil(t, result)

	// The result should be the content under $.regions
	regions, ok := result.(map[string]interface{})
	require.True(t, ok, "expected result to be a map")
	assert.Contains(t, regions, "US East (N. Virginia)")
}

func TestDownloadJson_InvalidJSON(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte("not valid json"))
	}))
	defer server.Close()

	_, err := downloadJson(server.URL)
	assert.Error(t, err)
}

func TestDownloadJson_HTTPError(t *testing.T) {
	// Use an invalid URL to trigger an HTTP error
	_, err := downloadJson("http://127.0.0.1:1")
	assert.Error(t, err)
}

func TestDownloadJson_NoRegionsKey(t *testing.T) {
	// JSON that doesn't have a "regions" key
	data := map[string]interface{}{
		"other": "data",
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(data)
	}))
	defer server.Close()

	result, err := downloadJson(server.URL)
	// downloadJson returns an error when $.regions key is missing
	assert.Error(t, err)
	assert.Nil(t, result)
}

func TestDownloadJson_Non200Status(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal error"))
	}))
	defer server.Close()

	result, err := downloadJson(server.URL)
	assert.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "status 500")
}

func TestDownloadJson_404Status(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	result, err := downloadJson(server.URL)
	assert.Error(t, err)
	assert.Nil(t, result)
	assert.Contains(t, err.Error(), "status 404")
}

func TestServerlessPrice_GetAllRegions(t *testing.T) {
	sp := &ServerlessPrice{
		Regions: map[string]price.ServerlessRegion{
			"US East (N. Virginia)": {},
			"EU (Ireland)":         {},
			"Asia Pacific (Tokyo)": {},
		},
	}

	regions := sp.GetAllRegions()
	assert.Len(t, regions, 3)
	assert.Contains(t, regions, "US East (N. Virginia)")
	assert.Contains(t, regions, "EU (Ireland)")
	assert.Contains(t, regions, "Asia Pacific (Tokyo)")
}

func TestServerlessPrice_GetAllRegions_Empty(t *testing.T) {
	sp := &ServerlessPrice{
		Regions: map[string]price.ServerlessRegion{},
	}

	regions := sp.GetAllRegions()
	assert.Empty(t, regions)
}

func TestServerlessPrice_GetRegionPrice(t *testing.T) {
	expectedRegion := price.ServerlessRegion{
		IndexingOCU: price.Unit{RateCode: "IDX1", Price: 0.24},
		SearchOCU:   price.Unit{RateCode: "SRC1", Price: 0.24},
		S3ByteHour:  price.Unit{RateCode: "S3H1", Price: 0.024},
	}

	sp := &ServerlessPrice{
		Regions: map[string]price.ServerlessRegion{
			"US East (N. Virginia)": expectedRegion,
		},
	}

	region, err := sp.GetRegionPrice("US East (N. Virginia)")
	require.NoError(t, err)
	assert.Equal(t, expectedRegion, region)
}

func TestServerlessPrice_GetRegionPrice_NotFound(t *testing.T) {
	sp := &ServerlessPrice{
		Regions: map[string]price.ServerlessRegion{
			"US East (N. Virginia)": {},
		},
	}

	_, err := sp.GetRegionPrice("Nonexistent Region")
	assert.Error(t, err)
	assert.Equal(t, "region not found", err.Error())
}

func TestGetRateCodeAndPrice_SafeTypeAssertions(t *testing.T) {
	// Normal case
	m := map[string]interface{}{
		"rateCode": "RC123",
		"price":    "0.24",
	}
	rc, p := getRateCodeAndPrice(m)
	assert.Equal(t, "RC123", rc)
	assert.Equal(t, 0.24, p)

	// Non-string values should not panic
	m2 := map[string]interface{}{
		"rateCode": 123,     // int instead of string
		"price":    0.24,    // float instead of string
	}
	rc2, p2 := getRateCodeAndPrice(m2)
	assert.Equal(t, "", rc2)
	assert.Equal(t, 0.0, p2)
}

