# TODO Move away from snapshot version after OS source is released
# https://github.com/opensearch-project/data-prepper/issues/1985
FROM opensearch-data-prepper:2.5.0-SNAPSHOT
COPY python/requirements.txt .

# Install dependencies to local user directory
RUN apk update
RUN apk add --no-cache python3 py-pip
RUN pip install --user -r requirements.txt

ENV FM_CODE_PATH /code
WORKDIR $FM_CODE_PATH
# Copy only source code
COPY python/*.py .

# update PATH
ENV PATH=/root/.local:$PATH

# Include the -u flag to have stdout logged
ENTRYPOINT python -u ./fetch_orchestrator.py $DATA_PREPPER_PATH $FM_CODE_PATH/input.yaml https://localhost:4900
