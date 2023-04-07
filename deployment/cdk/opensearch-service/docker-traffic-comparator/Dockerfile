# syntax=docker/dockerfile:1
FROM python:3.10.10

WORKDIR /traffic_comparator
RUN apt-get update && apt-get install -y netcat

# Copying the local necessary files for the comparator to run is only temporary, until the comparator repo is public.
# Then the steps here would be to clone the repo.

COPY traffic_comparator ./traffic_comparator
COPY cli.py .
COPY setup.py .
COPY run_app.sh .

RUN pip3 install --editable .

CMD ./run_app.sh

