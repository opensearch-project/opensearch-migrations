# Validation Dataset: Amazon Customer Reviews

## Overview

The validation framework uses the [Amazon Customer Reviews Dataset](https://s3.amazonaws.com/amazon-reviews-pds/readme.html), a publicly available collection of product reviews. The download script pulls ~200K+ reviews across multiple product categories (Electronics, Books, Digital Music, Home, Apparel).

## Download

```bash
python3 data/generate_dataset.py                          # Default: 200K docs → data/dataset.json
python3 data/generate_dataset.py --max-docs 50000         # Smaller subset for quick testing
python3 data/generate_dataset.py --output /tmp/data.json  # Custom output path
```

## Field Descriptions

| Field | Type | Solr Type | OpenSearch Type | Query Features |
|---|---|---|---|---|
| `id` | string | `string` | `keyword` | Unique document identifier |
| `product_title` | text | `text_general` | `text` | Term, phrase, boolean queries; highlighting; field list |
| `review_body` | text | `text_general` | `text` | Term, phrase, boolean queries; highlighting; field list |
| `review_headline` | text | `text_general` | `text` | Term, phrase queries; highlighting; field list |
| `product_category` | keyword | `string` | `keyword` | Terms facets; filter queries |
| `marketplace` | keyword | `string` | `keyword` | Terms facets; filter queries |
| `product_id` | keyword | `string` | `keyword` | Terms facets; filter queries |
| `star_rating` | integer | `pint` | `integer` | Range queries; range facets; boosting; sorting |
| `helpful_votes` | integer | `pint` | `integer` | Range queries; boosting expressions; sorting |
| `total_votes` | integer | `pint` | `integer` | Range queries; function queries; sorting |
| `review_date` | date | `pdate` | `date` | DateTime range facets; range queries; sorting |
| `verified_purchase` | boolean | `boolean` | `boolean` | Filter queries |
| `vine` | boolean | `boolean` | `boolean` | Filter queries |

## Why This Dataset

- 200K+ documents for realistic pagination and facet cardinality
- Multiple text fields for highlighting and field list validation
- Numeric fields with natural ranges (1-5 star ratings, vote counts)
- Date field spanning years for datetime range facets
- Keyword fields with high cardinality (thousands of categories/products)
- Boolean fields for filter query testing
