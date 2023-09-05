FROM opensearch-data-prepper:2.4.0-SNAPSHOT
COPY python/requirements.txt .

# Install dependencies to local user directory
RUN apk update
RUN apk add --no-cache python3 py-pip
RUN pip install --user -r requirements.txt

ENV ICT_CODE_PATH /code
WORKDIR $ICT_CODE_PATH
# Copy only source code
COPY python/*.py .

# update PATH
ENV PATH=/root/.local:$PATH

# make sure you include the -u flag to have our stdout logged
ENTRYPOINT python -u ./main.py -r $ICT_CODE_PATH/input.yaml $DATA_PREPPER_PATH/pipelines/pipelines.yaml; $DATA_PREPPER_PATH/bin/data-prepper
