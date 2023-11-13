import unittest

from fetch_orchestrator_params import FetchOrchestratorParams


class TestFetchOrchestratorParams(unittest.TestCase):
    def test_get_local_endpoint(self):
        # Default value for insecure flag (secure endpoint)
        params = FetchOrchestratorParams("test", "test", port=123)
        self.assertEqual("https://localhost:123", params.get_local_endpoint())
        # insecure endpoint
        params = FetchOrchestratorParams("test", "test", port=123, insecure=True)
        self.assertEqual("http://localhost:123", params.get_local_endpoint())


if __name__ == '__main__':
    unittest.main()
