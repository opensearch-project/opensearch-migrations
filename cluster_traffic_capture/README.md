# Cluster Traffic Capture

This package contains code and configuration to facilitate the capture of traffic to an Elasticsearch/OpenSearch cluster while having as minimal an impact on throughput/latency as possible.  The goal is to provide a way to validate the performance of a real workload against a new, prospective cluster that may be configured differently.  The current solution uses HAProxy [1] to synchronously capture the stream of requests/responses to the user's existing ("Primary") cluster while passing traffic through.  It also replicates the traffic to the prospective ("Shadow") cluster using a mirroring Stream Proccessing Offload Agent [2].  You can learn more about this scenario from this blog post [3].  See below for a network diagram and further explanation of the intended usage.

At a high level, the contents are:
* `./build_docker_images.py`: A command-line script that builds Docker images for the Primary and Shadow HAProxy instances
* `./demo_haproxy.py`: A script that executes the `./build_docker_images.py` to generate HAProxy Docker images and stands up Primary/Shadow ES 7.10.2 clusters in Docker containers on the local host with traffic mirroring enabled.
* `./docker_config_traffic_gen/Dockerfile`: A Dockerfile which, when build/run while the `./demo_haproxy.py` setup is running, will drive test traffic to the Primary test cluster using the OpenSearch Benchmarking tool [4].

[1] https://github.com/haproxy/haproxy
[2] https://github.com/haproxytech/spoa-mirror
[3] https://www.haproxy.com/blog/haproxy-traffic-mirroring-for-real-world-testing/
[4] https://github.com/opensearch-project/opensearch-benchmark

## Network Setup

Currently, it is expected that a user wishing to capture traffic for validation would spin up HAProxy instances between the Client and Primary cluster, and between the Primary HAProxy Instance and the Shadow cluster, like so:

                CLIENT
                 /|\
                  | normal traffic
                  |
                  |
                 \|/        mirrors traffic
           PRIMARY HAPROXY ------------------> SHADOW HAPROXY
                 /|\            one-way              /|\
                  |                                   |
                  |                                   |
                  |                                   |
                 \|/                                 \|/
           PRIMARY CLUSTER                     SHADOW CLUSTER

A description of each actor is as follows:
* CLIENT: The user's existing source of traffic to their PRIMARY CLUSTER
* PRIMARY CLUSTER: The user's existing cluster, which they are considering migrating to a new location or configuration, and want to capture the traffic to for validation
* SHADOW CLUSTER: The prospective cluster "under evaluation", which will have the traffic to the PRIMARY CLUSTER replayed against it
* PRIMARY HAPROXY: One or more hosts running the Primary HAProxy Docker image.  Each synchronously passes traffic from the CLIENT to PRIMARY CLUSTER while recording the requests/responses.  Additionally, it mirrors the traffic one-way to the SHADOW HAPROXY host(s).  
* SHADOW HAPROXY: One or more hosts running the Shadow HAProxy Docker image.  Each synchronously passes traffic it receives from the PRIMARY HAPROXY to the SHADOW CLUSER while recording requests/responses.  Traffic received from the SHADOW CLUSTER is not passed back to the PRIMARY HAPROXY or the CLIENT.

## How To Use This Package

### Pre-Requisites

* This github repo cloned locally
* Python3 and venv
* Docker installed on your host
* Currently in the same directory as this README, etc

### Building The Docker Images

#### Step 1 - Activate your Python virtual environment

To isolate the Python environment for the project from your local machine, create virtual environment like so:
```
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

You can exit the Python virtual environment and remove its resources like so:
```
deactivate
rm -rf .venv
```

Learn more about venv [here](https://docs.python.org/3/library/venv.html).

### Step 2 - Build the images

You should now be able to invoke the build script, like so:
```
./build_docker_images.py --primary-image haproxy-primary --primary-nodes host.docker.internal:9200 host.docker.internal:9201 --shadow-haproxy host.docker.internal:81 --shadow-image haproxy-shadow --shadow-nodes host.docker.internal:9202 host.docker.internal:9203 --internal-port 9200
```

This will make two Docker images (`haproxy-primary`, `haproxy-shadow`) that are configured to direct/capture traffic to the primary and shadow cluster nodes you specified.

### Running The Demo

The demo is supposed to provide an example of the setup's intended usage that is easy to test/interrogate on a laptop.  It stands up 6 Docker images: 1 HAProxy Primary, 2 ES 7.10.2 Primary Nodes, 1 HAProxy Shadow, and 2 ES 7.10.2 Shadow Nodes.  The user can send traffic to the Primary HAProxy and see it mirrored to the Shadow Cluster while being logged.

#### Step 1 - Activate your Python virtual environment

To isolate the Python environment for the project from your local machine, create virtual environment like so:
```
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

You can exit the Python virtual environment and remove its resources like so:
```
deactivate
rm -rf .venv
```

Learn more about venv [here](https://docs.python.org/3/library/venv.html).

### Step 2 - Start the demo

You should now be able to invoke the demo script, like so:

```
(.venv) chelma@3c22fba4e266 cluster_traffic_capture % ./demo_haproxy.py
Creating primary cluster...
Waiting up to 30 sec for cluster to be active...
Cluster primary-cluster is active
Creating shadow cluster...
Waiting up to 30 sec for cluster to be active...
Cluster shadow-cluster is active
Building HAProxy Docker images for Primary and Shadow HAProxy containers...
Executing command to build Docker images: ./build_docker_images.py --primary-image haproxy-primary --primary-nodes host.docker.internal:9200 host.docker.internal:9201 --shadow-haproxy host.docker.internal:81 --shadow-image haproxy-shadow --shadow-nodes host.docker.internal:9202 host.docker.internal:9203 --internal-port 9200
Subshell> Copying Docker-related files to: /tmp/cluster_traffic_capture
Subshell> Writing HAProxy Config to: /tmp/cluster_traffic_capture/haproxy_no_mirror.cfg
Subshell> Writing HAProxy Config to: /tmp/cluster_traffic_capture/haproxy_w_mirror.cfg
Subshell> Building HAProxy Docker image for Primary Cluster...
Subshell> Primary HAProxy image available locally w/ tag: haproxy-primary
Subshell> Building HAProxy Docker image for Shadow Cluster...
Subshell> Shadow HAProxy image available locally w/ tag: haproxy-shadow
Starting HAProxy container for Shadow Cluster...
Starting HAProxy container for Primary Cluster...

HAProxy is currently running in a Docker container, available at 127.0.0.1:80, and configured to pass traffic to an
ES 7.10.2 cluster of two nodes (each running in their own Docker containers).  The requests/responses passed to the
cluster via the HAProxy container will be logged to the container at /var/log/haproxy-traffic.log.  The requests are
mirrored to an identical shadow cluster.

Some example commands you can run to demonstrate the behavior are:
curl -X GET 'localhost:80'
curl -X GET 'localhost:80/_cat/nodes?v=true&pretty'
curl -X PUT 'localhost:80/noldor/_doc/1' -H 'Content-Type: application/json' -d'{"name": "Finwe"}'
curl -X GET 'localhost:80/noldor/_doc/1'

When you are done playing with the setup, hit the RETURN key in this terminal window to shut down and clean up the
demo containers.

```

The Docker setup will look something like:
```
chelma@3c22fba4e266 docker_config_traffic_gen % docker ps
CONTAINER ID   IMAGE                                                      COMMAND                  CREATED          STATUS          PORTS                              NAMES
ecd701f2b001   haproxy-primary                                            "/docker-entrypoint.…"   21 seconds ago   Up 20 seconds   0.0.0.0:80->9200/tcp               haproxy-primary
37cba786ff27   haproxy-shadow                                             "/docker-entrypoint.…"   22 seconds ago   Up 21 seconds   0.0.0.0:81->9200/tcp               haproxy-shadow
24764db5e518   docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2   "/tini -- /usr/local…"   36 seconds ago   Up 35 seconds   9300/tcp, 0.0.0.0:9203->9200/tcp   shadow-cluster-node-2
56d4b36aa26d   docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2   "/tini -- /usr/local…"   37 seconds ago   Up 36 seconds   9300/tcp, 0.0.0.0:9202->9200/tcp   shadow-cluster-node-1
c198216efc27   docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2   "/tini -- /usr/local…"   50 seconds ago   Up 50 seconds   9300/tcp, 0.0.0.0:9201->9200/tcp   primary-cluster-node-2
c688ef30cd52   docker.elastic.co/elasticsearch/elasticsearch-oss:7.10.2   "/tini -- /usr/local…"   51 seconds ago   Up 50 seconds   0.0.0.0:9200->9200/tcp, 9300/tcp   primary-cluster-node-1
```

### Step 3 - Test the setup with traffic

Now, you can send traffic to the HAProxy Primary on localhost:80 and see it replicated to the Shadow Cluster.

```
chelma@3c22fba4e266 cluster_traffic_capture % curl -X PUT 'localhost:80/noldor/_doc/1' -H 'Content-Type: application/json' -d'{"name": "Finwe"}'
{"_index":"noldor","_type":"_doc","_id":"1","_version":1,"result":"created","_shards":{"total":2,"successful":1,"failed":0},"_seq_no":0,"_primary_term":1}

chelma@3c22fba4e266 cluster_traffic_capture % curl -X GET 'localhost:9203/noldor/_doc/1'
{"_index":"noldor","_type":"_doc","_id":"1","_version":1,"_seq_no":0,"_primary_term":1,"found":true,"_source":{"name": "Finwe"}}
```


You can also use the OpenSearch Benchmarking tool using the supplied Dockerfile at `./docker_config_traffic_gen/Dockerfile`:
```
cd docker_config_traffic_gen
docker build --tag traffic-gen .
```

```
chelma@3c22fba4e266 docker_config_traffic_gen % docker run --name traffic-gen --add-host host.docker.internal:host-gateway traffic-gen:latest
Running opensearch-benchmark w/ 'geonames' workload...

   ____                  _____                      __       ____                  __                         __
  / __ \____  ___  ____ / ___/___  ____ ___________/ /_     / __ )___  ____  _____/ /_  ____ ___  ____ ______/ /__
 / / / / __ \/ _ \/ __ \\__ \/ _ \/ __ `/ ___/ ___/ __ \   / __  / _ \/ __ \/ ___/ __ \/ __ `__ \/ __ `/ ___/ //_/
/ /_/ / /_/ /  __/ / / /__/ /  __/ /_/ / /  / /__/ / / /  / /_/ /  __/ / / / /__/ / / / / / / / / /_/ / /  / ,<
\____/ .___/\___/_/ /_/____/\___/\__,_/_/   \___/_/ /_/  /_____/\___/_/ /_/\___/_/ /_/_/ /_/ /_/\__,_/_/  /_/|_|
    /_/


--------------------------------
[INFO] SUCCESS (took 27 seconds)
--------------------------------
Running opensearch-benchmark w/ 'http_logs' workload...

   ____                  _____                      __       ____                  __                         __
  / __ \____  ___  ____ / ___/___  ____ ___________/ /_     / __ )___  ____  _____/ /_  ____ ___  ____ ______/ /__
 / / / / __ \/ _ \/ __ \\__ \/ _ \/ __ `/ ___/ ___/ __ \   / __  / _ \/ __ \/ ___/ __ \/ __ `__ \/ __ `/ ___/ //_/
/ /_/ / /_/ /  __/ / / /__/ /  __/ /_/ / /  / /__/ / / /  / /_/ /  __/ / / / /__/ / / / / / / / / /_/ / /  / ,<
\____/ .___/\___/_/ /_/____/\___/\__,_/_/   \___/_/ /_/  /_____/\___/_/ /_/\___/_/ /_/_/ /_/ /_/\__,_/_/  /_/|_|
    /_/


--------------------------------
[INFO] SUCCESS (took 26 seconds)
--------------------------------
Running opensearch-benchmark w/ 'nested' workload...

   ____                  _____                      __       ____                  __                         __
  / __ \____  ___  ____ / ___/___  ____ ___________/ /_     / __ )___  ____  _____/ /_  ____ ___  ____ ______/ /__
 / / / / __ \/ _ \/ __ \\__ \/ _ \/ __ `/ ___/ ___/ __ \   / __  / _ \/ __ \/ ___/ __ \/ __ `__ \/ __ `/ ___/ //_/
/ /_/ / /_/ /  __/ / / /__/ /  __/ /_/ / /  / /__/ / / /  / /_/ /  __/ / / / /__/ / / / / / / / / /_/ / /  / ,<
\____/ .___/\___/_/ /_/____/\___/\__,_/_/   \___/_/ /_/  /_____/\___/_/ /_/\___/_/ /_/_/ /_/ /_/\__,_/_/  /_/|_|
    /_/


--------------------------------
[INFO] SUCCESS (took 14 seconds)
--------------------------------
Running opensearch-benchmark w/ 'nyc_taxis' workload...

   ____                  _____                      __       ____                  __                         __
  / __ \____  ___  ____ / ___/___  ____ ___________/ /_     / __ )___  ____  _____/ /_  ____ ___  ____ ______/ /__
 / / / / __ \/ _ \/ __ \\__ \/ _ \/ __ `/ ___/ ___/ __ \   / __  / _ \/ __ \/ ___/ __ \/ __ `__ \/ __ `/ ___/ //_/
/ /_/ / /_/ /  __/ / / /__/ /  __/ /_/ / /  / /__/ / / /  / /_/ /  __/ / / / /__/ / / / / / / / / /_/ / /  / ,<
\____/ .___/\___/_/ /_/____/\___/\__,_/_/   \___/_/ /_/  /_____/\___/_/ /_/\___/_/ /_/_/ /_/ /_/\__,_/_/  /_/|_|
    /_/


--------------------------------
[INFO] SUCCESS (took 13 seconds)
--------------------------------
```

The image is pre-configured to send traffic to localhost:80.  You can see the logged traffic on the Primary and Shadow HAProxy in their `/var/log`
```
chelma@3c22fba4e266 ~ % docker exec -it haproxy-shadow bash
root@37cba786ff27:/# tail -n 5 /var/log/haproxy-traffic.log
Feb 10 19:04:15 localhost haproxy[23]: Request-URI: /_stats/_all?level=shards#012Request-Method: GET#012Request-Body: -#012Response-Body: {"_shards":{"total":49,"successful":49,"failed":0},"_all":{"primaries":{"docs":{"count":1,"deleted":0},"store":{"size_in_bytes":13683,"reserved_in_bytes":0},"indexing":{"index_total":1,"index_time_in_millis":13,"index_current":0,"index_failed":0,"delete_total":0,"delete_time_in_millis":0,"delete_current":0,"noop_update_total":0,"is_throttled":false,"throttle_time_in_millis":0},"get":{"total":0,"time_in_millis":0,"exists_total":0,"exists_time_in_millis":0,"missing_total":0,"missing_time_in_millis":0,"current":0},"search":{"open_contexts":0,"query_total":0,"query_time_in_millis":0,"query_current":0,"fetch_total":0,"fetch_time_in_millis":0,"fetch_current":0,"scroll_total":0,"scroll_time_in_millis":0,"scroll_current":0,"suggest_total":0,"suggest_time_in_millis":0,"suggest_current":0},"merges":{"current":0,"current_docs":0,"current_size_in_bytes":0,"total":0,"total_time_in_millis":0,"total_do
Feb 10 19:04:15 localhost haproxy[23]: Request-URI: /.ml-anomalies-*/_search#012Request-Method: POST#012Request-Body: {"size":0,"query":{"bool":{"must":[{"term":{"result_type":"bucket"}}]}},"aggs":{"jobs":{"terms":{"field":"job_id"},"aggs":{"min_pt":{"min":{"field":"processing_time_ms"}},"max_pt":{"max":{"field":"processing_time_ms"}},"mean_pt":{"avg":{"field":"processing_time_ms"}},"median_pt":{"percentiles":{"field":"processing_time_ms","percents":[50]}}}}}}#012Response-Body: {"took":0,"timed_out":false,"_shards":{"total":0,"successful":0,"skipped":0,"failed":0},"hits":{"total":{"value":0,"relation":"eq"},"max_score":0.0,"hits":[]}}
Feb 10 19:04:20 localhost haproxy[23]: Request-URI: /_bulk#012Request-Method: POST#012Request-Body: -#012Response-Body: -
Feb 10 19:04:20 localhost haproxy[23]: message repeated 4 times: [ Request-URI: /_bulk#012Request-Method: POST#012Request-Body: -#012Response-Body: -]
Feb 10 19:04:22 localhost haproxy[23]: Request-URI: /nyc_taxis/_search#012Request-Method: GET#012Request-Body: -#012Response-Body: -
```

### Step 4 - Clean up the demo setup

As the terminal output suggestion, you can spin down the demo setup and clean up all created resources by hitting RETURN in the original terminal:

```
Stopping cluster primary-cluster...
Cleaning up underlying resources for cluster primary-cluster...
Stopping cluster shadow-cluster...
Cleaning up underlying resources for cluster shadow-cluster...
Cleaning up underlying resources for the Primary HAProxy container...
Cleaning up underlying resources for the Shadow HAProxy container...
(.venv) chelma@3c22fba4e266 cluster_traffic_capture %
```