#!/usr/bin/env bash

set -o errexit
set -o pipefail
set -o nounset
set -o xtrace

python manage.py runserver_plus --reloader-type=stat 0.0.0.0:8000
