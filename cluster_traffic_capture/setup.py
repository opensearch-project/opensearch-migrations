import setuptools

setuptools.setup(
    name="cluster_traffic_capture",
    version="0.1",
    description=("Code and configuration to help capture primary Elasticsearch/OpenSearch cluster traffic for replay"
                 " onto shadow clusters"),
    author="opensearch-migrations",
    package_dir={"": "cluster_traffic_capture"},
    packages=setuptools.find_packages(where="cluster_traffic_capture"),
    install_requires=[
    ],
    python_requires=">=3.6",
)
