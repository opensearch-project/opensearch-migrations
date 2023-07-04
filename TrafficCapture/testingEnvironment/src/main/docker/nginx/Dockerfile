ARG NGINX_VERSION=1.23.4
FROM nginx:${NGINX_VERSION}  as build

RUN apt-get update && apt-get upgrade -y
RUN apt-get install -y \
            openssh-client \
            git \
            wget \
            libxml2-dev \
            libxslt-dev \
            libpcre3 \
            libpcre3-dev \
            zlib1g \
            zlib1g-dev \
            openssl \
            libssl-dev \
            libtool \
            automake \
            gcc \
            g++ \
            make && \
        rm -rf /var/cache/apt

RUN wget "http://nginx.org/download/nginx-${NGINX_VERSION}.tar.gz" && \
    tar -C /usr/src -xzvf nginx-${NGINX_VERSION}.tar.gz

#RUN mkdir -p -m 0600 ~/.ssh && \
#    ssh-keyscan github.com >> ~/.ssh/known_hosts

WORKDIR /src/ngx_devel_kit
#RUN --mount=type=ssh git clone git@github.com:simpl/ngx_devel_kit .
RUN git clone https://github.com/vision5/ngx_devel_kit .

WORKDIR /src/echo-nginx-module
#RUN --mount=type=ssh git clone git@github.com:openresty/set-misc-nginx-module.git .
RUN git clone https://github.com/openresty/echo-nginx-module.git .

WORKDIR /usr/src/nginx-${NGINX_VERSION}
RUN NGINX_ARGS=$(nginx -V 2>&1 | sed -n -e 's/^.*arguments: //p') \
    ./configure --with-compat --with-http_ssl_module --add-dynamic-module=/src/ngx_devel_kit --add-dynamic-module=/src/echo-nginx-module ${NGINX_ARGS} && \
    make modules && \
    make install

FROM nginx:${NGINX_VERSION}

# Switch to noninteractive mode to avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get upgrade -y && apt-get install -y --no-install-recommends vim \
    && rm -rf /var/lib/apt/lists/* \
    && apt-get clean

RUN /bin/bash -c  '\
    export HTMLDIR=/usr/share/nginx/html ; \
    for i in {1..100}; do echo -n t; done > ${HTMLDIR}/100.txt && \
    for i in {1..1000}; do echo -n s; done > ${HTMLDIR}/1K.txt && \
    for i in {1..10000}; do echo -n m; done > ${HTMLDIR}/10K.txt && \
    for i in {1..100000}; do echo -n L; done > ${HTMLDIR}/100K.txt && \
    for i in {1..1000000}; do echo -n X; done > ${HTMLDIR}/1M.txt && \
    for i in {1..10000000}; do echo -n H; done > ${HTMLDIR}/10M.txt'

COPY nginx.conf /etc/nginx/nginx.conf
#COPY --from=build /usr/src/nginx-${NGINX_VERSION}/objs/ngx_http_echo_module.so /usr/src/nginx-${NGINX_VERSION}/objs/ndk_http_module.so /usr/lib/nginx/modules/

#CMD tail -f /dev/null
CMD nginx -g "daemon off;"