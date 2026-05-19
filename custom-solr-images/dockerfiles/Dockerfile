FROM amazoncorretto:8-alpine

ARG SOLR_VERSION=6.6.0
ARG SOLR_SHA1=ab74116e049cfb603b84226e13d0f7c51de9aa23
ENV SOLR_HOME=/var/solr/data
ENV PATH=/opt/solr/bin:$PATH
ENV LANG=C.UTF-8

RUN set -eux; \
  apk add --no-cache \
      aws-cli \
      bash \
      ca-certificates \
      curl \
      dcron \
      inotify-tools \
      lsof \
      procps \
      shadow \
      su-exec \
      tini \
      tzdata \
      util-linux \
      zip;

RUN --mount=type=cache,target=/var/cache/downloads,sharing=locked \
  set -eux; \
  ZIPFILE=/var/cache/downloads/solr-${SOLR_VERSION}.zip; \
  [ -s "$ZIPFILE" ] || curl -fsSLo "$ZIPFILE" \
    "https://artifacts.alfresco.com/nexus/content/repositories/public/org/apache/solr/solr/${SOLR_VERSION}/solr-${SOLR_VERSION}.zip"; \
  echo "${SOLR_SHA1}  $ZIPFILE" | sha1sum -c -; \
  mkdir -p /var/solr; \
  groupadd -r -g 8983 solr; \
  useradd  -r -u 8983 -g solr -m -d "${SOLR_HOME}" -s /bin/bash solr; \
  unzip "$ZIPFILE" -d /opt; \
  mv "/opt/solr-${SOLR_VERSION}" /opt/solr; \
  cp /opt/solr/server/solr/solr.xml /opt/solr/server/solr-template.solr.xml; \
  cp /opt/solr/server/solr/zoo.cfg  /opt/solr/server/solr-template.zoo.cfg; \
  chown -R solr:solr /opt/solr "${SOLR_HOME}"

COPY --chmod=0755 entrypoint.sh /usr/local/bin/entrypoint.sh

USER solr
WORKDIR ${SOLR_HOME}
EXPOSE 8983

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:8983/solr/admin/info/system?wt=json" > /dev/null || exit 1

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/entrypoint.sh"]
