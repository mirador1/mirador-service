#!/usr/bin/env python3
"""Convert a JaCoCo XML report into a Cobertura XML report.

Why this script exists
----------------------
GitLab's CI `coverage_report:` artefact accepts only `coverage_format: cobertura`
as of 2026-04 (no native JaCoCo importer). The Maven JaCoCo plugin emits the
JaCoCo schema. To get the per-file delta annotation in MR diff views ("X% of
new lines covered" + green/red gutter marks), we must convert.

Pure-stdlib (no `pip install` step in CI) — keeps the unit-test job lean and
avoids dependency drift. Output schema is the minimal Cobertura subset GitLab
parses: <coverage> > <packages> > <package> > <classes> > <class> > <lines>.

Usage
-----
    python3 bin/ci/jacoco-to-cobertura.py <jacoco.xml> <cobertura.xml>

Exits 0 on success. Exits 1 on missing input (CI step uses `|| true` to keep
the job green even if the XML is absent — e.g. when the test phase failed
before JaCoCo's report goal ran).

Maintenance
-----------
JaCoCo schema reference: https://www.jacoco.org/jacoco/trunk/coverage/report.dtd
Cobertura schema reference: http://cobertura.sourceforge.net/xml/coverage-04.dtd
GitLab parser source-of-truth: gitlab-org/gitlab :: lib/gitlab/ci/parsers/coverage/cobertura.rb
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def _counter(node: ET.Element, type_name: str) -> tuple[int, int]:
    """Return (missed, covered) for the named JaCoCo counter on this node, or (0, 0)."""
    for c in node.findall("counter"):
        if c.get("type") == type_name:
            return int(c.get("missed", "0")), int(c.get("covered", "0"))
    return 0, 0


def _rate(missed: int, covered: int) -> str:
    total = missed + covered
    return "1.0" if total == 0 else f"{covered / total:.4f}"


def convert(jacoco_path: Path, cobertura_path: Path) -> None:
    tree = ET.parse(jacoco_path)
    jacoco_root = tree.getroot()

    line_missed_total, line_covered_total = _counter(jacoco_root, "LINE")
    branch_missed_total, branch_covered_total = _counter(jacoco_root, "BRANCH")

    # Cobertura root element with the attributes GitLab requires.
    cobertura = ET.Element(
        "coverage",
        attrib={
            "line-rate": _rate(line_missed_total, line_covered_total),
            "branch-rate": _rate(branch_missed_total, branch_covered_total),
            "version": "0.1",
            "timestamp": "0",
            "lines-covered": str(line_covered_total),
            "lines-valid": str(line_covered_total + line_missed_total),
            "branches-covered": str(branch_covered_total),
            "branches-valid": str(branch_covered_total + branch_missed_total),
            "complexity": "0",
        },
    )

    sources = ET.SubElement(cobertura, "sources")
    # Cobertura paths are relative to one of the listed <source> roots.
    # GitLab matches diffed file paths against `<source>/<class filename>`.
    ET.SubElement(sources, "source").text = "src/main/java"

    packages = ET.SubElement(cobertura, "packages")

    for jpkg in jacoco_root.findall("package"):
        pkg_name_dotted = (jpkg.get("name") or "").replace("/", ".")
        pkg_line_missed, pkg_line_covered = _counter(jpkg, "LINE")
        pkg_branch_missed, pkg_branch_covered = _counter(jpkg, "BRANCH")
        pkg_el = ET.SubElement(
            packages,
            "package",
            attrib={
                "name": pkg_name_dotted,
                "line-rate": _rate(pkg_line_missed, pkg_line_covered),
                "branch-rate": _rate(pkg_branch_missed, pkg_branch_covered),
                "complexity": "0",
            },
        )
        classes_el = ET.SubElement(pkg_el, "classes")

        # Index per-class line hit counts from <sourcefile> nodes (JaCoCo
        # records line-level hits at sourcefile granularity, not class).
        sourcefiles: dict[str, ET.Element] = {
            sf.get("name") or "": sf for sf in jpkg.findall("sourcefile")
        }

        for jcls in jpkg.findall("class"):
            cls_name = jcls.get("name") or ""
            cls_sourcefile = jcls.get("sourcefilename") or ""
            cls_line_missed, cls_line_covered = _counter(jcls, "LINE")
            cls_branch_missed, cls_branch_covered = _counter(jcls, "BRANCH")
            # GitLab matches `filename` to changed paths in the MR diff —
            # use the package-relative path (e.g. `com/mirador/Foo.java`)
            # so it joins the `src/main/java` <source> root cleanly.
            filename_rel = f"{jpkg.get('name')}/{cls_sourcefile}" if cls_sourcefile else cls_name
            cls_el = ET.SubElement(
                classes_el,
                "class",
                attrib={
                    "name": cls_name.replace("/", "."),
                    "filename": filename_rel,
                    "line-rate": _rate(cls_line_missed, cls_line_covered),
                    "branch-rate": _rate(cls_branch_missed, cls_branch_covered),
                    "complexity": "0",
                },
            )
            ET.SubElement(cls_el, "methods")
            lines_el = ET.SubElement(cls_el, "lines")

            sf = sourcefiles.get(cls_sourcefile)
            if sf is None:
                continue
            for line in sf.findall("line"):
                # JaCoCo `line` attrs: nr (line number), mi (missed insn),
                # ci (covered insn), mb (missed branches), cb (covered branches).
                nr = line.get("nr")
                if nr is None:
                    continue
                ci = int(line.get("ci", "0"))
                mb = int(line.get("mb", "0"))
                cb = int(line.get("cb", "0"))
                hits = "1" if ci > 0 else "0"
                attrs = {"number": nr, "hits": hits, "branch": "false"}
                if (mb + cb) > 0:
                    attrs["branch"] = "true"
                    pct = 0 if (mb + cb) == 0 else int(100 * cb / (mb + cb))
                    attrs["condition-coverage"] = f"{pct}% ({cb}/{mb + cb})"
                ET.SubElement(lines_el, "line", attrib=attrs)

    cobertura_path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(cobertura).write(cobertura_path, encoding="utf-8", xml_declaration=True)


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: jacoco-to-cobertura.py <jacoco.xml> <cobertura.xml>", file=sys.stderr)
        return 1
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    if not src.is_file():
        print(f"jacoco-to-cobertura: skipped — {src} not found", file=sys.stderr)
        return 1
    convert(src, dst)
    print(f"jacoco-to-cobertura: wrote {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
