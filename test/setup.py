import setuptools

with open("requirements.txt") as f:
    required_packages = f.read().splitlines()

setuptools.setup(
    name='integ_test',
    version='0.1',
    description='End-to-End integrations test',
    author='OpenSearch Migrations',
    packages=setuptools.find_packages(),
    python_requires=">=3.9",
    install_requires=required_packages,
)