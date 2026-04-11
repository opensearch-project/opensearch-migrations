// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/cache"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
	"strings"
)

type Ingest struct {
	MinIndexingRate float64 `json:"minIndexingRate,omitempty"` //GB/hour
	MaxIndexingRate float64 `json:"maxIndexingRate,omitempty"` //GB/hour
	TimePerDayAtMax float64 `json:"timePerDayAtMax,omitempty"` //hours per day at maximum rate

	MinOCU       float64 `json:"minOCU,omitempty"`       //=MAX(2,CEILING(MinIndexingRate/IndexingGbHourOcu,1))
	MaxOCU       float64 `json:"maxOCU,omitempty"`       //=MAX(2,CEILING(MaxIndexingRate/IndexingGbHourOcu,1))
	OCUHoursADay float64 `json:"OCUHoursADay,omitempty"` //=MinOCU*(24-TimePerDayAtMax)+MaxOCU*TimePerDayAtMax
	Replicas     int     `json:"replicas"`

	MinOCUCalc       string `json:"minOCUCalc,omitempty"`
	MaxOCUCalc       string `json:"maxOCUCalc,omitempty"`
	OCUHoursADayCalc string `json:"OCUHoursADayCalc,omitempty"`
}

func (i *Ingest) Normalize() {
	if i.MaxIndexingRate < i.MinIndexingRate {
		i.MaxIndexingRate = i.MinIndexingRate
	}
}

type TimeSeries struct {
	DailyIndexSize float64 `json:"dailyIndexSize,omitempty"` //in GBs
	Replicas       int     `json:"replicas"`
	DaysInHot      int     `json:"daysInHot,omitempty"`
	DaysInWarm     int     `json:"daysInWarm,omitempty"`
	MinQueryRate   int64   `json:"minQueryRate,omitempty"` // queries per hour
	MaxQueryRate   int64   `json:"maxQueryRate,omitempty"` // queries per hour
	HoursAtMaxRate float64 `json:"hoursAtMaxRate,omitempty"`

	// Storage compression options
	DerivedSource   bool `json:"derivedSource,omitempty"`   // Enable derived source (~30% storage reduction)
	ZstdCompression bool `json:"zstdCompression,omitempty"` // Enable ZSTD compression (~20% storage reduction)

	MinOCU        float64 `json:"minOCU,omitempty"`       //=MAX(2,CEILING(HotIndexSize/HotGbPerOcu+WarmIndexSize/WarmGbPerOcu,1)*MAX(2,CEILING(minQueryRate/(ReadsPerSecondPerReplica*3600))))
	MaxOCU        float64 `json:"maxOCU,omitempty"`       //=MAX(2,CEILING(HotIndexSize/HotGbPerOcu+WarmIndexSize/WarmGbPerOcu,1)*MAX(2,CEILING(maxQueryRate/(ReadsPerSecondPerReplica*3600))))
	OCUHoursADay  float64 `json:"OCUHoursADay,omitempty"` //=MinOCU*(24-HoursAtMaxRate)+MaxOCU*HoursAtMaxRate
	HotIndexSize  float64 `json:"hotIndexSize,omitempty"`
	WarmIndexSize float64 `json:"warmIndexSize,omitempty"`

	MinOCUCalc       string `json:"minOCUCalc,omitempty"`
	MaxOCUCalc       string `json:"maxOCUCalc,omitempty"`
	OCUHoursADayCalc string `json:"OCUHoursADayCalc,omitempty"`
}

func (t *TimeSeries) Normalize() {
	if t.DaysInHot <= 0 {
		t.DaysInHot = 1 // at least one hot day is required.
	}
	// if days in warm is not set, set it to 0
	if t.DaysInWarm <= 0 {
		t.DaysInWarm = 0
	}
	if t.MaxQueryRate < t.MinQueryRate {
		t.MaxQueryRate = t.MinQueryRate
	}
}

type Search struct {
	CollectionSize float64 `json:"collectionSize,omitempty"`
	Replicas       int     `json:"replicas"`
	MinQueryRate   int64   `json:"minQueryRate,omitempty"` // queries per hour
	MaxQueryRate   int64   `json:"maxQueryRate,omitempty"` // queries per hour
	HoursAtMaxRate float64 `json:"hoursAtMaxRate,omitempty"`

	// Storage compression options
	DerivedSource   bool `json:"derivedSource,omitempty"`   // Enable derived source (~25% storage reduction)
	ZstdCompression bool `json:"zstdCompression,omitempty"` // Enable ZSTD compression (~15% storage reduction)

	MinOCU       float64 `json:"minOCU,omitempty"`       //=MAX(2,CEILING(CollectionSize/HotGbPerOcu,1)*MAX(2,CEILING(MinQueryRate/(QueriesPerSecondPerReplica*3600))))
	MaxOCU       float64 `json:"maxOCU,omitempty"`       //=MAX(2,CEILING(CollectionSize/HotGbPerOcu,1)*MAX(2,CEILING(MaxQueryRate/(QueriesPerSecondPerReplica*3600))))
	OCUHoursADay float64 `json:"OCUHoursADay,omitempty"` //=MinOCU*(24-HoursAtMaxRate)+MaxOCU*HoursAtMaxRate

	MinOCUCalc       string `json:"minOCUCalc,omitempty"`
	MaxOCUCalc       string `json:"maxOCUCalc,omitempty"`
	OCUHoursADayCalc string `json:"OCUHoursADayCalc,omitempty"`
}

func (s *Search) Normalize() {
	if s.MaxQueryRate < s.MinQueryRate {
		s.MaxQueryRate = s.MinQueryRate
	}
}

type Vector struct {
	DataSize                   float64 `json:"size"`
	Replicas                   int64   `json:"replicas"`
	Region                     string  `json:"region"`
	VectorMemoryCircuitBreaker int     `json:"vectorMemoryCircuitBreaker"`

	//vector related
	PQEdges  int `json:"PQEdges"`
	Segments int `json:"segments"`

	DocumentSize  int64 `json:"documentSize,omitempty"`
	IncrementHint int64 `json:"incrementHint,omitempty"`
	Replica       int64 `json:"replica,omitempty"`

	VectorEngineType string `json:"vectorEngineType,omitempty"`
	VectorCount      int64  `json:"vectorCount,omitempty"`
	DimensionsCount  int64  `json:"dimensionsCount,omitempty"`
	MaxEdges         int    `json:"maxEdges,omitempty"`
	SubVectors       int    `json:"subVectors,omitempty"`
	NList            int    `json:"nlist,omitempty"`
	CodeSize         int    `json:"codeSize,omitempty"`

	// On-disk compression support (same as provisioned)
	OnDisk           bool `json:"onDisk,omitempty"`
	CompressionLevel int  `json:"compressionLevel,omitempty"`

	// Storage compression options (applies to non-vector data storage)
	DerivedSource   bool `json:"derivedSource,omitempty"`   // Enable derived source (~30% storage reduction for non-vector data)
	ZstdCompression bool `json:"zstdCompression,omitempty"` // Enable ZSTD compression (~20% storage reduction for non-vector data)

	IndexOCU                float64 `json:"indexOCU"`
	SearchOCU               float64 `json:"searchOCU"`
	CollectionSize          float64 `json:"collectionSize"`
	VectorGraphSizeInMemory float64 `json:"vectorGraphInMemory"`

	VectorMemoryCalc string `json:"vectorMemoryCalc,omitempty"`
	IndexOCUCalc     string `json:"indexOCUCalc,omitempty"`
	SearchOCUCalc    string `json:"searchOCUCalc,omitempty"`

	Config string `json:"config"`
}

// Normalize sets some default values if the corresponding fields are invalid.
//
// - If `IncrementHint` is less than 1000, it will be set to 1000.
// - If `Replicas` is negative, it will be set to 0 if `Config` is "dev", or 1 if `Config` is "prod".
// - If `MaxEdges` is less than 1, it will be set to 16.
// - If `VectorEngineType` is empty, it will be set to "hnsw".
// - If `SubVectors` is less than 1, it will be set to 8.
// - If `NList` is less than 1, it will be set to 4.
// - If `CodeSize` is less than 1, it will be set to 8.
// - Validates and normalizes on-disk compression settings.
func (v *Vector) Normalize() {
	if v.IncrementHint < 1000 {
		v.IncrementHint = 1000
	}
	if v.Replicas < 0 || strings.EqualFold("prod", v.Config) {
		v.Replicas = 1
	} else if strings.EqualFold("dev", v.Config) {
		v.Replicas = 0
	}
	if v.MaxEdges < 1 {
		v.MaxEdges = 16
	}
	if v.VectorEngineType == "" {
		v.VectorEngineType = "hnsw"
	}
	if v.SubVectors < 1 {
		v.SubVectors = 8
	}
	if v.NList < 1 {
		v.NList = 4
	}
	if v.CodeSize < 1 {
		v.CodeSize = 8
	}

	// Validate on-disk compression settings
	v.validateOnDiskMode()
}

type EstimateRequest struct {
	Ingest     Ingest      `json:"ingest"`
	Search     *Search     `json:"search,omitempty"`
	TimeSeries *TimeSeries `json:"timeSeries,omitempty"`
	Vector     *Vector     `json:"vector,omitempty"`
	Region     string      `json:"region"`
	Edp        float64     `json:"edp"`
	Redundancy *bool       `json:"redundancy"` // Default: true (redundancy on)
}

// Normalize normalizes the fields of an EstimateRequest.
//
// - It normalizes the fields of `Ingest`.
// - If `Search` is not nil, it normalizes the fields of `Search`.
// - If `TimeSeries` is not nil, it normalizes the fields of `TimeSeries`.
// - If `Vector` is not nil, it normalizes the fields of `Vector`.
// - It normalizes the region input to ensure backward compatibility.
// - It sets the default redundancy value to true if not explicitly set.
func (er *EstimateRequest) Normalize() {
	// Normalize region input to canonical display name for cache lookup
	er.Region = regions.NormalizeRegionInput(er.Region)

	// Set default redundancy to true (redundancy on) if not explicitly set
	if er.Redundancy == nil {
		redundancyOn := true
		er.Redundancy = &redundancyOn
	}

	er.Ingest.Normalize()
	if er.Search != nil {
		er.Search.Normalize()

	}
	if er.TimeSeries != nil {
		er.TimeSeries.Normalize()
	}
	if er.Vector != nil {
		er.Vector.Normalize()
	}
}

type EstimationResponse struct {
	Ingest     Ingest         `json:"ingest"`
	Search     *Search        `json:"search,omitempty"`
	TimeSeries *TimeSeries    `json:"timeSeries,omitempty"`
	Vector     *Vector        `json:"vector,omitempty"`
	Region     string         `json:"region"`
	Price      price.Estimate `json:"price"`
	Edp        float64        `json:"edp"`
	Redundancy bool           `json:"redundancy"` // Redundancy setting used in calculations
}

// calculatePrice calculates the price for the given EstimationResponse.
//
// It calculates the price for ingest, search and time series, and vector.
// It uses the region price from the cache to calculate the price.
// It sets the Edp, daily ingest OCU, daily search OCU, monthly S3 cost
// and total price in the `EstimationResponse`.
func (er *EstimationResponse) calculatePrice() {
	if er.Region != "" {
		pr, err := cache.GetServerlessPrice().GetRegionPrice(er.Region)
		if err == nil {
			epr := price.Estimate{Edp: er.Edp}
			//1. ingest
			epr.SetDailyIngestOcu(er.Ingest.OCUHoursADay * pr.IndexingOCU.Price)
			//2. Search or time series
			dailyPrice, s3Price := 0.0, 0.0
			if er.Search != nil {
				dailyPrice = float64(er.Search.OCUHoursADay) * pr.SearchOCU.Price
				s3Price = er.Search.CollectionSize * pr.S3ByteHour.Price
			} else if er.TimeSeries != nil {
				dailyPrice = float64(er.TimeSeries.OCUHoursADay) * pr.SearchOCU.Price
				totalStorage := er.TimeSeries.HotIndexSize + er.TimeSeries.WarmIndexSize
				s3Price = totalStorage * pr.S3ByteHour.Price
			} else if er.Vector != nil {
				dailyPrice = er.Vector.SearchOCU * 24 * pr.SearchOCU.Price
				s3Price = er.Vector.CollectionSize * pr.S3ByteHour.Price
			}
			epr.SetDailySearchOcu(dailyPrice)
			epr.SetMonthlyS3cost(s3Price)
			epr.UpdateTotal()
			er.Price = epr
		}
	}
}
