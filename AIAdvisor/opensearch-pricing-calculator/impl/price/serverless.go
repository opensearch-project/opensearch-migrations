// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package price

const ServerlessPricingUrl = "https://b0.p.awsstatic.com/pricing/2.0/meteredUnitMaps/es/USD/current/es-serverless.json"

// SecretServerlessPricingUrl is the serverless pricing endpoint for the aws-iso-b partition.
// Serverless is not currently available in Secret regions but will be fetched when it becomes available.
const SecretServerlessPricingUrl = "https://calculator.aws/pricing/2.0/meteredUnitMaps/aws-iso-b/es/USD/current/es-serverless.json"

// TopSecretServerlessPricingUrl is the serverless pricing endpoint for the aws-iso partition.
// Serverless is not currently available in Top Secret regions but will be fetched when it becomes available.
const TopSecretServerlessPricingUrl = "https://calculator.aws/pricing/2.0/meteredUnitMaps/aws-iso/es/USD/current/es-serverless.json"

const DaysPerYear = 365
const DaysPerMonth = DaysPerYear / 12.0

type Unit struct {
	RateCode string  `json:"rateCode,omitempty"`
	Price    float64 `json:"price,omitempty"`
}

type ServerlessRegion struct {
	IndexingOCU Unit `json:"indexingOCU"`
	SearchOCU   Unit `json:"searchOCU"`
	S3ByteHour  Unit `json:"s3GBHour"`
}

type Serverless struct {
	IndexOcu        float64 `json:"indexOcu,omitempty"`
	SearchOcu       float64 `json:"searchOcu,omitempty"`
	S3Storage       float64 `json:"s3Storage,omitempty"`
	Edp             float64 `json:"edp,omitempty"`
	Discount        float64 `json:"discount,omitempty"`
	Total           float64 `json:"total,omitempty"`
	DiscountedTotal float64 `json:"discountedTotal,omitempty"`
}

type Estimate struct {
	Day   Serverless `json:"day,omitempty"`
	Month Serverless `json:"month"`
	Year  Serverless `json:"year"`
	Edp   float64    `json:"edp,omitempty"`
}

// SetDailyIngestOcu sets the daily ingest OCU price in the Estimate.
//
// It sets the daily ingest OCU price in the Day, Month and Year fields
// of the Estimate. The monthly price is calculated by dividing the
// yearly price by 12, and the yearly price is calculated by multiplying
// the daily price by 365.
func (pr *Estimate) SetDailyIngestOcu(price float64) {
	pr.Day.IndexOcu = price
	pr.Year.IndexOcu = price * DaysPerYear
	pr.Month.IndexOcu = pr.Year.IndexOcu / 12
}

// SetDailySearchOcu sets the daily search OCU price in the Estimate.
//
// It sets the daily search OCU price in the Day, Month and Year fields
// of the Estimate. The monthly price is calculated by dividing the
// yearly price by 12, and the yearly price is calculated by multiplying
// the daily price by 365.
func (pr *Estimate) SetDailySearchOcu(price float64) {
	pr.Day.SearchOcu = price
	pr.Year.SearchOcu = price * DaysPerYear
	pr.Month.SearchOcu = pr.Year.SearchOcu / 12
}

// SetMonthlyS3cost sets the monthly S3 storage price in the Estimate.
//
// It sets the monthly S3 storage price in the Day, Month and Year fields
// of the Estimate. The daily price is calculated by dividing the monthly
// price by 30, and the yearly price is calculated by multiplying the
// monthly price by 12.
func (pr *Estimate) SetMonthlyS3cost(price float64) {
	pr.Day.S3Storage = price / DaysPerMonth
	pr.Year.S3Storage = price * 12
	pr.Month.S3Storage = price
}

// UpdateTotal updates the total, discount, and discounted total prices in the Estimate.
//
// It updates the total, discount, and discounted total prices in the Day, Month and Year fields
// of the Estimate. The total price is calculated by summing the ingest OCU, search OCU, and S3
// storage prices. The discount is calculated by multiplying the total price by the Edp percentage.
// The discounted total is calculated by subtracting the discount from the total price.
func (pr *Estimate) UpdateTotal() {
	pr.Day.Total = pr.Day.IndexOcu + pr.Day.SearchOcu + pr.Day.S3Storage
	pr.Day.Discount = pr.Day.Total * (pr.Edp / 100)
	pr.Day.DiscountedTotal = pr.Day.Total - pr.Day.Discount

	pr.Year.Total = pr.Year.IndexOcu + pr.Year.SearchOcu + pr.Year.S3Storage
	pr.Year.Discount = pr.Year.Total * (pr.Edp / 100)
	pr.Year.DiscountedTotal = pr.Year.Total - pr.Year.Discount

	pr.Month.Total = pr.Month.IndexOcu + pr.Month.SearchOcu + pr.Month.S3Storage
	pr.Month.Discount = pr.Month.Total * (pr.Edp / 100)
	pr.Month.DiscountedTotal = pr.Month.Total - pr.Month.Discount
}
