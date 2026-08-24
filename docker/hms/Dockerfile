FROM apache/hive:4.0.1

USER root
ADD --chmod=0444 https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.7/postgresql-42.7.7.jar /opt/hive/lib/postgresql.jar
# Hive ships the matching Hadoop AWS client under the Hadoop tools directory, while the
# standalone metastore only loads /opt/hive/lib. Copy the matching pair instead of resolving
# a second Hadoop version at runtime.
RUN cp /opt/hadoop/share/hadoop/tools/lib/hadoop-aws-3.3.6.jar /opt/hive/lib/ \
 && cp /opt/hadoop/share/hadoop/tools/lib/aws-java-sdk-bundle-1.12.367.jar /opt/hive/lib/
COPY --chown=hive:hive core-site.xml /opt/hadoop/etc/hadoop/core-site.xml
USER hive
