// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package serverless

import (
	"fmt"
	"math"

	"github.com/opensearch-project/opensearch-pricing-calculator/impl/commons"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/regions"
)

const IndexingGbHourOcu float64 = 2.8
const QueriesPerSecondPerReplica float64 = 10
const ReadsPerSecondPerReplica float64 = 1
const HotGbPerOcu float64 = 120
const HalfHotGbPerOcu float64 = HotGbPerOcu / 2
const WarmGbPerOcu float64 = 1800

type RegionsResponse struct {
	Regions []regions.RegionInfo `json:"regions"`
}

// calculate calculates MinOCU, MaxOCU and OCUHoursADay for Ingest.
// If search is not nil, it calculates OCU based on the collection size of search.
// Otherwise, it calculates OCU based on ingest's MinIndexingRate and MaxIndexingRate.
// The formula for OCU calculation is as follows:
// if collection size is less than HotGbPerOcu, then we need 2 OCU to index it
// otherwise, OCU is CEILING(collection size/HotGbPerOcu) * 2
// The formula for OCUHoursADay calculation is as follows:
// OCUHoursADay = MinOCU*(24-TimePerDayAtMax)+MaxOCU*TimePerDayAtMax
// calculateOCUFactor returns the OCU factor based on indexing rate threshold and replica count
func (i *Ingest) calculateOCUFactor(threshold float64, capacity float64) float64 {
	if threshold <= capacity/2 {
		if i.Replicas == 0 {
			return 0.5
		}
		return 1
	}
	if i.Replicas == 1 {
		return 2
	}
	return 1 // Default case
}

// calculateOCUFromRate calculates OCU based on indexing rate and factor
func (i *Ingest) calculateOCUFromRate(rate, factor float64) float64 {
	if factor < 1 {
		return factor
	}
	return math.Max(factor, math.Ceil(rate/IndexingGbHourOcu))
}

func (i *Ingest) calculate(search ...*Search) {
	if len(search) == 0 {
		// Calculate OCU based on indexing rates
		minOCUFactor := i.calculateOCUFactor(i.MinIndexingRate, IndexingGbHourOcu)
		maxOCUFactor := i.calculateOCUFactor(i.MaxIndexingRate, IndexingGbHourOcu)

		i.MinOCU = i.calculateOCUFromRate(i.MinIndexingRate, minOCUFactor)
		i.MaxOCU = i.calculateOCUFromRate(i.MaxIndexingRate, maxOCUFactor)

		if minOCUFactor < 1 {
			i.MinOCUCalc = fmt.Sprintf("ocuFactor:%.1f (minIndexingRate:%.2f <= capacity/2:%.2f, replicas:%d)",
				minOCUFactor, i.MinIndexingRate, IndexingGbHourOcu/2, i.Replicas)
		} else {
			i.MinOCUCalc = fmt.Sprintf("MAX(ocuFactor:%.1f, CEIL(minIndexingRate:%.2f / IndexingGbHourOcu:%.1f))",
				minOCUFactor, i.MinIndexingRate, IndexingGbHourOcu)
		}
		if maxOCUFactor < 1 {
			i.MaxOCUCalc = fmt.Sprintf("ocuFactor:%.1f (maxIndexingRate:%.2f <= capacity/2:%.2f, replicas:%d)",
				maxOCUFactor, i.MaxIndexingRate, IndexingGbHourOcu/2, i.Replicas)
		} else {
			i.MaxOCUCalc = fmt.Sprintf("MAX(ocuFactor:%.1f, CEIL(maxIndexingRate:%.2f / IndexingGbHourOcu:%.1f))",
				maxOCUFactor, i.MaxIndexingRate, IndexingGbHourOcu)
		}
	} else {
		// Calculate OCU based on search collection size
		// Apply compression if enabled (derived source and/or ZSTD)
		effectiveCollectionSize := search[0].CollectionSize
		compressionMultiplier := commons.GetCompressionMultiplier(search[0].DerivedSource, search[0].ZstdCompression, commons.SearchCompressionRatios)
		effectiveCollectionSize *= compressionMultiplier

		ocuFactor := i.calculateOCUFactor(effectiveCollectionSize, HotGbPerOcu)

		if effectiveCollectionSize <= HotGbPerOcu {
			i.MinOCU = ocuFactor
			i.MaxOCU = ocuFactor
			i.MinOCUCalc = fmt.Sprintf("ocuFactor:%.1f (effectiveCollectionSize:%.2f <= HotGbPerOcu:%.0f)",
				ocuFactor, effectiveCollectionSize, HotGbPerOcu)
			i.MaxOCUCalc = i.MinOCUCalc
		} else {
			replicaFactor := math.Max(1, ocuFactor)
			baseOCU := math.Max(ocuFactor, math.Ceil(effectiveCollectionSize/HotGbPerOcu))
			i.MinOCU = baseOCU * replicaFactor
			i.MaxOCU = i.MinOCU
			i.MinOCUCalc = fmt.Sprintf("MAX(ocuFactor:%.1f, CEIL(effectiveCollectionSize:%.2f / HotGbPerOcu:%.0f)) * MAX(1, ocuFactor:%.1f)",
				ocuFactor, effectiveCollectionSize, HotGbPerOcu, ocuFactor)
			i.MaxOCUCalc = i.MinOCUCalc
		}
	}

	i.OCUHoursADay = i.MinOCU*(24-i.TimePerDayAtMax) + i.MaxOCU*i.TimePerDayAtMax
	i.OCUHoursADayCalc = fmt.Sprintf("minOCU:%.2f * (24 - timePerDayAtMax:%.2f) + maxOCU:%.2f * timePerDayAtMax:%.2f",
		i.MinOCU, i.TimePerDayAtMax, i.MaxOCU, i.TimePerDayAtMax)
}

// calculateV2 calculates MinOCU, MaxOCU and OCUHoursADay for TimeSeries.
// MinOCU is calculated as MAX(2,CEILING(HotIndexSize/HotGbPerOcu+WarmIndexSize/WarmGbPerOcu,1)*MAX(2,CEILING(minQueryRate/(ReadsPerSecondPerReplica*3600))))
// MaxOCU is calculated as MAX(2,CEILING(HotIndexSize/HotGbPerOcu+WarmIndexSize/WarmGbPerOcu,1)*MAX(2,CEILING(maxQueryRate/(ReadsPerSecondPerReplica*3600))))
// OCUHoursADay is calculated as MinOCU*(24-HoursAtMaxRate)+MaxOCU*HoursAtMaxRate
func (t *TimeSeries) calculateV2() {
	t.HotIndexSize = t.DailyIndexSize * float64(t.DaysInHot)
	t.WarmIndexSize = t.DailyIndexSize * float64(t.DaysInWarm)

	// Apply compression if enabled (derived source and/or ZSTD)
	compressionMultiplier := commons.GetCompressionMultiplier(t.DerivedSource, t.ZstdCompression, commons.TimeSeriesCompressionRatios)
	t.HotIndexSize *= compressionMultiplier
	t.WarmIndexSize *= compressionMultiplier

	// Simplified logic: if collection fits in HotGbPerOcu/2 and no replicas, minOcuFactor is 0.5
	// Otherwise, minOcuFactor is 2 if replica count is 1
	var minOCUFactor float64
	if t.HotIndexSize+t.WarmIndexSize <= HalfHotGbPerOcu {
		if t.Replicas == 0 {
			minOCUFactor = 0.5
		} else {
			minOCUFactor = 1
		}
	} else if t.Replicas == 1 {
		minOCUFactor = 2
	} else {
		minOCUFactor = 1 // Default case
	}
	hotOCUSize := HotGbPerOcu
	if minOCUFactor == 0.5 {
		hotOCUSize = HalfHotGbPerOcu
	}
	t.MinOCU = math.Max(minOCUFactor, math.Ceil(
		t.HotIndexSize/hotOCUSize+t.WarmIndexSize/WarmGbPerOcu)*
		math.Max(minOCUFactor, float64(t.MinQueryRate)/(ReadsPerSecondPerReplica*3600)))
	t.MaxOCU = math.Max(minOCUFactor, math.Ceil(
		t.HotIndexSize/hotOCUSize+t.WarmIndexSize/WarmGbPerOcu)*
		math.Max(minOCUFactor, float64(t.MaxQueryRate)/(ReadsPerSecondPerReplica*3600)))
	t.OCUHoursADay = t.MinOCU*(24-(t.HoursAtMaxRate)) + t.MaxOCU*t.HoursAtMaxRate

	t.MinOCUCalc = fmt.Sprintf("MAX(minOCUFactor:%.1f, CEIL(hotIndexSize:%.2f / hotOCUSize:%.0f + warmIndexSize:%.2f / WarmGbPerOcu:%.0f) * MAX(minOCUFactor:%.1f, minQueryRate:%d / (ReadsPerSecondPerReplica:%.0f * 3600)))",
		minOCUFactor, t.HotIndexSize, hotOCUSize, t.WarmIndexSize, WarmGbPerOcu, minOCUFactor, t.MinQueryRate, ReadsPerSecondPerReplica)
	t.MaxOCUCalc = fmt.Sprintf("MAX(minOCUFactor:%.1f, CEIL(hotIndexSize:%.2f / hotOCUSize:%.0f + warmIndexSize:%.2f / WarmGbPerOcu:%.0f) * MAX(minOCUFactor:%.1f, maxQueryRate:%d / (ReadsPerSecondPerReplica:%.0f * 3600)))",
		minOCUFactor, t.HotIndexSize, hotOCUSize, t.WarmIndexSize, WarmGbPerOcu, minOCUFactor, t.MaxQueryRate, ReadsPerSecondPerReplica)
	t.OCUHoursADayCalc = fmt.Sprintf("minOCU:%.2f * (24 - hoursAtMaxRate:%.2f) + maxOCU:%.2f * hoursAtMaxRate:%.2f",
		t.MinOCU, t.HoursAtMaxRate, t.MaxOCU, t.HoursAtMaxRate)
}

// calculate calculates MinOCU, MaxOCU and OCUHoursADay for Search.
// MinOCU is calculated as MAX(2,CEILING(CollectionSize/HotGbPerOcu,1)*MAX(2,CEILING(minQueryRate/(QueriesPerSecondPerReplica*3600))))
// MaxOCU is calculated as MAX(2,CEILING(CollectionSize/HotGbPerOcu,1)*MAX(2,CEILING(maxQueryRate/(QueriesPerSecondPerReplica*3600))))
// OCUHoursADay is calculated as MinOCU*(24-HoursAtMaxRate)+MaxOCU*HoursAtMaxRate
func (s *Search) calculate() {
	// Apply compression if enabled (derived source and/or ZSTD)
	effectiveCollectionSize := s.CollectionSize
	compressionMultiplier := commons.GetCompressionMultiplier(s.DerivedSource, s.ZstdCompression, commons.SearchCompressionRatios)
	effectiveCollectionSize *= compressionMultiplier

	// Simplified logic: if collection fits in HalfHotGbPerOcu and no replicas, minOcuFactor is 0.5
	// Otherwise, minOcuFactor is 2 if replica count is 1
	var minOCUFactor float64
	if effectiveCollectionSize <= HalfHotGbPerOcu {
		if s.Replicas == 0 {
			minOCUFactor = 0.5
		} else {
			minOCUFactor = 1
		}
	} else if s.Replicas == 1 {
		minOCUFactor = 2
	} else {
		minOCUFactor = 1 // Default case
	}
	hotOCUSize := HotGbPerOcu
	if minOCUFactor == 0.5 {
		hotOCUSize = HalfHotGbPerOcu
	}
	s.MinOCU = math.Max(minOCUFactor, math.Ceil(effectiveCollectionSize/hotOCUSize)*
		math.Max(minOCUFactor, float64(s.MinQueryRate)/(QueriesPerSecondPerReplica*3600)))
	s.MaxOCU = math.Max(minOCUFactor, math.Ceil(effectiveCollectionSize/hotOCUSize)*
		math.Max(minOCUFactor, float64(s.MaxQueryRate)/(QueriesPerSecondPerReplica*3600)))
	s.OCUHoursADay = s.MinOCU*(24.0-s.HoursAtMaxRate) + s.MaxOCU*s.HoursAtMaxRate

	s.MinOCUCalc = fmt.Sprintf("MAX(minOCUFactor:%.1f, CEIL(effectiveCollectionSize:%.2f / hotOCUSize:%.0f) * MAX(minOCUFactor:%.1f, minQueryRate:%d / (QueriesPerSecondPerReplica:%.0f * 3600)))",
		minOCUFactor, effectiveCollectionSize, hotOCUSize, minOCUFactor, s.MinQueryRate, QueriesPerSecondPerReplica)
	s.MaxOCUCalc = fmt.Sprintf("MAX(minOCUFactor:%.1f, CEIL(effectiveCollectionSize:%.2f / hotOCUSize:%.0f) * MAX(minOCUFactor:%.1f, maxQueryRate:%d / (QueriesPerSecondPerReplica:%.0f * 3600)))",
		minOCUFactor, effectiveCollectionSize, hotOCUSize, minOCUFactor, s.MaxQueryRate, QueriesPerSecondPerReplica)
	s.OCUHoursADayCalc = fmt.Sprintf("minOCU:%.2f * (24 - hoursAtMaxRate:%.2f) + maxOCU:%.2f * hoursAtMaxRate:%.2f",
		s.MinOCU, s.HoursAtMaxRate, s.MaxOCU, s.HoursAtMaxRate)
}

// CalculateV2 calculates the EstimationResponse for a given EstimateRequest.
//
// If the EstimateRequest is for search, it calculates the required OCUs and memory
// for the search collection and the required OCUs and memory for the ingest
// pipeline. It also calculates the price for the required resources.
//
// If the EstimateRequest is for time series, it calculates the required OCUs and
// memory for the time series collection and the required OCUs and memory for the
// ingest pipeline. It also calculates the price for the required resources.
//
// If the EstimateRequest is for vector, it calculates the required OCUs and memory
// for the vector collection and the required OCUs and memory for the ingest
// pipeline. It also calculates the price for the required resources.
//
// The function returns the calculated EstimationResponse.
func (er *EstimateRequest) CalculateV2() (response EstimationResponse, err error) {
	response.Ingest = er.Ingest
	response.Region = er.Region
	response.Edp = er.Edp

	// Pass redundancy setting to response (default is true if not set)
	if er.Redundancy != nil {
		response.Redundancy = *er.Redundancy
	} else {
		response.Redundancy = true // Default to redundancy on
	}
	if response.Redundancy {
		response.Ingest.Replicas = 1
	}

	if er.Search != nil {
		response.Search = er.Search
		if response.Redundancy {
			response.Search.Replicas = 1
		}
		response.Ingest.calculate(response.Search)
		response.Search.calculate()
	} else if er.TimeSeries != nil {
		response.Ingest.calculate()
		response.TimeSeries = er.TimeSeries
		if response.Redundancy {
			response.TimeSeries.Replicas = 1
		}
		response.TimeSeries.calculateV2()
	} else if er.Vector != nil {
		response.Vector = er.Vector
		if response.Redundancy {
			response.Vector.Replicas = 1
		}
		response.Vector.calculateV2()
		response.Ingest.OCUHoursADay = response.Vector.IndexOCU * 24
		response.Ingest.MinOCU = response.Vector.IndexOCU
		response.Ingest.MaxOCU = response.Vector.IndexOCU
		response.Ingest.MinOCUCalc = fmt.Sprintf("vectorIndexOCU:%.2f", response.Vector.IndexOCU)
		response.Ingest.MaxOCUCalc = response.Ingest.MinOCUCalc
		response.Ingest.OCUHoursADayCalc = fmt.Sprintf("vectorIndexOCU:%.2f * 24", response.Vector.IndexOCU)
	}
	response.calculatePrice()
	return
}
