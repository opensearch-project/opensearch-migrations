FROM public.ecr.aws/amazonlinux/amazonlinux:2023

RUN dnf install -y jq shadow-utils && dnf clean all

RUN useradd -m -s /bin/bash user && \
    mkdir -p /home/user/claude/.claude/skills/solr-opensearch-migration-advisor && \
    mkdir -p /home/user/claude/processing/.claude/skills

# we copy only entrypoint.sh to the main folder, which will switch cwd to
# the claude/processing subfolder before starting claude, to which we also mount the host volume to
# preserve work.
# To avoid ownership and thus visibility conflicts between host and container,
# we copy all relevant agent files to /home/user/claude and use symlinks in entrypoint.sh
# to make them visible in the claude/processing for the agent to consume.
# Rather than mounting directly to /home/user/claude.
# NOTE that the docker build assumes we are in the solr-opensearch-migration-advisor root to be
# able to copy agent files, thus we need to add the path to entrypoint.sh in the below.
COPY ./setup/docker/claude/entrypoint.sh /home/user/entrypoint.sh
COPY SKILL.md /home/user/claude/.claude/skills/solr-opensearch-migration-advisor/SKILL.md
COPY references /home/user/claude/references
COPY scripts /home/user/claude/scripts
COPY steering /home/user/claude/steering
RUN chmod +x /home/user/entrypoint.sh
RUN chown user /home/user/entrypoint.sh
RUN chown -R user /home/user/claude

USER user
WORKDIR /home/user

RUN curl -fsSL https://claude.ai/install.sh | bash

ENV PATH="/home/user/.local/bin:${PATH}"

RUN claude --version

CMD ["tail", "-f", "/dev/null"]