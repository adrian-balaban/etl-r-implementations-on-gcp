FROM docker.io/library/flink:2.2.1-java17

# No S3/rocksdb plugin, no checkpoint-related jars: batch mode does not checkpoint. The job jars
# submitted to this cluster are uber-jars (maven-shade-plugin), so the connectors
# (Kafka, JDBC/Postgres) travel with the job rather than living in /opt/flink/lib —
# this image only needs the base distribution.
