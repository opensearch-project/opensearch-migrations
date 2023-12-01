#!/bin/bash

# Example usage: ./setupIntegTests.sh https://github.com/opensearch-project/opensearch-migrations.git main

git_http_url=$1
branch=$2

# TODO check if exists first
mkdir /root/integ-tests
cd /root/integ-tests || exit
git init
git remote add -f origin $git_http_url
git config core.sparseCheckout true
echo "/test" >> .git/info/sparse-checkout
git pull origin $branch

cd test
pip install virtualenv
# TODO check if .venv exists
virtualenv .venv
source .venv/bin/activate
pip install -r requirements.txt
pytest tests.py
deactivate
