# Security

This repository is a reference implementation. It holds no credentials and no real data.

## Report a problem

Open a private advisory: **Security → Report a vulnerability** on GitHub.

## Open by design

The Beam mock of Target System accepts unauthenticated calls (`allUsers` invoker).
It holds synthetic data and stands in for an open API. Do not reuse that module for real systems.
The Flink mock is internal-only.

## Before you deploy

- Set your own project id, billing account and state bucket. Nothing here names them.
- GCP runs are billable. Each ends with a teardown. Verify it by resource type: see [`docs/runbook-gcp.md`](docs/runbook-gcp.md).
