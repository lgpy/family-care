# Medication catalogue builder (D31)

Builds the medication-name catalogue the app searches while you type. It is
**bundled in the APK** so a lookup never leaves the phone — which medicines a
family takes is health data (D4, D10).

```
ops/meds-catalog/build.py             the builder (Python 3, stdlib only)
android/feature/medications/data/src/main/assets/meds_catalog.tsv.gzip
android/feature/medications/data/src/main/assets/meds_catalog.meta.json
```

## When to rebuild

Before a release, and only then. The catalogue changes slowly (new registrations,
price-list updates); rebuilding on every commit would put a fetch of ~5 MB of
third-party HTML on the critical path and churn the committed asset.

```sh
python -I ops/meds-catalog/build.py
```

`-I` (isolated mode) keeps the run independent of user site-packages and of the
working directory; the script only uses the standard library.

Useful flags:

| Flag | Effect |
| --- | --- |
| `--save-dir DIR` | keep the raw downloads (`spr.html`, `disp.html`, `mnsrm.html`). Put it **outside** the repository, e.g. `"$TEMP/meds-raw"`. |
| `--from-dir DIR` | rebuild from a previous `--save-dir` without the network (offline rebuilds, trying a parser change). |

```sh
python -I ops/meds-catalog/build.py --save-dir "$TEMP/meds-raw"
python -I ops/meds-catalog/build.py --from-dir "$TEMP/meds-raw"
```

## Sources

All three are public pages on `app10.infarmed.pt`, fetched with the polite
`family-care-catalog-builder` user agent. The downloads are treated as
untrusted data: parsed as HTML text, never executed.

1. **Reference-price list** (`tabela=spr`, ~5.1k rows) — an HTML `<table>`
   served as `application/ms-excel`, cp1252. Columns used: `Medicamento`,
   `Forma Farmacêutica`, `DCI`, `Dosagem`, `Embalagem N.º Unidades Volume`,
   `Titular de AIM`, `N.º Registo`.
2. **Available generics** (`tabela=disp`, ~4.5k rows) — the same kind of table
   with 12 columns and **no DCI/CNPEM**, but it lists products the reference
   list misses (e.g. the apixabano generics). When a disp-only row's name
   starts with a DCI known from the other lists ("Fluconazol Alter"), that DCI
   is filled in; otherwise it stays empty.
3. **MNSRM** (over-the-counter, ~2.5k rows) — an HTML page whose "Exportar para
   Excel" form posts to `lista_excel.php` (only the submit-image coordinates
   are sent). Membership means the medicine needs **no prescription**.

Rows are joined by **N.º Registo**, the registration number, which already
identifies product + strength + pack; the reference list wins on conflicts and
the later sources only fill gaps. MNSRM membership sets `otc = 1`.

The script refuses to write a shrunken catalogue: fewer than 3,000 reference
rows, 2,000 generic rows or 1,500 MNSRM rows, or a missing expected header,
ends the run with a non-zero exit and no asset change.

## Asset format

`meds_catalog.tsv.gzip` is gzipped UTF-8 TSV (no quoting; tabs and newlines are
replaced by spaces). Header:

```
registo name dci strength form pack pack_units otc holder
```

* `registo` — INFARMED registration number (the stable id).
* `pack_units` — `60` from `Blister - 60 unidade(s)`; empty when the pack is not
  a count of countable units (e.g. `Frasco - 1 unidade(s) - 200 ml`).
* `otc` — `1` when the registration is on the MNSRM list.

`meds_catalog.meta.json` records `built_at`, the source URLs, the source row
counts and the asset sizes, so a diff shows what a rebuild actually changed.

Current build: 7,916 products (5,073 spr + 4,544 disp + 2,474 MNSRM rows),
~126 KB gzipped.

## Data caveats (D31)

* **Brand-only medicines without a generic are missing** — Eliquis, Ozempic,
  insulins and the like are not on these lists. The app's free-text name field
  is the fallback and stays available always.
* **No GTIN/barcode**, so no box scanning.
* **No licence is stated on the exports.** The data is public but its reuse
  terms are not, so it stays inside this private app and repository: it is not
  redistributed, published or offered as an API. Do not ship it to a store
  build without checking INFARMED's terms first.
* Strength, form and pack are INFARMED's own text, normalised for whitespace
  only. The app maps the form to its enum by keyword and leaves anything
  unknown as `other`; it never rewrites what the user can then edit.
