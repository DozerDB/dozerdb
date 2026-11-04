# Adapted from docker-image-src/calver/coredb/Dockerfile-trixie of Neo4j's Docker image,
# https://github.com/neo4j/docker-neo4j.
FROM debian:trixie-slim
ENV JAVA_HOME=/opt/java/openjdk
COPY --from=eclipse-temurin:21 $JAVA_HOME $JAVA_HOME
ARG NEO4J_TARBALL=dozerdb-2026.10.1.0-unix.tar.gz
# NEO4J_EDITION stays community: the entrypoint's remaining edition-gated paths are the cluster advertised
# addresses, which do not apply to DozerDB. DozerDB's metrics are not edition-gated, so the entrypoint prepares
# /metrics (below) unconditionally.
ENV PATH="${JAVA_HOME}/bin:${PATH}" \
    NEO4J_TARBALL=${NEO4J_TARBALL} \
    NEO4J_EDITION=community \
    NEO4J_HOME="/var/lib/neo4j" \
    LANG=C.UTF-8
COPY ${NEO4J_TARBALL} ${NEO4J_TARBALL}.sha256 /

COPY ./local-package/* /startup/

RUN apt-get update \
    && apt-get install --no-install-recommends -o Acquire::Retries=10 -y \
      curl ca-certificates gcc libc-dev git jq make procps tini wget \
    && groupadd --gid 7474 --system neo4j \
    && useradd --uid 7474 --system --no-create-home --home "${NEO4J_HOME}" --gid neo4j neo4j \
    && sha256sum -c --strict --quiet "${NEO4J_TARBALL}.sha256" \
    && tar --extract --file ${NEO4J_TARBALL} --directory /var/lib \
    && mv /var/lib/dozerdb-* "${NEO4J_HOME}" \
    && rm "${NEO4J_TARBALL}" "${NEO4J_TARBALL}.sha256" \
    && sed -i 's/Package Type:.*/Package Type: docker trixie/' $NEO4J_HOME/packaging_info \
    && mv /startup/neo4j-admin-report.sh "${NEO4J_HOME}"/bin/neo4j-admin-report \
    && mv "${NEO4J_HOME}"/data /data \
    && mv "${NEO4J_HOME}"/logs /logs \
    && mkdir /metrics \
    && chown -R neo4j:neo4j /data \
    && chmod -R 777 /data \
    && chown -R neo4j:neo4j /logs \
    && chmod -R 777 /logs \
    && chown -R neo4j:neo4j /metrics \
    && chmod -R 777 /metrics \
    && chown -R neo4j:neo4j "${NEO4J_HOME}" \
    && chmod -R 777 "${NEO4J_HOME}" \
    && chmod -R 755 "${NEO4J_HOME}/bin" \
    && ln -s /data "${NEO4J_HOME}"/data \
    && ln -s /logs "${NEO4J_HOME}"/logs \
    && ln -sT /metrics "${NEO4J_HOME}"/metrics \
    && git clone https://github.com/ncopa/su-exec.git \
    && cd su-exec \
    && git checkout 4c3bb42b093f14da70d8ab924b487ccfbb1397af \
    && echo d6c40440609a23483f12eb6295b5191e94baf08298a856bab6e15b10c3b82891 su-exec.c | sha256sum -c \
    && echo 2a87af245eb125aca9305a0b1025525ac80825590800f047419dc57bba36b334 Makefile | sha256sum -c \
    && make \
    && mv /su-exec/su-exec /usr/bin/su-exec \
    && apt-get -y purge --auto-remove curl gcc git make libc-dev \
    && rm -rf /var/lib/apt/lists/* /su-exec


ENV PATH="${NEO4J_HOME}"/bin:$PATH

WORKDIR "${NEO4J_HOME}"

# /metrics is prepared like /data and /logs above (owned by neo4j, world-writable, the home's metrics/ pointing at
# it; the archive ships no metrics/ directory, and ln -sT fails if a future one does, so it would be moved like
# data/ and logs/), and the entrypoint points server.directories.metrics at it. It is not declared a volume: metrics
# are regenerable output, and a declared volume would leave an anonymous volume behind every container; mount it
# to keep the files.
VOLUME /data /logs

# Only the ports that always listen are exposed. The Prometheus endpoint, port 2004, listens only when
# server.metrics.prometheus.enabled=true (publish it then: -p 2004:2004), and 2003 is the port of the Graphite
# server the Graphite exporter connects out to, so neither is exposed.
EXPOSE 7474 7473 7687

ENTRYPOINT ["tini", "-g", "--", "/startup/docker-entrypoint.sh"]
CMD ["neo4j"]
