FROM opensearchproject/data-prepper:2.5.0
COPY python/requirements.txt .

# Install dependencies to local user directory
RUN apt -y update
RUN apt -y install python3 python3-pip
RUN pip3 install --user -r requirements.txt

ENV FM_CODE_PATH /code
WORKDIR $FM_CODE_PATH
# Copy only source code
COPY python/*.py .

# update PATH
ENV PATH=/root/.local:$PATH

# Write custom DP config
RUN echo "ssl: false" > $DATA_PREPPER_PATH/config/data-prepper-config.yaml
RUN echo "metricRegistries: [Prometheus]" >> $DATA_PREPPER_PATH/config/data-prepper-config.yaml

# Include the -u flag to have unbuffered stdout (for detached mode)
# "$@" allows passing extra args/flags to the entrypoint when the image is run
ENTRYPOINT python3 -u ./fetch_orchestrator.py --insecure $DATA_PREPPER_PATH $FM_CODE_PATH/input.yaml "$@"
