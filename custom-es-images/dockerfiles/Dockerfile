# ============================================================
# Dockerfile — All ES versions (1.x–8.x) on Amazon Corretto
# Default: Alpine runtime for minimal size
# ES 5.0-5.2: glibc runtime (AL2023) to avoid musl SIGSEGV crashes
# ============================================================
ARG CORRETTO_VERSION=8
ARG BASE_IMAGE=amazoncorretto:${CORRETTO_VERSION}-alpine
ARG CGROUP_FIXER_BASE=alpine:3.18
ARG ES_VERSION
ARG TARBALL_FILE

# --- Stage 1: Extract pre-downloaded tarball (AL2023) ---
FROM amazonlinux:2023 AS downloader
ARG ES_VERSION
ARG TARBALL_FILE

COPY ${TARBALL_FILE} /tmp/es.tar.gz

RUN dnf install -y tar gzip findutils && \
    mkdir -p /opt/elasticsearch && \
    tar -xzf /tmp/es.tar.gz --strip-components=1 -C /opt/elasticsearch && \
    rm /tmp/es.tar.gz && \
    cd /opt/elasticsearch && \
    # Remove bundled JDK (we use Corretto)
    rm -rf jdk && \
    # Remove Windows files
    rm -f bin/*.bat bin/*.exe && \
    # Remove docs/legal files
    rm -f NOTICE.txt LICENSE.txt README.textile README.asciidoc README.md && \
    # Remove ML native binaries (~200-400MB in 7.x+/8.x)
    rm -rf modules/x-pack-ml/platform && \
    # Also handle ES 6.x layout where ML is under modules/x-pack/
    rm -rf modules/x-pack/x-pack-ml/platform && \
    # Remove unnecessary native code in other modules
    rm -rf modules/x-pack-sql/platform && \
    rm -rf modules/x-pack/x-pack-sql/platform && \
    # Remove ingest-geoip data only for 8.x+ (where it's downloaded on demand)
    MAJOR=$(echo "${ES_VERSION}" | cut -d. -f1) && \
    if [ "$MAJOR" -ge 8 ]; then rm -rf modules/ingest-geoip/*.mmdb 2>/dev/null; fi && \
    # Remove Sigar native libs (ES 1.x-2.x) — glibc-compiled, crash on Alpine/musl
    rm -rf lib/sigar 2>/dev/null; true

# --- Stage 2: Build cgroups v2 fix for ES 5.1-5.2 (must match runtime libc) ---
FROM ${CGROUP_FIXER_BASE} AS cgroup-fixer
ARG ES_VERSION

RUN MAJOR=$(echo "${ES_VERSION}" | cut -d. -f1) && \
    MINOR=$(echo "${ES_VERSION}" | cut -d. -f2) && \
    mkdir -p /opt/fix && \
    if [ "$MAJOR" -eq 5 ] && [ "$MINOR" -le 2 ]; then \
      if command -v apk >/dev/null 2>&1; then \
        apk add --no-cache gcc musl-dev; \
      else \
        dnf install -y gcc glibc-devel; \
      fi && \
      printf '#define _GNU_SOURCE\n#include <stdio.h>\n#include <string.h>\n#include <dlfcn.h>\n#include <fcntl.h>\n#include <stdarg.h>\n#include <unistd.h>\nstatic const char *rp="/proc/self/cgroup";\nstatic const char *fp="/tmp/cgroup_filtered";\nstatic const char *fix(const char *p){return (p&&strcmp(p,rp)==0)?fp:p;}\ntypedef int (*open_t)(const char*,int,...);\nint open(const char *p,int f,...){open_t r=(open_t)dlsym(RTLD_NEXT,"open");mode_t m=0;if(f&O_CREAT){va_list a;va_start(a,f);m=va_arg(a,mode_t);va_end(a);}return r(fix(p),f,m);}\nint open64(const char *p,int f,...){open_t r=(open_t)dlsym(RTLD_NEXT,"open64");mode_t m=0;if(f&O_CREAT){va_list a;va_start(a,f);m=va_arg(a,mode_t);va_end(a);}return r(fix(p),f,m);}\nint openat(int d,const char *p,int f,...){typedef int(*fn)(int,const char*,int,...);fn r=(fn)dlsym(RTLD_NEXT,"openat");mode_t m=0;if(f&O_CREAT){va_list a;va_start(a,f);m=va_arg(a,mode_t);va_end(a);}return r(d,fix(p),f,m);}\ntypedef FILE*(*fopen_t)(const char*,const char*);\nFILE*fopen(const char*p,const char*m){fopen_t r=(fopen_t)dlsym(RTLD_NEXT,"fopen");return r(fix(p),m);}\nFILE*fopen64(const char*p,const char*m){fopen_t r=(fopen_t)dlsym(RTLD_NEXT,"fopen64");return r(fix(p),m);}\n' > /tmp/fix.c && \
      gcc -shared -fPIC -o /opt/fix/libcgroup_fix.so /tmp/fix.c -ldl; \
    else \
      touch /opt/fix/.skip; \
    fi

# --- Stage 3: Prepare version-specific config and entrypoint (AL2023) ---
FROM amazonlinux:2023 AS config-builder
ARG ES_VERSION

RUN MAJOR=$(echo "${ES_VERSION}" | cut -d. -f1) && \
    MINOR=$(echo "${ES_VERSION}" | cut -d. -f2) && \
    mkdir -p /tmp/config /tmp/scripts && \
    # Base config
    printf 'cluster.name: docker-cluster\nnode.name: es-node\npath.repo: /tmp/snapshots\n' > /tmp/config/elasticsearch.yml && \
    # Disable disk-based allocation decisions entirely for CI/test
    printf 'cluster.routing.allocation.disk.threshold_enabled: false\n' >> /tmp/config/elasticsearch.yml && \
    # Network and discovery
    if [ "$MAJOR" -le 2 ]; then \
      printf 'network.host: 0.0.0.0\ndiscovery.zen.minimum_master_nodes: 1\n' >> /tmp/config/elasticsearch.yml; \
    elif [ "$MAJOR" -eq 5 ] && [ "$MINOR" -lt 4 ]; then \
      printf 'http.host: 0.0.0.0\ntransport.host: _local_\ndiscovery.zen.minimum_master_nodes: 1\n' >> /tmp/config/elasticsearch.yml; \
    elif [ "$MAJOR" -eq 5 ]; then \
      printf 'network.host: 0.0.0.0\ndiscovery.type: single-node\n' >> /tmp/config/elasticsearch.yml; \
    else \
      printf 'network.host: 0.0.0.0\ndiscovery.type: single-node\n' >> /tmp/config/elasticsearch.yml; \
    fi && \
    # Security settings (X-Pack bundled from 6.3+)
    if [ "$MAJOR" -ge 8 ]; then \
      printf 'xpack.security.enabled: false\nxpack.security.enrollment.enabled: false\nxpack.security.http.ssl.enabled: false\nxpack.security.transport.ssl.enabled: false\n' >> /tmp/config/elasticsearch.yml; \
    elif [ "$MAJOR" -eq 7 ] || { [ "$MAJOR" -eq 6 ] && [ "$MINOR" -ge 3 ]; }; then \
      printf 'xpack.security.enabled: false\n' >> /tmp/config/elasticsearch.yml; \
    fi && \
    # Disable ML native code (stripped from image to save ~200-400MB per 7.x+/8.x image)
    if [ "$MAJOR" -ge 7 ] || { [ "$MAJOR" -eq 6 ] && [ "$MINOR" -ge 3 ]; }; then \
      printf 'xpack.ml.enabled: false\n' >> /tmp/config/elasticsearch.yml; \
    fi && \
    # Entrypoint script
    if [ "$MAJOR" -eq 5 ] && [ "$MINOR" -le 2 ]; then \
      printf '#!/bin/bash\ngrep -v "^0::" /proc/self/cgroup > /tmp/cgroup_filtered\nexport LD_PRELOAD=/usr/local/lib/libcgroup_fix.so\nexport ES_JAVA_OPTS="${ES_JAVA_OPTS} -XX:-UseContainerSupport"\nexec /usr/share/elasticsearch/bin/elasticsearch "$@"\n' > /tmp/scripts/entrypoint.sh; \
    else \
      printf '#!/bin/bash\nexec /usr/share/elasticsearch/bin/elasticsearch "$@"\n' > /tmp/scripts/entrypoint.sh; \
    fi && \
    chmod +x /tmp/scripts/entrypoint.sh

# --- Stage 4: Runtime ---
FROM ${BASE_IMAGE}

ARG ES_VERSION
LABEL org.opencontainers.image.title="custom-elasticsearch" \
      org.opencontainers.image.version="${ES_VERSION}" \
      org.opencontainers.image.description="Custom Elasticsearch ${ES_VERSION} on Amazon Corretto"

ENV ES_HOME=/usr/share/elasticsearch \
    ES_VERSION=${ES_VERSION}

# Install minimal runtime deps — bash for ES scripts, curl for healthcheck
# Detect package manager to support both Alpine (apk) and AL2023 (dnf)
RUN if command -v apk >/dev/null 2>&1; then \
      apk add --no-cache bash coreutils curl && \
      addgroup -g 1000 elasticsearch && \
      adduser -u 1000 -G elasticsearch -h ${ES_HOME} -D elasticsearch; \
    else \
      dnf install -y --allowerasing bash coreutils curl hostname shadow-utils && dnf clean all && \
      groupadd -g 1000 elasticsearch && \
      useradd -u 1000 -g elasticsearch -d ${ES_HOME} -M elasticsearch; \
    fi && \
    mkdir -p /tmp/snapshots && chown 1000:1000 /tmp/snapshots

COPY --from=downloader --chown=1000:1000 /opt/elasticsearch ${ES_HOME}
COPY --from=cgroup-fixer /opt/fix/libcgroup_fix.so* /usr/local/lib/
COPY --from=config-builder /tmp/scripts/entrypoint.sh /usr/local/bin/entrypoint.sh
COPY --from=config-builder /tmp/config/elasticsearch.yml ${ES_HOME}/config/elasticsearch.yml

# ES 7+ uses ES_JAVA_HOME — point it to the Corretto JAVA_HOME from base image
ENV ES_JAVA_HOME=${JAVA_HOME}

RUN mkdir -p ${ES_HOME}/data ${ES_HOME}/logs && \
    chown -R 1000:1000 ${ES_HOME}

WORKDIR ${ES_HOME}
USER 1000

EXPOSE 9200 9300

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
  CMD curl -sf http://localhost:9200/_cluster/health || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
