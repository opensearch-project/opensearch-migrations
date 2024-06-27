#!/bin/bash

# Example usage: ./setupIntegTests.sh https://github.com/opensearch-project/opensearch-migrations.git main http://opense-clust-test.elb.us-east-1.amazonaws.com:19200 http://opense-clust-test.elb.us-east-1.amazonaws.com:9200 integ_min_1705067071260_127

git_http_url=$1
branch=$2
source_endpoint=$3
proxy_endpoint=$4
unique_id=$5

mkdir -p /root/integ-tests
cd /root/integ-tests || exit
git init
remote_exists=$(git remote -v | grep origin)
if [ -z "${remote_exists}" ]; then
  echo "No remote detected, adding 'origin'"
  git remote add -f origin "$git_http_url"
else
  echo "Existing 'origin' remote detected, updating to $git_http_url"
  git remote set-url origin "$git_http_url"
fi

git config core.sparseCheckout true
# Check file exists and contains sparse-checkout
if test -f .git/info/sparse-checkout; then
  sparse_entry=$(cat .git/info/sparse-checkout | grep "/test")
  if [ -z "${sparse_entry}" ]; then
    echo "No '/test' entry in '.git/info/sparse-checkout' file detected, will attempt to add"
    git remote add -f origin "$git_http_url"
  else
    echo "Have detected '/test' entry in '.git/info/sparse-checkout' file, no changes needed"
  fi
else
  echo "File '.git/info/sparse-checkout' not found, will attempt to create"
  echo "/test" >> .git/info/sparse-checkout
fi

git pull origin "$branch"
cd test || exit
pip install pipenv

pipenv install
echo "Starting 'tests.py'"
# TODO Add support to dynamically skip certain tests depending on setup. Currently, forcing negative auth tests not to run here as the source/target cluster has no auth
set -o xtrace
pipenv run pytest tests.py::E2ETests::test_0001_index tests.py::E2ETests::test_0002_document tests.py::E2ETests::test_0005_invalidIncorrectUri tests.py::E2ETests::test_0006_OSB --proxy_endpoint="${proxy_endpoint}" --source_endpoint="${source_endpoint}" --target_endpoint="${MIGRATION_DOMAIN_ENDPOINT}" --source_auth_type="none" --target_auth_type="none" --source_verify_ssl=False --target_verify_ssl=False --deployment_type="cloud" --unique_id="${unique_id}" --verbose --junitxml="./reports/${unique_id}.xml"
set +o xtrace
deactivate