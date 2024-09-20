FROM opensearchproject/data-prepper:2.5.0

# Set environment variables
ENV FM_CODE_PATH=/code \
    PATH=/root/.local:$PATH

# Set working directory
WORKDIR $FM_CODE_PATH

# Copy Pipfile and Pipfile.lock
COPY python/Pipfile python/Pipfile.lock ./

# Install dependencies
RUN apt-get update && apt-get install -y \
    python3.11 \
    python3-pip \
    && pip3 install --no-cache-dir pipenv \
    && pipenv install --deploy --ignore-pipfile --python /usr/bin/python3.11 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Copy source code
COPY python/*.py ./

# Write custom DP config
RUN echo -e "ssl: false\n\
    metricRegistries: [Prometheus]\
    " > "$DATA_PREPPER_PATH/config/data-prepper-config.yaml"

# Include the -u flag to have unbuffered stdout (for detached mode)
ENTRYPOINT ["pipenv", "run", "python3", "-u", "./fetch_orchestrator.py", "--insecure", "$DATA_PREPPER_PATH", "$FM_CODE_PATH/input.yaml"]
