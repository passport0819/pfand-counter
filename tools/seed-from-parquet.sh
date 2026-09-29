#!/usr/bin/env bash
# Reads the German beverage products out of Open Food Facts' Parquet export — only the seven
# columns we need. Writes $TMPDIR/off-de-beverages.jsonl (default /tmp) for tools/seed-build.py.
#
# Measured 22.09.2026, and the reason this script prefers a local copy:
#   duckdb's httpfs reads a remote Parquet in hundreds of small range requests. Hugging Face
#   answered 429 (Too Many Requests) at 21 % and the retry backoff then crawled at 2 KB/s.
#   The very same file fetched in ONE sequential request ran at 16 MB/s — about 8 minutes
#   for all 7.9 GB. So: download once, read locally, and the rate limit never comes up.
#
# The local copy lives outside this project on purpose (7.9 GB). To get the space back:
#   rm -rf ~/off-daten
set -euo pipefail
DUCK=${DUCK:-$HOME/.local/bin/duckdb}
LOCAL=${LOCAL:-$HOME/off-daten/food.parquet}
REMOTE=${REMOTE:-https://huggingface.co/datasets/openfoodfacts/product-database/resolve/main/food.parquet}
OUT=${OUT:-${TMPDIR:-/tmp}/off-de-beverages.jsonl}
# Dairy products for the jar rule (tools/dairy_jar_rule.py), with the fields the app reads.
OUT_DAIRY=${OUT_DAIRY:-${TMPDIR:-/tmp}/off-de-dairy.jsonl}

if [ ! -s "$LOCAL" ]; then
  echo "Hole den Gesamtexport einmalig nach $LOCAL (7,9 GB, rund 8 Minuten) …"
  mkdir -p "$(dirname "$LOCAL")"
  curl -L -C - --retry 20 --retry-delay 10 --retry-all-errors -o "$LOCAL" "$REMOTE"
fi

SOURCE=$LOCAL
PRELUDE=""
if [ ! -s "$LOCAL" ]; then          # kein Platz, kein Download: notfalls doch über das Netz
  SOURCE=$REMOTE
  PRELUDE="INSTALL httpfs; LOAD httpfs; SET threads = 1; SET http_keep_alive = true;
           SET http_retries = 12; SET http_retry_wait_ms = 3000; SET http_retry_backoff = 2;"
fi

"$DUCK" -c "
$PRELUDE
SET enable_progress_bar = true;
COPY (
  -- labels_tags, packaging_text and packaging_recycling_tags are worth their weight: for
  -- 1325 German drinks they spell out "Mehrwegpfand" or "Einwegpfand". That is a stated
  -- fact from the source, not an inference, and it outranks anything derived or guessed.
  SELECT code, product_name, brands, quantity, categories_tags, packagings, packaging_tags,
         labels_tags, packaging_text, packaging_recycling_tags, packaging,
         -- the label text read by OCR; seed-build reads only Einweg next to 0,25 from it
         CAST(ingredients_text AS VARCHAR) AS ingredients_text
  FROM read_parquet('$SOURCE')
  WHERE list_contains(countries_tags, 'en:germany')
    AND list_contains(categories_tags, 'en:beverages')
) TO '$OUT' (FORMAT JSON);
"
wc -l "$OUT"

# Reusable dairy jars are no beverages, so they need a query of their own. Only candidates are
# pulled (a dairy word in a category); tools/dairy_jar_rule.py decides, exactly as the app does.
"$DUCK" -c "
$PRELUDE
COPY (
  SELECT code, brands, quantity, categories_tags, labels_tags, packaging_tags,
         list_transform(packagings, p -> p.material) AS mats,
         list_transform(packagings, p -> p.shape) AS shapes,
         list_transform(packagings, p -> p.recycling) AS recs,
         list_transform(product_name, t -> t.text) AS names, product_name
  FROM read_parquet('$SOURCE')
  WHERE list_contains(countries_tags, 'en:germany')
    AND len(list_filter(categories_tags,
          t -> regexp_matches(t, 'dair|yog|milk|milch|cream|sahne|quark|kefir|skyr|joghurt'))) > 0
) TO '$OUT_DAIRY' (FORMAT JSON);
"
wc -l "$OUT_DAIRY"
