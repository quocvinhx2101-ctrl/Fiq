FROM eclipse-temurin:17-jdk-noble AS distribution
ARG SPARK_VERSION=4.0.1
ARG LIVY_VERSION=0.9.0-incubating
RUN apt-get update && apt-get install -y --no-install-recommends curl procps python3 && rm -rf /var/lib/apt/lists/* \
 && curl -fL "https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-hadoop3.tgz" -o /tmp/spark.tgz \
 && tar -xzf /tmp/spark.tgz -C /opt && mv "/opt/spark-${SPARK_VERSION}-bin-hadoop3" /opt/spark && rm /tmp/spark.tgz \
 && curl -fL "https://archive.apache.org/dist/incubator/livy/${LIVY_VERSION}/apache-livy-${LIVY_VERSION}_2.12-bin.zip" -o /tmp/livy.zip \
 && jar xf /tmp/livy.zip && mv "apache-livy-${LIVY_VERSION}_2.12-bin" /opt/livy && rm /tmp/livy.zip \
 && chmod 0755 /opt/livy/bin/* \
 && useradd --system --uid 10002 --create-home livy && mkdir -p /opt/fiq /opt/livy/logs \
 && chown -R livy:livy /opt/livy /opt/fiq

FROM eclipse-temurin:21-jdk-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl procps python3 \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10002 --create-home livy \
 && mkdir -p /opt/fiq /home/livy/.ivy2.5.2 \
 && chown -R livy:livy /home/livy/.ivy2.5.2
COPY --from=distribution --chown=livy:livy /opt/spark /opt/spark
COPY --from=distribution --chown=livy:livy /opt/livy /opt/livy
ENV SPARK_HOME=/opt/spark LIVY_HOME=/opt/livy JAVA_HOME=/opt/java/openjdk
COPY --chown=livy:livy livy.conf /opt/livy/conf/livy.conf
COPY --chown=livy:livy spark-defaults.conf /opt/spark/conf/spark-defaults.conf
USER 10002
EXPOSE 8998
CMD ["/opt/livy/bin/livy-server"]
