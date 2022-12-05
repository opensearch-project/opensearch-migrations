# Trivial Example

This example doesn't demonstrate an edge case, but is intended to be a simple example of loading data and querying it as proof of concept and a template for future development.

It loads three documents with different dates and then queries with a date range with an inclusive upper bound that should catch two of the three documents.

The jq filter pulls out the number of hits and the names of the hits -- this ensures that we're getting the correct two files.