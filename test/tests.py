from operations import Operations
from http import HTTPStatus
import unittest
import os

# This code is probably existing as a placeholder only for testing purposes,
# final code here will be using comparator's results. Also, won't be sending request directly to target cluster,
# and will be using the replayer instead, so we're able to check comparator's results once the replayer outputs
# the triples log file.
# TODO: Add endpoints to be read from environment variable.


class MyTestCase(unittest.TestCase):
    def test_index(self):
        endpoint1 = os.getenv('ENDPOINT_1', 'http://localhost:9200')
        endpoint2 = os.getenv('ENDPOINT_2')
        index = "my_index"
        response1 = Operations.create_index(endpoint1, index)
        response2 = Operations.create_index(endpoint2, index)
        self.assert_(response1.status_code == HTTPStatus.OK and response2.status_code == HTTPStatus.OK)
        # TODO: check comparator's results here, and add more logging to know where exactly the test fails, if it does.

        response1 = Operations.delete_index(endpoint1, index)
        response2 = Operations.delete_index(endpoint2, index)
        self.assert_(response1.status_code == HTTPStatus.OK and response2.status_code == HTTPStatus.OK)

    def test_document(self):
        endpoint1 = os.getenv('ENDPOINT_1', 'http://localhost:9200')
        endpoint2 = os.getenv('ENDPOINT_2')
        index = "my_index2"
        doc_id = '7'
        response1 = Operations.create_index(endpoint1, index)
        response2 = Operations.create_index(endpoint2, index)
        self.assert_(response1.status_code == HTTPStatus.OK and response2.status_code == HTTPStatus.OK)

        response1 = Operations.create_document(endpoint1, index, doc_id)
        response2 = Operations.create_document(endpoint2, index, doc_id)
        # TODO: check comparator's results here, and add more logging to know where exactly the test fails, if it does.
        self.assert_(response1.status_code == HTTPStatus.CREATED and response2.status_code == HTTPStatus.CREATED)

        response1 = Operations.delete_document(endpoint1, index, doc_id)
        response2 = Operations.delete_document(endpoint2, index, doc_id)
        self.assert_(response1.status_code == HTTPStatus.OK and response2.status_code == HTTPStatus.OK)

        response1 = Operations.delete_index(endpoint1, index)
        response2 = Operations.delete_index(endpoint2, index)
        # TODO: check comparator's results here, and add more logging to know where exactly the test fails, if it does.
        self.assert_(response1.status_code == HTTPStatus.OK and response2.status_code == HTTPStatus.OK)

    def test_unsupported_transformation(self):

        pass

    def test_supported_transformation(self):

        pass


if __name__ == '__main__':
    unittest.main()
