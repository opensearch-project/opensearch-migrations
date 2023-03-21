# syntax=docker/dockerfile:1

FROM python:3.8-slim-buster

ARG MIGRATION_ENDPOINT
ENV MIGRATION_ENDPOINT ${MIGRATION_ENDPOINT}

WORKDIR /app

COPY requirements.txt requirements.txt
RUN pip3 install -r requirements.txt

COPY . .

CMD [ "sh", "-c", "python3 -u app.py ${MIGRATION_ENDPOINT}"]