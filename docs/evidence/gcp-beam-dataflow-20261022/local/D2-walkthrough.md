# Two records, hop by hop

The actual bytes from the laptop run `run-local-20261022`. The GCP run holds the same rows (see `../gcp/D4-local-vs-gcp.md`).

## A record that migrates: `ACC000000036`

**1. The input line** (`data/00-INPUT-ACCOUNT.src`). Pipe-delimited. `CLIENT_TYPE` is padded to 4 characters, and the date and balance are raw.

```
ACC000000036|CUS0000036|SME |SAV001|EUR|20050723|D|+00000323665.32
```

**2. `account_src`** (intake, door 1 passed). Values are kept exactly as they arrived, plus provenance columns. The key is a hash.

```
ACCT_ID=ACC000000036  CUST_ID=CUS0000036  CLIENT_TYPE=SME  PRODUCT_CODE=SAV001  CURRENCY=EUR
OPEN_DATE=2005-07-23  STATUS=D  BALANCE=323665.32
_run_id=run-local-20261022  _source_file=ACCOUNT.001.DAT  _source_key=ACC000000036
_account_key=00b84382a5eeee1ca0fdf88bca11a79b
```

**3. `account_curated`** (Dataform). Typed and derived: money in minor units, a band, an age.

```
balance_minor=32366532  balance_amount=323665.32  balance_band=HIGH  account_age_years=21  status_code=D
```

**4. `account_enriched`** (enrich). The same row. The reference join found no branch for this contract, so it adds empty columns and rejects nothing.

**5. `account_target`** (produce, doors 2 passed). The record becomes a document. `status` D became `DORMANT`.

```json
{"accountId":"ACC000000036","balance":{"amount":"323665.32","currency":"EUR"},"currency":"EUR",
 "customerId":"CUS0000036","migration":{"dedupKey":"00b84382a5eeee1ca0fdf88bca11a79b",
 "runId":"run-local-20261022","sourceKey":"ACC000000036"},"openedOn":"2005-07-23",
 "productCode":"SAV001","status":"DORMANT"}
```

**6. Kafka, target topic** (`kafka/B6-target-system-target.tsv`). Keyed by the dedup key. It appears twice, at offsets 0 and 50, because produce and the loader both publish it.

**7. The Target System's verdicts** (`kafka/B6-target-system-confirmations.tsv`). The first copy is accepted, the second is recognised.

```
offset 0   created    2026-10-22T12:51:47.779871489Z
offset 50  duplicate  2026-10-22T12:51:49.911203598Z
```

**8. recon** counts it among the 50 confirmed target rows. `B9-per-account-trace.tsv` has one line for it:
present in all four tables, 2 Kafka messages, verdicts `created=1,duplicate=1`.

## A record that is rejected: `ACC000900002`

**1. The input line** (line 56). The date field is `20191345`: month 13, day 45.

```
ACC000900002|CUS0000002|RETL|LON003|RON|20191345|D|+00000250092.20
```

**2. intake stops it at door 1.** The parse walks the fields in TDS order and the first failure takes the blame.
It writes the record to `reject_log` **with the raw line as the system saw it**:

```
stage=parse  reason=PARSE_BAD_DATE  detail=OPEN_DATE='20191345'  source_key=""
raw_record=ACC000900002|CUS0000002|RETL|LON003|RON|20191345|D|+00000250092.20
```

**3. And it names it in `record_lineage`**, keyed by the content, because no account key could be derived:

```
door=rejected  stage=parse  reason=PARSE_BAD_DATE  source_key=raw:8e81e78cbd3f33ee  account_key=""
```

**4. Nothing downstream sees it.** It is in no later table and has no Kafka message.
It is one of the 5 in the equation `55 = 50 + 5`, and the report's `ledgerAgreement` finds it in both `reject_log` and `record_lineage`.
