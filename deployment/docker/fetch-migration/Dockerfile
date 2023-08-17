FROM fetch-migration:latest
COPY ./run_app.sh .
ENV INLINE_PIPELINE ""

# Override entrypoint in parent image
ENTRYPOINT ./run_app.sh
