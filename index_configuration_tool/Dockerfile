# Multistage build - 1st stage
FROM python:3.11 AS builder
COPY requirements.txt .

# Install dependencies to local user directory
RUN pip install --user -r requirements.txt

# Multistage build - 2nd stage
FROM python:3.11-slim
WORKDIR /code
# Copy only required dependencies
COPY --from=builder /root/.local /root/.local
# Copy only source code and Logstash grammar
COPY ./*.py .
COPY logstash.lark .

# update PATH
ENV PATH=/root/.local:$PATH

# make sure you include the -u flag to have our stdout logged
ENTRYPOINT [ "python", "-u", "./main.py" ]
