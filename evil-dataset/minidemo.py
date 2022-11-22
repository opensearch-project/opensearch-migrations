import random
from base64 import b64encode
import json


class BaseFieldProvider():
	_field_name = None

	def __init__(self, seed=None):
		self.random = random.Random(seed) # Each class having its own random instance is important becasue it means you get the 
		self.value_generator = self.generate_field()
		self._count = 0

	def generate(self):
		pass

	def generate_field(self):
		while True:
			yield {self._field_name: self.generate()}

	def generate_queries(self, version=None) -> str:
		pass

	def generate_type_mapping(self) -> str:
		pass

	def field_name(self) -> str:
		return self._field_name


class DocumentProvider():
	def __init__(self, field_providers):
		self.field_providers = field_providers
		self.generators = [fp.value_generator for fp in self.field_providers]

	def generate_document(self):
		doc = {}
		for field in self.generators:
			try:
				doc.update(next(field))
			except StopIteration:
				continue
		return doc

	def generate_type_mapping(self):
		return {
			"mappings": {
				"properties": {
					fp.field_name(): fp.generate_type_mapping() for fp in self.field_providers if fp.generate_type_mapping() is not None
				}
			}
		}



class BinaryValueFieldProvider(BaseFieldProvider):
	_field_name = "binary_value"
	_type_mapping = {"type": "binary"}

	def __init__(self, seed=None):
		super().__init__(seed)

	def generate(self) -> str:
		length = self.random.randint(0,1000)
		value = self.random.randbytes(length)
		return b64encode(value).decode()

	def generate_type_mapping(self) -> dict:
		return self._type_mapping


class ByteValueFieldProvider(BaseFieldProvider):
	_field_name = "byte_value"
	_type_mapping = {"type": "byte"}

	def __init__(self, seed=None):
		super().__init__(seed)

	def generate(self) -> int:
		return self.random.randrange(-128, 127, 1)

	def generate_type_mapping(self) -> dict:
		return self._type_mapping


class FieldNameWithDotsProvider(BaseFieldProvider):
	_field_name = "field.with.dots"

	def __init__(self, seed=None):
		super().__init__(seed)

	def generate(self) -> str:
		return "hello world"

	def generate_field(self):
		yield {self._field_name: self.generate()}


FIELDS = {
	'data_type_coverage': [
		BinaryValueFieldProvider,
		ByteValueFieldProvider
	],
	'edge_cases': [
		FieldNameWithDotsProvider
	]
}


def main(index, n_rows, data_output_file, mapping_output_file, seed=None):
	field_providers = [field_provider(seed) for field_provider in FIELDS['data_type_coverage'] + FIELDS['edge_cases']]
	doc_provider = DocumentProvider(field_providers)

	docs = []
	for i in range(n_rows):
		doc = doc_provider.generate_document()
		docs.append(doc)

	type_mapping = doc_provider.generate_type_mapping()

	index_row = {'index': {'_index': index}}

	with open(data_output_file, 'w') as f:
		for doc in docs:
			f.write(json.dumps(index_row))
			f.write("\n")
			f.write(json.dumps(doc))
			f.write("\n")

	with open(mapping_output_file, 'w') as f:
		json.dump(type_mapping, f)


if __name__ == "__main__":
	main("sample_index", 10, "dataset.json", "mapping.json")

# curl -XPUT 'https://localhost:9202/sample_index?pretty' -ku "admin:admin" -H "Content-Type: application/x-ndjson" --data-binary @mapping.json
# curl -XPOST 'https://localhost:9202/_bulk?pretty' -ku "admin:admin" -H "Content-Type: application/x-ndjson" --data-binary @dataset.json
# curl 'https://localhost:9202/sample_index/_count?pretty' -ku "admin:admin"
# curl 'https://localhost:9202/sample_index/_mappings?pretty' -ku "admin:admin"
