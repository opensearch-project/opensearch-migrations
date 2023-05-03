# syntax=docker/dockerfile:1
FROM python:3.10.10

RUN apt-get update && apt-get install -y netcat && apt-get install -y git

# Be weary that this layer can be cached and not capture recent commits. Run docker build --no-cache to avoid this
RUN git clone https://github.com/opensearch-project/traffic-comparator.git

WORKDIR /traffic-comparator

COPY run_app.sh .

RUN pip3 install --editable .

CMD ./run_app.sh

