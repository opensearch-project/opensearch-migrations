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

# Include the -u flag to have stdout logged
ENTRYPOINT python -u ./orchestrator.py $DATA_PREPPER_PATH $ICT_CODE_PATH/input.yaml https://localhost:4900
