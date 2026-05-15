// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package cache

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/PaesslerAG/jsonpath"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"go.uber.org/zap"
)

var lock = &sync.Mutex{}

type ServerlessPrice struct {
	Regions map[string]price.ServerlessRegion `json:"regions"`
}

type InvalidationStatus struct {
	Message     string    `json:"message"`
	UpdatedTime time.Time `json:"updatedTime"`
}

var serverlessPriceCache *ServerlessPrice

// GetServerlessPrice returns the serverless pricing for all regions.
//
// The function is thread-safe and will not return nil. It will return the same instance
// of ServerlessPrice on every call, unless the cache is invalidated, in which case
// it will return a new instance of ServerlessPrice.
func GetServerlessPrice() *ServerlessPrice {
	lock.Lock()
	defer lock.Unlock()
	if serverlessPriceCache == nil {
		serverlessPriceCache = &ServerlessPrice{}
		serverlessPriceCache.Regions = make(map[string]price.ServerlessRegion)
		serverlessPriceCache.Invalidate()
	}
	return serverlessPriceCache
}

// GetAllRegions returns all regions as an array of strings.
//
// The function is thread-safe and will not return nil. It will return the same instance
// of []string on every call, unless the cache is invalidated, in which case
// it will return a new instance of []string.
func (sl *ServerlessPrice) GetAllRegions() (regions []string) {
	for key := range sl.Regions {
		regions = append(regions, key)
	}
	return
}

// GetRegionPrice returns the serverless pricing for the given region.
//
// The function returns the ServerlessRegion object for the given region, or an error if the region is not found.
//
// The function is thread-safe and will not return nil. It will return the same instance
// of ServerlessRegion on every call, unless the cache is invalidated, in which case
// it will return a new instance of ServerlessRegion.
func (sl *ServerlessPrice) GetRegionPrice(region string) (price.ServerlessRegion, error) {
	rp, ok := sl.Regions[region]
	if ok {
		return rp, nil
	} else {
		return price.ServerlessRegion{}, errors.New("region not found")
	}
}

// Invalidate Reloads Serverless price cache.
//
// It reads the json from the ServerlessPricingUrl, processes it and updates the local copy.
// It also attempts to fetch Secret Region serverless pricing (aws-iso-b partition).
// If any error occurs, it returns an InvalidationStatus with the error message.
// If no error occurs, it returns an InvalidationStatus with a success message and the timestamp of the update.
func (sl *ServerlessPrice) Invalidate() (response InvalidationStatus) {
	response = sl.invalidateFromURL(price.ServerlessPricingUrl)
	if response.Message != "" && response.UpdatedTime.IsZero() {
		return
	}

	// Attempt to fetch isolated partition serverless pricing
	// Serverless is not currently available in these partitions but will work when it becomes available
	for _, ep := range []struct{ label, url string }{
		{"Secret", price.SecretServerlessPricingUrl},
		{"Top Secret", price.TopSecretServerlessPricingUrl},
	} {
		resp := sl.invalidateFromURL(ep.url)
		if resp.UpdatedTime.IsZero() {
			zap.L().Warn("failed to fetch isolated region serverless pricing, continuing",
				zap.String("partition", ep.label), zap.String("message", resp.Message))
		}
	}

	return InvalidationStatus{
		Message:     fmt.Sprintf("New Serverless price for %d regions updated", len(sl.Regions)),
		UpdatedTime: time.Now(),
	}
}

// invalidateFromURL fetches and processes serverless pricing from a given URL,
// merging results into the existing Regions map.
func (sl *ServerlessPrice) invalidateFromURL(url string) (response InvalidationStatus) {
	pricingJsonBytes, err := downloadJson(url)
	if err != nil {
		response.Message = fmt.Sprintf("error fetching serverless pricing from %s: %v", url, err)
		return
	}
	rp, ok := pricingJsonBytes.(map[string]interface{})
	if !ok {
		response.Message = "unexpected response format from serverless pricing API"
		return
	}
	count := 0
	for region, pricing := range rp {
		var regPri price.ServerlessRegion
		regionMap, ok := pricing.(map[string]interface{})
		if !ok {
			zap.L().Warn("skipping region with unexpected data format", zap.String("region", region))
			continue
		}
		for itemKey, itemValue := range regionMap {
			itemMap, ok := itemValue.(map[string]interface{})
			if !ok {
				continue
			}
			switch itemKey {
			case "Amazon OpenSearch Service Serverless IndexingOCU":
				regPri.IndexingOCU.RateCode, regPri.IndexingOCU.Price = getRateCodeAndPrice(itemMap)
			case "Amazon OpenSearch Service Serverless SearchOCU":
				regPri.SearchOCU.RateCode, regPri.SearchOCU.Price = getRateCodeAndPrice(itemMap)
			case "Amazon OpenSearch Service Serverless Managed Storage":
				regPri.S3ByteHour.RateCode, regPri.S3ByteHour.Price = getRateCodeAndPrice(itemMap)
			}
		}
		sl.Regions[region] = regPri
		count++
	}
	return InvalidationStatus{
		Message:     fmt.Sprintf("Serverless price for %d regions updated from %s", count, url),
		UpdatedTime: time.Now(),
	}
}

// getRateCodeAndPrice takes a map of strings to interface{} and returns a tuple of string and float64.
//
// The function iterates over the map and checks if the key is "rateCode" or "price". If the key is "rateCode", it assigns the value to the rateCode variable. If the key is "price", it parses the value as a float64 and assigns it to the price variable.
//
// The function then returns the rateCode and price variables.
func getRateCodeAndPrice(itemValue map[string]interface{}) (rateCode string, price float64) {
	for ik, iv := range itemValue {
		switch ik {
		case "rateCode":
			if s, ok := iv.(string); ok {
				rateCode = s
			}
		case "price":
			if s, ok := iv.(string); ok {
				price, _ = strconv.ParseFloat(s, 64)
			}
		}
	}
	return
}

// downloadJson downloads a JSON from a URL and unmarshals it, then returns the object found at "$.regions" from the JSON.
//
// The function will return an error if the HTTP request fails, or if the JSON is not parsed correctly.
// If the JSON is parsed correctly, but there is no "$.regions" property, the function will return an empty interface.
//
// The function uses the http.Client to download the JSON, and sets a timeout of 20 seconds.
// The function uses the json.Unmarshal function to unmarshal the JSON into an interface, and then uses the jsonpath.Read function to read the "$.regions" property from the interface.
func downloadJson(url string) (gi interface{}, err error) {
	spaceClient := http.Client{
		Timeout: 5 * time.Minute,
	}

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return
	}

	res, err := spaceClient.Do(req)
	if err != nil {
		return
	}

	if res.StatusCode != http.StatusOK {
		if res.Body != nil {
			_ = res.Body.Close()
		}
		return nil, fmt.Errorf("pricing API returned status %d for %s", res.StatusCode, url)
	}

	if res.Body != nil {
		defer func(Body io.ReadCloser) {
			err := Body.Close()
			if err != nil {
				zap.L().Error("error closing response body", zap.Error(err))
			}
		}(res.Body)
	}
	var f interface{}
	const maxResponseSize = 10 << 20 // 10 MB
	body, err := io.ReadAll(io.LimitReader(res.Body, maxResponseSize))
	if err != nil {
		return nil, err
	}
	err = json.Unmarshal(body, &f)
	if err != nil {
		zap.L().Error("error parsing JSON", zap.Error(err))
		return nil, err
	}
	gi, err = jsonpath.Get("$.regions", f)
	if err != nil {
		zap.L().Error("error reading $.regions from JSON", zap.Error(err))
		return nil, err
	}
	return
}
