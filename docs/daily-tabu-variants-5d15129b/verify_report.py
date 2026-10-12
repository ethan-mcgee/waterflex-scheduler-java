"""Check delivered tables, plot data and template parts against validated raw cases.

Recomputes from raw results without the builder's derived fields. Run normally, without Python's -O flag.
Visual pagination still requires rendering.
"""
import json
from hashlib import sha256
from math import isclose
from statistics import mean, median
from zipfile import ZipFile

from docx import Document
from docx.oxml.ns import qn
from build_report import HERE, OUTPUT, REFERENCE, REFERENCE_SHA, PRESERVE
from report_data import load

SOLVERS = ("TABU", "TABU_SIZE_3", "TABU_SIZE_15", "TABU_KOPT", "KOPT", "SUBLIST", "LATE_ACCEPTANCE")
LABELS = ("Tabu", "Tabu size 3", "Tabu size 15", "Tabu + K opt", "K opt", "Sublist", "Late acceptance")
FLEETS = (5, 10, 20, 50)
BUDGETS = (60, 90, 120, 240)
WORKLOADS = ("CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW")
WORK = ("Clustered", "Dispersed", "Mixed skill", "Tight window")


def verify():
    config, rows, confirm_rows, contention = load()
    raw = {r["key"]: r["raw"] for r in rows}
    assert len(raw) == 4480

    def group(s, f=None, w=None, b=None, seed=None):
        selected = [(k, v) for k, v in raw.items() if k[4] == s and (f is None or k[0] == f) and (w is None or k[1] == w)
                    and (b is None or k[3] == b * 1000) and (seed is None or k[2] == seed)]
        assert selected
        return selected

    def savings(g):
        return [(raw[k[:-1] + ("TABU",)]["after"]["costCents"] - v["after"]["costCents"]) / 100 for k, v in g]

    def ref(v, field):
        stats = v["referencePhase"]
        return {"steps": stats["steps"] * 1000 / stats["solveMs"], "moves": stats["moveEvaluations"] / stats["steps"],
                "rate": stats["moveEvaluations"] / stats["solveMs"], "best": stats["timeToBestMs"] / stats["solveMs"]}[field]

    def counts(values):
        return "/".join(str(sum(test(x) for x in values)) for test in (lambda x: x > 0, lambda x: x == 0, lambda x: x < 0))

    def dollars(value):
        return f"${value:,.2f}" if value >= 0 else f"-${-value:,.2f}"

    doc = Document(OUTPUT)
    assert len(doc.tables) == 26 and len(doc.inline_shapes) == 10 and len(doc.sections) == 1
    assert len([p for p in doc.paragraphs if p.style.name == "Heading 1"]) == 20
    captions = [p for p in doc.paragraphs if p.style.name == "Caption"]
    assert len(captions) == 10
    for i, p in enumerate(captions, 1):
        assert p.text.startswith(f"Figure {i}.") and all(str(r.font.color.rgb) == "000000" and r.italic for r in p.runs)

    def check(index, expected):
        actual = [[c.text for c in r.cells] for r in doc.tables[index].rows[1:]]
        assert actual == [[str(c) for c in r] for r in expected], (index, actual, expected)

    check(0, [[label, dollars(mean(v["after"]["costCents"] / 100 for _, v in group(s))),
               "Control" if s == "TABU" else dollars(mean(savings(group(s)))), "Control" if s == "TABU" else counts(savings(group(s)))]
              for s, label in zip(SOLVERS, LABELS)])
    check(4, [[f, group("TABU", f)[0][1]["appointments"], f"{mean(v['before']['costCents'] / 100 for _, v in group('TABU', f)):,.2f}",
               f"{mean(v['after']['costCents'] / 100 for _, v in group('TABU', f)):,.2f}",
               f"{(1 - mean(v['after']['costCents'] for _, v in group('TABU', f)) / mean(v['before']['costCents'] for _, v in group('TABU', f))) * 100:.1f}%"] for f in FLEETS])
    family = ("KOPT", "SUBLIST", "LATE_ACCEPTANCE")
    check(8, [[label, *[f"{mean(mean(savings(group(s, f, w))) for s in family):+.2f}" for f in FLEETS]] for w, label in zip(WORKLOADS, WORK)])
    check(9, [[label, f"{mean(ref(v, 'rate') for _, v in group(s)):.1f}", f"{mean(ref(v, 'steps') for _, v in group(s)):,.1f}",
               f"{mean(ref(v, 'moves') for _, v in group(s)):,.0f}", "Control" if s == "TABU" else f"{mean(savings(group(s))):+.2f}"]
              for s, label in zip(SOLVERS, LABELS)])
    own = []
    for s, label in zip(SOLVERS, LABELS):
        values = [(raw[k[:3] + (60000, s)]["after"]["costCents"] - v["after"]["costCents"]) / 100 for k, v in group(s, b=240)]
        assert len(values) == 160
        own.append([label, dollars(mean(values)), counts(values)])
    check(10, own)
    check(12, [[f, size, f"{mean(ref(v, 'steps') for _, v in group(s, f)):.1f}", f"{mean(ref(v, 'moves') for _, v in group(s, f)):,.0f}",
                f"{mean(ref(v, 'best') for _, v in group(s, f)) * 100:.0f}%", "Control" if s == "TABU" else f"{mean(savings(group(s, f))):+.2f}"]
               for f in FLEETS for s, size in (("TABU_SIZE_3", 3), ("TABU", 7), ("TABU_SIZE_15", 15))])
    check(14, [[label, *[f"{mean(v['after']['fairness']['variance'] for _, v in group(s, f)):.4f}" for f in FLEETS],
                f"{mean(v['after']['fairness']['variance'] for _, v in group(s)):.4f}"] for s, label in zip(SOLVERS, LABELS)])
    check(15, [[label, counts(savings(group(s))), f"{min(savings(group(s))):+.2f}", f"{median(savings(group(s))):+.2f}",
                f"{max(savings(group(s))):+.2f}"] for s, label in zip(SOLVERS[1:], LABELS[1:])])
    check(16, [[n, f"{min(g['reference_median_ratio'] for g in contention[n]['groups']):.3f}",
                f"{max(g['reference_median_ratio'] for g in contention[n]['groups']):.3f}", sum(g["pairs"] for g in contention[n]["groups"]), "No"]
               for n in (4, 6, 10)])
    sequential = {r["key"][:-1]: r["raw"]["after"]["costCents"] for r in confirm_rows if r["solver"] == "TABU"}
    seq = {r["key"][:-1]: (sequential[r["key"][:-1]] - r["raw"]["after"]["costCents"]) / 100 for r in confirm_rows if r["solver"] == "TABU_KOPT"}
    par = {k: (raw[k + ("TABU",)]["after"]["costCents"] - raw[k + ("TABU_KOPT",)]["after"]["costCents"]) / 100 for k in seq}
    assert len(seq) == 64
    check(17, [["All", 64, f"{mean(seq.values()):+.2f}", f"{mean(par.values()):+.2f}", counts(seq.values())]]
          + [[f"{b} s budget", 32, f"{mean(v for k, v in seq.items() if k[3] == b * 1000):+.2f}", f"{mean(v for k, v in par.items() if k[3] == b * 1000):+.2f}", ""] for b in (120, 240)]
          + [[f"{f} technicians", 16, f"{mean(v for k, v in seq.items() if k[0] == f):+.2f}", f"{mean(v for k, v in par.items() if k[0] == f):+.2f}", ""] for f in FLEETS])
    for i, f in enumerate(FLEETS):
        check(19 + i, [[w[0], b, f"{mean(v['after']['costCents'] for _, v in group('TABU', f, w, b)) / 100:,.2f}",
                        *[f"{mean(savings(group(s, f, w, b))):+.2f}\n{counts(savings(group(s, f, w, b)))}" for s in SOLVERS[1:]]]
                       for w in WORKLOADS for b in BUDGETS])
    check(24, [[label, wl, *[f"{mean(savings(group(s, f, w))):+.2f}" for f in FLEETS]] for s, label in zip(SOLVERS[1:], LABELS[1:]) for w, wl in zip(WORKLOADS, WORK)])

    data = json.loads((HERE / "generated/chart_data.json").read_text())

    def close(actual, expected):
        assert len(actual) == len(expected)
        assert all(isclose(a, e, rel_tol=1e-10, abs_tol=1e-10) for a, e in zip(actual, expected)), (actual, expected)

    for actual, s in zip(data["cost_fleet"]["values"], SOLVERS[1:]):
        close(actual, [mean(savings(group(s, f))) for f in FLEETS] + [mean(savings(group(s)))])
    for actual, s in zip(data["cost_workload"]["values"], SOLVERS[1:]):
        close(actual, [mean(savings(group(s, w=w))) for w in WORKLOADS])
    for d in data["steps"]:
        close(d["steps_per_second"], [mean(ref(v, "steps") for _, v in group(d["solver"], f)) for f in FLEETS])
        close(d["moves_per_step"], [mean(ref(v, "moves") for _, v in group(d["solver"], f)) for f in FLEETS])
    for d in data["convergence"]:
        close(d["best_percent"], [mean(ref(v, "best") for _, v in group(d["solver"], f)) * 100 for f in FLEETS])
        close(d["late_best_percent"], [mean(ref(v, "best") > .9 for _, v in group(d["solver"], f)) * 100 for f in FLEETS])
    for d in data["budgets"]:
        close(d["savings"], [mean(x for f in d["fleets"] for x in savings(group(d["solver"], f, b=b))) for b in BUDGETS])
    for d in data["phases"]:
        close(d["accepted_savings"], [mean(savings(group(d["solver"], f, w))) for f in FLEETS for w in WORKLOADS])
    for d in data["variation"]:
        close(d["case_savings"], sorted(savings(group(d["solver"]))))
    close([p["sequential"] for p in data["confirmation"]], [seq[tuple(p["key"])] for p in data["confirmation"]])
    close([p["parallel"] for p in data["confirmation"]], [par[tuple(p["key"])] for p in data["confirmation"]])

    assert sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_SHA
    with ZipFile(REFERENCE) as reference, ZipFile(OUTPUT) as out:
        for name in (*PRESERVE, *[n for n in reference.namelist() if "footer" in n]):
            assert reference.read(name) == out.read(name), name
        for name in out.namelist():
            if name.endswith(".xml"):
                text = out.read(name).decode("utf-8")
                assert "—" not in text and "–" not in text, name
        embedded = {sha256(out.read(n)).hexdigest() for n in out.namelist() if n.startswith("word/media/")}
        assert embedded == {sha256(p.read_bytes()).hexdigest() for p in (HERE / "generated").glob("*.png")}
    assert doc.sections[0]._sectPr.xml == Document(REFERENCE).sections[0]._sectPr.xml
    assert all(shape._inline.docPr.get("descr") for shape in doc.inline_shapes)
    assert all(row._tr.get_or_add_trPr().find(qn("w:cantSplit")) is not None for t in doc.tables for row in t.rows)
    print("PASS: 4,480 raw cases, 128 confirmation cases, 3 contention reports; 16 numerical tables; ten chart datasets and images; template parts.")


if __name__ == "__main__":
    verify()
