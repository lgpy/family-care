#!/usr/bin/env python3
"""Build the medication catalogue bundled in the APK (D31).

Downloads INFARMED's public lists, joins them by registration number and writes

    android/feature/medications/data/src/main/assets/meds_catalog.tsv.gzip
    android/feature/medications/data/src/main/assets/meds_catalog.meta.json

Sources (public, no key, no login):

  * Reference-price list, ``tabela=spr`` (~5.1k rows): an HTML table served as
    ``application/ms-excel``, cp1252. Has the DCI, the CNPEM and the price.
  * Available generics, ``tabela=disp`` (~4.5k rows): the same kind of table,
    twelve columns and no DCI/CNPEM, but it carries products the reference
    list does not (e.g. apixabano generics).
  * MNSRM (over-the-counter) list: an HTML page whose "Exportar para Excel"
    form posts to ``lista_excel.php``. Membership means "no prescription".

The downloads are untrusted data: they are parsed as text and never executed.

Run it before a release, from anywhere:

    python -I ops/meds-catalog/build.py

``--save-dir DIR`` keeps the raw downloads (put it outside the repository),
``--from-dir DIR`` rebuilds from a previous ``--save-dir`` without the network:

    python -I ops/meds-catalog/build.py --from-dir "$TEMP/meds-raw"

The sanity checks are deliberately loud: a shrunken or reshaped source fails
the run (non-zero exit) instead of silently committing a smaller catalogue.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path

USER_AGENT = "family-care-catalog-builder"

SPR_URL = (
    "https://app10.infarmed.pt/genericos/genericos_II/lista_genericos.php"
    "?tabela=spr&fonte=todos&excel=true"
)
DISP_URL = (
    "https://app10.infarmed.pt/genericos/genericos_II/lista_genericos.php"
    "?tabela=disp&fonte=todos&excel=true"
)
MNSRM_PAGE_URL = (
    "https://app10.infarmed.pt/pt/comparticipacao_medicamentos/lista_mnsrm/lista_mnsrm.php"
)
MNSRM_EXCEL_URL = (
    "https://app10.infarmed.pt/pt/comparticipacao_medicamentos/lista_mnsrm/lista_excel.php"
)

# The MNSRM export form only carries the submit image's coordinates.
MNSRM_FORM = {"Submit.x": "1", "Submit.y": "1"}

# Sanity thresholds (D31): the lists only grow, so a drop means a changed export.
MIN_SPR_ROWS = 3000
MIN_DISP_ROWS = 2000
MIN_MNSRM_ROWS = 1500

HEADER = ["registo", "name", "dci", "strength", "form", "pack", "pack_units", "otc", "holder"]

REPO_ROOT = Path(__file__).resolve().parents[2]
ASSET_DIR = REPO_ROOT / "android/feature/medications/data/src/main/assets"
ASSET_TSV = ASSET_DIR / "meds_catalog.tsv.gzip"
ASSET_META = ASSET_DIR / "meds_catalog.meta.json"


# --- downloading -------------------------------------------------------------


def download(url: str, data: bytes | None = None) -> bytes:
    request = urllib.request.Request(url, data=data, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 200:
            raise SystemExit(f"GET {url} returned HTTP {response.status}")
        return response.read()


def fetch_all(save_dir: Path | None) -> dict[str, bytes]:
    raw = {
        "spr": download(SPR_URL),
        "disp": download(DISP_URL),
        "mnsrm": download(
            MNSRM_EXCEL_URL,
            data=urllib.parse.urlencode(MNSRM_FORM).encode("ascii"),
        ),
    }
    if save_dir is not None:
        save_dir.mkdir(parents=True, exist_ok=True)
        for name, body in raw.items():
            (save_dir / f"{name}.html").write_bytes(body)
        print(f"saved raw downloads to {save_dir}")
    return raw


def load_all(from_dir: Path) -> dict[str, bytes]:
    raw = {}
    for name in ("spr", "disp", "mnsrm"):
        path = from_dir / f"{name}.html"
        if not path.is_file():
            raise SystemExit(f"--from-dir {from_dir}: missing {path.name}")
        raw[name] = path.read_bytes()
    print(f"read raw downloads from {from_dir}")
    return raw


# --- parsing -----------------------------------------------------------------


class TableRows(HTMLParser):
    """Collects every table row as a list of cell strings.

    These exports are one big ``<table>`` whose rows may sit directly under
    ``<table>`` (no ``<tbody>``) and whose cell markup is inconsistent, so a
    forgiving collector is more robust than a tree.
    """

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[str]] = []
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "tr":
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._cell = []
        elif tag == "br" and self._cell is not None:
            self._cell.append(" ")

    def handle_endtag(self, tag: str) -> None:
        if tag in ("td", "th") and self._cell is not None and self._row is not None:
            self._row.append("".join(self._cell))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)


def normalise(text: str) -> str:
    """Collapse whitespace and drop the zero-width/nbsp junk in the exports."""
    text = text.replace("\xa0", " ").replace("\u200b", "").replace("\ufeff", "")
    return re.sub(r"\s+", " ", text).strip()


def fold(text: str) -> str:
    """Lowercase, accent-free form used for matching (never for display)."""
    decomposed = unicodedata.normalize("NFD", normalise(text))
    return "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn").lower()


def header_key(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", fold(text)).strip()


def parse_table(body: bytes, source: str, required: list[str]) -> tuple[list[str], list[list[str]]]:
    """Decode one export, find its header row and return (headers, rows)."""
    text = None
    for encoding in ("cp1252", "utf-8"):
        try:
            text = body.decode(encoding)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        raise SystemExit(f"{source}: neither cp1252 nor utf-8 decoded the download")

    parser = TableRows()
    parser.feed(text)

    for index, row in enumerate(parser.rows):
        keys = [header_key(cell) for cell in row]
        found = [_column(keys, wanted) for wanted in required]
        if all(column is not None for column in found):
            headers = [normalise(cell) for cell in row]
            return headers, parser.rows[index + 1 :]

    raise SystemExit(
        f"{source}: expected headers {required} not found; "
        f"first row was {[normalise(c) for c in (parser.rows[0] if parser.rows else [])]}"
    )


def _column(keys: list[str], wanted: str) -> int | None:
    """The index of a header cell: exact, then prefix, then suffix.

    The suffix pass is what finds "N.º Registo" (folding leaves a bare ``n``
    before the ordinal indicator) without matching a stray "registo" word.
    """
    for index, key in enumerate(keys):
        if key == wanted:
            return index
    for index, key in enumerate(keys):
        if key.startswith(wanted + " "):
            return index
    for index, key in enumerate(keys):
        if key.endswith(" " + wanted) or key == wanted:
            return index
    return None


def columns(headers: list[str], wanted: list[str]) -> dict[str, int]:
    keys = [header_key(cell) for cell in headers]
    result = {}
    for name in wanted:
        index = _column(keys, name)
        if index is None:
            raise SystemExit(f"missing column {name!r} in headers {headers}")
        result[name] = index
    return result


def cell(row: list[str], index: int) -> str:
    return normalise(row[index]) if index < len(row) else ""


PACK_UNITS = re.compile(r"(\d+)\s*unidade\(s\)\s*$", re.IGNORECASE)


def pack_units(pack: str) -> int | None:
    """60 from "Blister - 60 unidade(s)"; null when the pack is not countable.

    A trailing volume (`Frasco - 1 unidade(s) - 200 ml`) is not a count of
    doses, so the end anchor keeps those null.
    """
    match = PACK_UNITS.search(pack)
    return int(match.group(1)) if match else None


def rows_of(raw: dict[str, bytes]) -> tuple[dict[str, dict], dict[str, int]]:
    """Parse all three exports and join them by registration number."""
    spr_headers, spr_rows = parse_table(
        raw["spr"],
        "spr",
        [
            "medicamento",
            "forma farmaceutica",
            "dci",
            "dosagem",
            "embalagem",
            "titular de aim",
            "registo",
        ],
    )
    spr_cols = columns(
        spr_headers,
        ["medicamento", "forma farmaceutica", "dci", "dosagem", "embalagem", "titular de aim", "registo"],
    )
    if len(spr_rows) < MIN_SPR_ROWS:
        raise SystemExit(f"spr: only {len(spr_rows)} rows, expected at least {MIN_SPR_ROWS}")

    disp_headers, disp_rows = parse_table(
        raw["disp"],
        "disp",
        ["medicamento", "dosagem", "forma farmaceutica", "embalagem", "titular de aim", "registo"],
    )
    disp_cols = columns(
        disp_headers,
        ["medicamento", "dosagem", "forma farmaceutica", "embalagem", "titular de aim", "registo"],
    )
    if len(disp_rows) < MIN_DISP_ROWS:
        raise SystemExit(f"disp: only {len(disp_rows)} rows, expected at least {MIN_DISP_ROWS}")

    mnsrm_headers, mnsrm_rows = parse_table(
        raw["mnsrm"],
        "mnsrm",
        ["registo", "nome do medicamento", "dci", "forma farmaceutica", "dosagem", "embalagem", "titular de aim"],
    )
    mnsrm_cols = columns(
        mnsrm_headers,
        ["registo", "nome do medicamento", "dci", "forma farmaceutica", "dosagem", "embalagem", "titular de aim"],
    )
    if len(mnsrm_rows) < MIN_MNSRM_ROWS:
        raise SystemExit(f"mnsrm: only {len(mnsrm_rows)} rows, expected at least {MIN_MNSRM_ROWS}")

    products: dict[str, dict] = {}

    def add(registo: str, name: str, dci: str, strength: str, form: str, pack: str, holder: str, otc: bool) -> None:
        if not registo or not name:
            return
        current = products.get(registo)
        if current is None:
            products[registo] = {
                "registo": registo,
                "name": name,
                "dci": dci,
                "strength": strength,
                "form": form,
                "pack": pack,
                "pack_units": pack_units(pack),
                "otc": otc,
                "holder": holder,
            }
            return
        # spr wins; the later sources only fill gaps.
        for key, value in (
            ("dci", dci),
            ("strength", strength),
            ("form", form),
            ("pack", pack),
            ("holder", holder),
        ):
            if not current[key] and value:
                current[key] = value
        if not current["pack_units"]:
            current["pack_units"] = pack_units(current["pack"])
        current["otc"] = current["otc"] or otc

    for row in spr_rows:
        add(
            cell(row, spr_cols["registo"]),
            cell(row, spr_cols["medicamento"]),
            cell(row, spr_cols["dci"]),
            cell(row, spr_cols["dosagem"]),
            cell(row, spr_cols["forma farmaceutica"]),
            cell(row, spr_cols["embalagem"]),
            cell(row, spr_cols["titular de aim"]),
            False,
        )

    # disp has no DCI: derive it from the name when a known DCI is a prefix
    # ("Fluconazol Alter" -> "Fluconazol"), otherwise leave it empty. The
    # vocabulary is every DCI the two lists that do carry one name, including
    # the MNSRM rows (parsed before being merged, so e.g. "Ácido
    # acetilsalicílico" can label the disp-only aspirin packs).
    dci_vocabulary = [product["dci"] for product in products.values() if product["dci"]]
    dci_vocabulary += [cell(row, mnsrm_cols["dci"]) for row in mnsrm_rows]
    known_dci = sorted(
        {fold(dci): dci for dci in dci_vocabulary if dci}.items(),
        key=lambda item: -len(item[0]),
    )

    def derive_dci(name: str) -> str:
        folded = fold(name)
        for key, display in known_dci:
            if folded.startswith(key) and (
                len(folded) == len(key) or not folded[len(key)].isalnum()
            ):
                return display
        return ""

    for row in disp_rows:
        name = cell(row, disp_cols["medicamento"])
        registo = cell(row, disp_cols["registo"])
        add(
            registo,
            name,
            derive_dci(name) if registo not in products or not products[registo]["dci"] else "",
            cell(row, disp_cols["dosagem"]),
            cell(row, disp_cols["forma farmaceutica"]),
            cell(row, disp_cols["embalagem"]),
            cell(row, disp_cols["titular de aim"]),
            False,
        )

    for row in mnsrm_rows:
        add(
            cell(row, mnsrm_cols["registo"]),
            cell(row, mnsrm_cols["nome do medicamento"]),
            cell(row, mnsrm_cols["dci"]),
            cell(row, mnsrm_cols["dosagem"]),
            cell(row, mnsrm_cols["forma farmaceutica"]),
            cell(row, mnsrm_cols["embalagem"]),
            cell(row, mnsrm_cols["titular de aim"]),
            True,
        )

    counts = {
        "spr": len(spr_rows),
        "disp": len(disp_rows),
        "mnsrm": len(mnsrm_rows),
        "products": len(products),
    }
    return products, counts


# --- output ------------------------------------------------------------------


def tsv_text(products: dict[str, dict]) -> str:
    lines = ["\t".join(HEADER)]
    for product in sorted(products.values(), key=lambda item: (fold(item["name"]), item["strength"], item["registo"])):
        values = [
            product["registo"],
            product["name"],
            product["dci"],
            product["strength"],
            product["form"],
            product["pack"],
            "" if product["pack_units"] is None else str(product["pack_units"]),
            "1" if product["otc"] else "0",
            product["holder"],
        ]
        lines.append("\t".join(value.replace("\t", " ").replace("\n", " ") for value in values))
    return "\n".join(lines) + "\n"


def write_outputs(products: dict[str, dict], counts: dict[str, int]) -> None:
    ASSET_DIR.mkdir(parents=True, exist_ok=True)
    text = tsv_text(products).encode("utf-8")
    # mtime=0 keeps the committed bytes reproducible.
    with open(ASSET_TSV, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz:
            gz.write(text)

    meta = {
        "built_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "sources": {
            "spr": SPR_URL,
            "disp": DISP_URL,
            "mnsrm_page": MNSRM_PAGE_URL,
            "mnsrm_excel": MNSRM_EXCEL_URL,
        },
        "rows": counts,
        "asset": ASSET_TSV.name,
        "asset_bytes_gz": ASSET_TSV.stat().st_size,
        "asset_bytes_tsv": len(text),
    }
    ASSET_META.write_text(json.dumps(meta, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    print(f"wrote {ASSET_TSV.relative_to(REPO_ROOT)} "
          f"({counts['products']} products, {ASSET_TSV.stat().st_size} bytes gz, {len(text)} bytes plain)")
    print(f"wrote {ASSET_META.relative_to(REPO_ROOT)}")
    for name in ("spr", "disp", "mnsrm"):
        print(f"  {name}: {counts[name]} source rows")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from-dir", type=Path, help="build from saved spr.html/disp.html/mnsrm.html")
    parser.add_argument("--save-dir", type=Path, help="keep the raw downloads in this directory")
    args = parser.parse_args(argv)

    raw = load_all(args.from_dir) if args.from_dir else fetch_all(args.save_dir)
    products, counts = rows_of(raw)
    write_outputs(products, counts)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
