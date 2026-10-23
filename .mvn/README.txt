# Marks the monorepo root for Maven: ${maven.multiModuleProjectDirectory} resolves to this
# directory for every build run anywhere under it. implementations/beam-dataflow/apps/common
# uses it to name spec/contracts as a canonical absolute path (maven-enforcer rejects symlinked
# and unnormalised ones).
