FROM opensearch-data-prepper:2.4.0-SNAPSHOT
COPY requirements.txt .

# Install dependencies to local user directory
RUN apk update
RUN apk add --no-cache python3 py-pip
RUN pip install --user -r requirements.txt

WORKDIR /code
# Copy only source code
COPY ./*.py .

# update PATH
ENV PATH=/root/.local:$PATH

# make sure you include the -u flag to have our stdout logged
CMD python -u ./main.py -r $DATA_PREPPER_PATH/pipelines/pipelines.yaml $DATA_PREPPER_PATH/pipelines/pipelines.yaml; $DATA_PREPPER_PATH/bin/data-prepper
