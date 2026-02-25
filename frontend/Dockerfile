FROM nginx:stable-alpine3.21-slim

# copy exported static site
COPY out /usr/share/nginx/html

# overwrite default Nginx config
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY build/version/VERSION /VERSION

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
