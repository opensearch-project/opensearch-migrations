FROM nginx:1.25.1

# Installing VIM for the sake of users who would like to exec shells in the webserver container.
RUN apt-get update
RUN apt-get install -y vim

RUN /bin/bash -c  '\
    export HTMLDIR=/usr/share/nginx/html ; \
    for i in {1..100}; do echo -n t; done > ${HTMLDIR}/100.txt && \
    for i in {1..1000}; do echo -n s; done > ${HTMLDIR}/1K.txt && \
    for i in {1..10}; do cat ${HTMLDIR}/1K.txt | tr s m ; done > ${HTMLDIR}/10K.txt && \
    for i in {1..10}; do cat ${HTMLDIR}/10K.txt | tr m L; done > ${HTMLDIR}/100K.txt && \
    for i in {1..10}; do cat ${HTMLDIR}/100K.txt | tr L X; done > ${HTMLDIR}/1M.txt && \
    for i in {1..10}; do cat ${HTMLDIR}/1M.txt | tr X H; done > ${HTMLDIR}/10M.txt'

COPY nginx.conf /etc/nginx/nginx.conf
#COPY --from=build /usr/src/nginx-${NGINX_VERSION}/objs/ngx_http_echo_module.so /usr/src/nginx-${NGINX_VERSION}/objs/ndk_http_module.so /usr/lib/nginx/modules/

#CMD tail -f /dev/null
CMD nginx -g "daemon off;"