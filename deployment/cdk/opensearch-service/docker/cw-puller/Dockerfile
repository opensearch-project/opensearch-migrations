# syntax=docker/dockerfile:1
FROM python:3.8-slim-buster AS setup
RUN apt-get update && apt-get install -y git

WORKDIR /app

RUN git clone https://github.com/opensearch-project/opensearch-migrations.git

FROM python:3.8-slim-buster
RUN apt-get update && apt-get install -y netcat

WORKDIR /app

#ENV CW_LOG_GROUP_NAME cw-feeder-lg
#ENV CW_LOG_STREAM_NAME cw-feeder-stream

COPY --from=setup /app/opensearch-migrations/TrafficReplayer/pull_then_poll_cw_logs.py .
COPY ./run_app.sh .
# Ideally this should be in opensearch-migrations repo
COPY requirements.txt requirements.txt

RUN pip3 install -r requirements.txt

CMD ./run_app.sh