import random
from base64 import b64encode
import json
from os import urandom


class BaseFieldProvider():
    _field_name = None

    def __init__(self, seed):
        # Each class having its own random instance is important becasue it means you get the same
        # values when you rerun it, even if new values have been added in the meantime.
        self.random = random.Random(seed)
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
        length = self.random.randint(0, 1000)
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


def main(index, n_docs, data_output_file, mapping_output_file, field_set=None, seed=None):
    # Python doesn't have a random.getseed() type method, so it's necessary to specifically set a seed.
    if seed is None:
        seed = int.from_bytes(urandom(64))

    print("To reproduce this run, seed is: ", seed)

    # If field_set is None, use all fields, otherwise only the selected one.
    if field_set is None:
        # (I know this is an ugly way to do this)
        field_providers = [field_provider(seed) for field_provider
                           in FIELDS['data_type_coverage'] + FIELDS['edge_cases']]
    else:
        field_providers = [field_provider(seed) for field_provider
                           in FIELDS[field_set]]

    doc_provider = DocumentProvider(field_providers)

    docs = []
    for i in range(n_docs):
        doc = doc_provider.generate_document()
        docs.append(doc)

    # Generate type mapping and write to file.
    type_mapping = doc_provider.generate_type_mapping()
    with open(mapping_output_file, 'w') as f:
        json.dump(type_mapping, f)

    # Write all docs to file, formatted for an index bulk upload
    index_row = {'index': {'_index': index}}
    with open(data_output_file, 'w') as f:
        for doc in docs:
            f.write(json.dumps(index_row))
            f.write("\n")
            f.write(json.dumps(doc))
            f.write("\n")


if __name__ == "__main__":
    print("Simple use case where all fields are included and no seed is provided. Data output to `dataset.json` and `mapping.json`")
    main("sample_index", 10, "dataset.json", "mapping.json")

    print()
    print("Example with only the data_type_coverage fields. Data output to `data_type_coverage_dataset.json` and `data_type_coverage_mapping.json`")
    main("data_type_coverage_index", 20, "data_type_coverage_dataset.json",
         "data_type_coverage_mapping.json", field_set='data_type_coverage', seed=10)
