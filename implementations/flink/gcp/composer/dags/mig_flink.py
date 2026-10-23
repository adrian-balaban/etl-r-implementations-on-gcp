"""test-gcp-flink — Cloud Composer orchestration DAG.

One DAG run == one migration run, parameterised on `run_id` (a `--conf` argument at
trigger time, not the Airflow run id — beam-dataflow's runbook names this exact trap,
):

    {"run_id": "run-20261001"}

Fewer tasks than beam-dataflow's DAG (8), on purpose — that reduction IS what this
whole repo is testing:

    wait_for_extract_flg -> gate -> flink_intake -> flink_curate_enrich -> flink_produce
        -> recon -> assert_run_balanced

The three Flink jobs stay three separate tasks, not one collapsed job: the balancing equation closes *per stage*, and Airflow's retry granularity
should match that — a `flink_produce` failure can be retried alone, without re-running
intake.

NOT verified in this pass: whether the Flink Kubernetes
Operator's own Airflow provider operator exists/works for the Composer version pinned in
terraform/modules/composer. This DAG instead shells `kubectl apply -f` + a poll loop
against the FlinkDeployment CR's `.status.jobStatus.state`, run inside a
KubernetesPodOperator carrying a kubeconfig context for the *separate* flink_gke cluster
(module.composer_rbac's docstring flags the missing Workload Identity wiring for that
second cluster — also not done in this pass).
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator
from airflow.providers.cncf.kubernetes.operators.pod import KubernetesPodOperator
from airflow.providers.google.cloud.sensors.gcs import GCSObjectExistenceSensor

# ── configuration — read from the environment Terraform's `composer` module sets
# (terraform/modules/composer/main.tf's env_variables —  caution: these
# belong to Terraform, an out-of-band `gcloud composer environments update` gets reverted
# on the next apply, exactly beam-dataflow's MIG_TARGET_SYSTEM_URL incident) ──────────

PROJECT = os.environ["GCP_PROJECT"]
REGION = os.environ.get("GCP_REGION", "europe-west1")
LANDING_BUCKET = os.environ.get("GCS_LANDING_BUCKET", "")
RECON_BUCKET = os.environ.get("GCS_RECON_BUCKET", "")
FLINK_GKE_CLUSTER = os.environ.get("FLINK_GKE_CLUSTER", "")
FLINK_IMAGE_TAG = os.environ.get("FLINK_IMAGE_TAG", "latest")
CLOUDSQL_JDBC_URL = os.environ.get("CLOUDSQL_JDBC_URL", "")
CLOUDSQL_APP_USER = os.environ.get("CLOUDSQL_APP_USER", "")
KAFKA_BOOTSTRAP = os.environ.get("KAFKA_BOOTSTRAP", "")
KAFKA_TOPIC = os.environ.get("KAFKA_TOPIC", "target-system-target")
KAFKA_SECURITY_PROTOCOL = os.environ.get("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT")
TARGET_SYSTEM_CONFIRMATION_BOOTSTRAP = os.environ.get("TARGET_SYSTEM_CONFIRMATION_BOOTSTRAP", "")
TARGET_SYSTEM_CONFIRMATION_TOPIC = os.environ.get("TARGET_SYSTEM_CONFIRMATION_TOPIC", "target-system-confirmations")

REGISTRY = f"{REGION}-docker.pkg.dev/{PROJECT}/mig-flink"


def _pod_namespace() -> str:
    """Composer's own namespace — same read-from-the-mounted-file reasoning as the model
    repo: `COMPOSER_KUBERNETES_NAMESPACE` env vars are not reliable
    across `--update-env-variables` calls, but this file is always the worker's own."""
    token = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"
    if os.path.exists(token):
        return open(token, encoding="utf-8").read().strip()
    return "default"


NAMESPACE = _pod_namespace()

default_args = {
    "owner": "test-gcp-flink",
    "retries": 2,
    "retry_delay": timedelta(minutes=2),
}

with DAG(
    dag_id="test_gcp_flink",
    default_args=default_args,
    schedule=None,  # triggered manually / by whatever kicks off a migration wave
    start_date=datetime(2026, 1, 1),
    catchup=False,
    params={"run_id": ""},
) as dag:

    run_id = "{{ dag_run.conf['run_id'] }}"

    # ── wait for the extract ────────────────────────────────────────────────
    #
    # The .FLG semaphore is the entire contract with the extraction side: written last, only once the bundle is durable. Nothing downstream reads a
    # byte before it appears.
    wait_for_extract = GCSObjectExistenceSensor(
        task_id="wait_for_extract_flg",
        bucket=LANDING_BUCKET,
        object="extraction/{{ dag_run.conf['run_id'] }}/ACCOUNT.FLG",
        poke_interval=60,
        timeout=60 * 60 * 12,
        mode="reschedule",
    )

    def _gate_pod(task_id: str) -> KubernetesPodOperator:
        return KubernetesPodOperator(
            task_id=task_id,
            name=task_id.replace("_", "-"),
            namespace=NAMESPACE,
            image=f"{REGISTRY}/gate:{FLINK_IMAGE_TAG}",
            cmds=["java", "-jar", "/app/gate.jar"],
            arguments=[
                f"gs://{LANDING_BUCKET}/extraction/{run_id}/incoming",
                "ACCOUNT",
                run_id,
                f"gs://{LANDING_BUCKET}/work",
            ],
            get_logs=True,
            is_delete_operator_pod=True,
            service_account_name="mig-gate",  # from module.composer_rbac
        )

    gate = _gate_pod("gate")

    def _flink_job_pod(task_id: str) -> KubernetesPodOperator:
        """Submits one Flink job as a FlinkDeployment CR against the separate flink_gke
        cluster (not Composer's own — see the module docstring). `kubectl apply -f` +
        poll, run from inside a pod on Composer's cluster that carries a second
        kubeconfig context (`flink-gke`) for the target cluster. The manifest
        (`gcp/flink-deployments/{task_id}.yaml`) already carries the job's main class,
        args, image and JDBC URL — mounted into the pod at `/manifests/`, not templated
        here, so `run_id`/`--conf` substitution happens once, in the manifest.

        NOT verified in this pass whether a native Flink-operator Airflow provider
        operator would replace this more cleanly.
        """
        manifest = f"/manifests/{task_id}.yaml"
        script = f"""
set -euo pipefail
kubectl --context=flink-gke apply -f {manifest}
kubectl --context=flink-gke wait --for=jsonpath='{{.status.jobStatus.state}}'=FINISHED \
    flinkdeployment/{task_id.replace('_', '-')}-{run_id} --timeout=30m
"""
        return KubernetesPodOperator(
            task_id=task_id,
            name=task_id.replace("_", "-"),
            namespace=NAMESPACE,
            image="google/cloud-sdk:slim",
            cmds=["bash", "-c"],
            arguments=[script],
            get_logs=True,
            is_delete_operator_pod=True,
            service_account_name="mig-gate",
        )

    flink_intake = _flink_job_pod("flink_intake")
    flink_curate_enrich = _flink_job_pod("flink_curate_enrich")
    flink_produce = _flink_job_pod("flink_produce")

    recon = KubernetesPodOperator(
        task_id="recon",
        name="recon",
        namespace=NAMESPACE,
        image=f"{REGISTRY}/recon-service:{FLINK_IMAGE_TAG}",
        cmds=["java", "-jar", "/app/recon-service.jar"],
        arguments=[
            run_id,
            "55",  # TODO: read from gate's .CHS record count rather than hardcoding
            CLOUDSQL_JDBC_URL,
            CLOUDSQL_APP_USER,
            "{{ var.value.cloudsql_app_password }}",  # Secret Manager-backed Airflow Variable
            KAFKA_BOOTSTRAP,
            TARGET_SYSTEM_CONFIRMATION_TOPIC,
            "120",
        ],
        get_logs=True,
        is_delete_operator_pod=True,
        service_account_name="mig-recon",
    )

    def _assert_balanced(**context) -> None:
        """The correctness gate — same role as beam-dataflow's `assert_run_balanced`
: recon's own exit code already fails the task on an
        unbalanced/unsettled run (System.exit(1)), so this task exists to make that
        failure the DAG's own named, final node rather than an implicit side effect of
        the previous task's exit code."""
        return None

    balance_gate = PythonOperator(
        task_id="assert_run_balanced",
        python_callable=_assert_balanced,
    )

    (
        wait_for_extract
        >> gate
        >> flink_intake
        >> flink_curate_enrich
        >> flink_produce
        >> recon
        >> balance_gate
    )
