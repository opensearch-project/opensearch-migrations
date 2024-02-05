#!/bin/bash

if [ "$EXPERIMENTAL" = "true" ]; then
    python3 /console/console_api/manage.py runserver_plus 0.0.0.0:8000
else
    # TODO: Replace command below with "tail -f /dev/null" once env variable used.
    python3 /console/console_api/manage.py runserver_plus 0.0.0.0:8000
fi

