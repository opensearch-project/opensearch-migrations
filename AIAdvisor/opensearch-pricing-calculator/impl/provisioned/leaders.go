// Copyright OpenSearch Contributors
// SPDX-License-Identifier: Apache-2.0

package provisioned

import (
	"errors"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/cache"
	"github.com/opensearch-project/opensearch-pricing-calculator/impl/price"
	"strings"
)

var x86Leaders = map[string]string{
	"1-10":     "m5.large.search",
	"11-30":    "m5.xlarge.search",
	"31-60":    "m5.2xlarge.search",
	"61-120":   "m5.2xlarge.search",
	"121-240":  "m5.4xlarge.search",
	"241-480":  "m5.8xlarge.search",
	"481-1002": "r5.8xlarge.search",
}
var x86Gen7Leaders = map[string]string{
	"1-10":     "m7i.large.search",
	"11-30":    "m7i.xlarge.search",
	"31-60":    "m7i.2xlarge.search",
	"61-120":   "m7i.2xlarge.search",
	"121-240":  "m7i.4xlarge.search",
	"241-480":  "m7i.8xlarge.search",
	"481-1002": "r7i.8xlarge.search",
}
var g2Leaders = map[string]string{
	"1-10":     "m6g.large.search",
	"11-30":    "m6g.xlarge.search",
	"31-60":    "m6g.2xlarge.search",
	"61-120":   "m6g.2xlarge.search",
	"121-240":  "m6g.4xlarge.search",
	"241-480":  "m6g.8xlarge.search",
	"481-1002": "r6g.8xlarge.search",
}
var g3Leaders = map[string]string{
	"1-10":     "m7g.large.search",
	"11-30":    "m7g.xlarge.search",
	"31-60":    "m7g.2xlarge.search",
	"61-120":   "m7g.2xlarge.search",
	"121-240":  "m7g.4xlarge.search",
	"241-480":  "m7g.8xlarge.search",
	"481-1002": "r7g.8xlarge.search",
}
var g4Leaders = map[string]string{
	"1-10":     "m8g.large.search",
	"11-30":    "m8g.xlarge.search",
	"31-60":    "m8g.2xlarge.search",
	"61-120":   "m8g.2xlarge.search",
	"121-240":  "m8g.4xlarge.search",
	"241-480":  "m8g.8xlarge.search",
	"481-1002": "r8g.8xlarge.search",
}

// getLeaderNodesFor takes a HotNodes object, an optional WarmNodes object, and an optional ColdStorage object
// and returns a ClusterConfig object.
// If the `DedicatedManager` flag is set to true, it will calculate the leader nodes required for the given hot nodes
// and add the leader nodes cost to the total cost.
// If `WarmNodes` is not nil, it will add the warm nodes cost to the total cost.
// If `ColdStorage` is not nil, it will add the cold storage cost to the total cost.
// If the `Edp` field is set, it will calculate the discount based on the total cost and the Edp percentage,
// and then calculate the discounted total cost.
// If there is an error while calculating the leader nodes, it will return an empty ClusterConfig and an error.
func getLeaderNodesFor(hotNodeType string, region string, count int) (leaderNodes *HotNodes, err error) {
	provisionedPrice, _ := cache.GetRegionProvisionedPrice(region)

	countKey := getCountKey(count)
	leaderMaps := getLeaderMaps(hotNodeType)

	var node *price.InstanceUnit
	for _, leaderMap := range leaderMaps {
		if node, _ = provisionedPrice.GetHotNode(leaderMap[countKey]); node != nil {
			break
		}
	}

	if node == nil {
		return nil, errors.New("corresponding leader nodes are not available in this region")
	}

	leaderNodes = &HotNodes{
		Type:   node.InstanceType,
		Count:  3,
		Family: node.Family,
	}
	leaderNodes.CalculatePrice(*node, 0, 0)
	return
}

func getCountKey(count int) string {
	switch {
	case count <= 10:
		return "1-10"
	case count <= 30:
		return "11-30"
	case count <= 60:
		return "31-60"
	case count <= 120:
		return "61-120"
	case count <= 240:
		return "121-240"
	case count <= 480:
		return "241-480"
	default:
		return "481-1002"
	}
}

func getLeaderMaps(hotNodeType string) []map[string]string {
	switch {
	case strings.Contains(hotNodeType, "6g.") || strings.Contains(hotNodeType, "6gd.") || strings.Contains(hotNodeType, "4gn.") || strings.HasPrefix(hotNodeType, "i4g."):
		return []map[string]string{g2Leaders}
	case strings.HasPrefix(hotNodeType, "or1") || strings.Contains(hotNodeType, "7g.") || strings.Contains(hotNodeType, "7gd."):
		return []map[string]string{g3Leaders, g2Leaders}
	case strings.HasPrefix(hotNodeType, "or2") || strings.Contains(hotNodeType, "8g.") || strings.Contains(hotNodeType, "8gd.") || strings.HasPrefix(hotNodeType, "om2.") || strings.HasPrefix(hotNodeType, "oi2."):
		return []map[string]string{g4Leaders, g3Leaders, g2Leaders}
	case strings.Contains(hotNodeType, "7i"):
		return []map[string]string{x86Gen7Leaders, x86Leaders}
	default:
		return []map[string]string{x86Leaders}
	}
}
