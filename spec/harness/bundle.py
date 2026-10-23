"""Package a `.DAT` extract as `.FLG` / `.CHS` bundle for extract-gate.

NEW (not vendored): the old lane produced these artefacts as a side effect of the real
GCS-backed extractor-app; this repo has no GCS, so a bundle has to be built directly on
the local landing volume (`./work`, mounted at `/opt/mig/work` in the Flink containers,
per ).

Produces, under `<out>/<run_id>/incoming/`:
  ACCOUNT.tar.gz   — tar+gzip of ACCOUNT.001.DAT and ACCOUNT.CHS (Archives.java's ordering:
                     archive first, then compress; PGP is optional and not applied here)
  ACCOUNT.FLG      — written last and OUTSIDE the bundle, so extract-gate can detect
                     "the extract is complete" without decrypting anything (mirrors
                     apps/common/src/main/java/ro/mig/common/Archives.java's rationale)

.CHS format (one line per data file): "<sha256>  <records>  <fileName>", matching
apps/common/src/main/java/ro/mig/common/Checksums.render in the vendored Java apps —
extract-gate's Checksums.java parses the same format.

Usage:
    python -m harness.bundle --src fixtures/ACCOUNT.src --run-id run-flink-local-1 --out work
    python -m harness.bundle --src fixtures/ACCOUNT.src --run-id run-flink-local-1 --out work \
        --bad-chs         # corrupt the checksum, for the "refuses to start" test
    python -m harness.bundle --src fixtures/ACCOUNT.src --run-id run-flink-local-1 --out work \
        --no-flg          # omit the .FLG entirely, for the same test
"""

from __future__ import annotations

import argparse
import hashlib
import io
import tarfile
from pathlib import Path


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _count_records(data: bytes) -> int:
    if not data:
        return 0
    lines = data.count(b"\n")
    if not data.endswith(b"\n"):
        lines += 1
    return lines


def _pgp_encrypt(data: bytes, public_key: Path) -> bytes:
    """Encrypt `data` to `public_key` with the real `gpg` CLI — the format the source system emits
    (.tar.gz.pgp), not a re-implementation of it. A throwaway homedir keeps the caller's keyring
    untouched, and --trust-model always is safe because the key file was supplied explicitly."""
    import shutil, subprocess, tempfile
    if shutil.which("gpg") is None:
        raise SystemExit("--pgp-public-key needs the `gpg` CLI on PATH")
    with tempfile.TemporaryDirectory(prefix="bundle-gpg-") as home:
        env = {"GNUPGHOME": home, "PATH": __import__("os").environ["PATH"]}
        base = ["gpg", "--homedir", home, "--batch", "--yes", "--quiet"]
        subprocess.run(base + ["--import", str(public_key)], check=True, env=env, capture_output=True)
        ids = subprocess.run(base + ["--list-keys", "--with-colons"], check=True, env=env, capture_output=True, text=True).stdout
        fpr = next(l.split(":")[9] for l in ids.splitlines() if l.startswith("fpr:"))
        proc = subprocess.run(
            base + ["--trust-model", "always", "--recipient", fpr, "--encrypt", "--output", "-"],
            input=data, env=env, capture_output=True,
        )
        if proc.returncode != 0:
            # gpg's own words, not a bare CalledProcessError: the usual cause ("Unusable public key")
            # is a key with no encryption subkey, which a traceback does not tell you.
            raise SystemExit(f"gpg could not encrypt to {public_key}: {proc.stderr.decode().strip()}")
        return proc.stdout


def build(src: Path, run_id: str, out_root: Path, bad_chs: bool = False, no_flg: bool = False,
          pgp_public_key: Path | None = None) -> Path:
    payload = src.read_bytes()
    dat_name = "ACCOUNT.001.DAT"
    records = _count_records(payload)
    digest = _sha256(payload)
    if bad_chs:
        digest = "0" * 64  # deliberately wrong

    chs_text = (
        "# checksum manifest\n"
        f"# run_id={run_id}\n"
        "# sha256  records  file\n"
        f"{digest}  {records}  {dat_name}\n"
    )

    incoming = out_root / run_id / "incoming"
    incoming.mkdir(parents=True, exist_ok=True)

    # With --pgp-public-key the archive is written as ACCOUNT.tar.gz.pgp INSTEAD of ACCOUNT.tar.gz —
    # never both, because extract-gate refuses to choose between them.
    tar_path = incoming / ("ACCOUNT.tar.gz.pgp" if pgp_public_key else "ACCOUNT.tar.gz")
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w:gz") as tar:
        for name, data in ((dat_name, payload), ("ACCOUNT.CHS", chs_text.encode("utf-8"))):
            info = tarfile.TarInfo(name=name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
    tar_bytes = buf.getvalue()
    tar_path.write_bytes(_pgp_encrypt(tar_bytes, pgp_public_key) if pgp_public_key else tar_bytes)

    if not no_flg:
        (incoming / "ACCOUNT.FLG").write_text(f"run_id={run_id}\n", encoding="utf-8")

    return tar_path


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--src", type=Path, required=True)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--out", type=Path, default=Path("work"))
    ap.add_argument("--bad-chs", action="store_true")
    ap.add_argument("--no-flg", action="store_true")
    ap.add_argument("--pgp-public-key", type=Path, default=None,
                    help="encrypt the archive to this OpenPGP public key (needs gpg); writes ACCOUNT.tar.gz.pgp")
    args = ap.parse_args()

    tar_path = build(args.src, args.run_id, args.out, args.bad_chs, args.no_flg, args.pgp_public_key)
    print(f"bundled {args.src} -> {tar_path} (run_id={args.run_id})")


if __name__ == "__main__":
    main()
