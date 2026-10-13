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
from report_data import load, earlier

SOLVERS = ("TABU", "TABU_KOPT", "TABU_KOPT_SIZE_3", "TABU_SIZE_3", "KOPT")
LABELS = ("Tabu", "Tabu + K opt", "Tabu + K opt size 3", "Tabu size 3", "K opt")
LONG_SOLVERS = ("TABU_KOPT", "TABU", "KOPT", "LATE_ACCEPTANCE")
LONG_LABELS = ("Tabu + K opt", "Tabu", "K opt", "Late acceptance")
FLEETS = (5, 10, 20, 30, 50)
WORKLOADS = ("CLUSTERED", "DISPERSED", "MIXED_SKILL", "TIGHT_WINDOW")
WORK = ("Clustered", "Dispersed", "Mixed skill", "Tight window")


def counts(values):
    return "/".join(str(sum(test(x) for x in values)) for test in (lambda x: x > 0, lambda x: x == 0, lambda x: x < 0))


def dollars(value):
    return f"${value:,.2f}" if value >= 0 else f"-${-value:,.2f}"


def verify():
    data = load()
    # Keys: fleet, workload, fixture seed, search seed, budget ms, solver.
    main = {r["key"]: r["raw"] for r in data["main"][1]}
    long = {r["key"]: r["raw"] for r in data["long"][1]}
    confirm = {r["key"]: r["raw"] for r in data["confirm"][1]}
    first = {r["key"]: r["raw"] for r in earlier()}
    assert len(main) == 1200 and len(long) == 192 and len(confirm) == 32 and len(first) == 4480

    def group(raw, s, f=None, w=None, b=None, x=None, seed=None):
        selected = [(k, v) for k, v in raw.items() if k[-1] == s and (f is None or k[0] == f) and (w is None or k[1] == w)
                    and (b is None or k[-2] == b * 1000) and (x is None or k[2] == x) and (seed is None or k[3] == seed)]
        assert selected
        return selected

    def savings(raw, g, control, stage="after"):
        return [(raw[k[:-1] + (control,)][stage]["costCents"] - v[stage]["costCents"]) / 100 for k, v in g]

    def m_save(s, **kw):
        return savings(main, group(main, s, **kw), "TABU")

    def l_save(s, **kw):
        return savings(long, group(long, s, **kw), "TABU_KOPT")

    def stat(v, field):
        st = v["referencePhase"]
        return {"steps": st["steps"] * 1000 / st["solveMs"], "moves": st["moveEvaluations"] / st["steps"],
                "rate": st["moveEvaluations"] / st["solveMs"], "best": st["timeToBestMs"] / st["solveMs"]}[field]

    def first_group(s, f, b=None):
        return [(k, v) for k, v in first.items() if k[4] == s and k[0] == f and (b is None or k[3] == b * 1000)]

    doc = Document(OUTPUT)
    assert len(doc.tables) == 28 and len(doc.inline_shapes) == 11 and len(doc.sections) == 1
    assert len([p for p in doc.paragraphs if p.style.name == "Heading 1"]) == 16
    captions = [p for p in doc.paragraphs if p.style.name == "Caption"]
    assert len(captions) == 11
    for i, p in enumerate(captions, 1):
        assert p.text.startswith(f"Figure {i}.") and all(str(r.font.color.rgb) == "000000" and r.italic for r in p.runs)

    def check(index, expected):
        actual = [[c.text for c in r.cells] for r in doc.tables[index].rows[1:]]
        assert actual == [[str(c) for c in r] for r in expected], (index, actual, expected)

    check(0, [[label, dollars(mean(v["after"]["costCents"] / 100 for _, v in group(main, s))),
               "Control" if s == "TABU" else dollars(mean(m_save(s))), "Control" if s == "TABU" else counts(m_save(s))]
              for s, label in zip(SOLVERS, LABELS)])
    first_improvement = {f: (1 - mean(v["reference"]["costCents"] for _, v in first_group("TABU", f)) / mean(v["before"]["costCents"] for _, v in first_group("TABU", f))) * 100
                         for f in (5, 10, 20, 50)}
    check(5, [[f, group(main, "TABU", f)[0][1]["appointments"], f"{mean(v['before']['costCents'] / 100 for _, v in group(main, 'TABU', f)):,.2f}",
               f"{mean(v['after']['costCents'] / 100 for _, v in group(main, 'TABU', f)):,.2f}",
               f"{(1 - mean(v['after']['costCents'] for _, v in group(main, 'TABU', f)) / mean(v['before']['costCents'] for _, v in group(main, 'TABU', f))) * 100:.1f}%",
               f"{first_improvement[f]:.1f}%" if f in first_improvement else "Not tested"] for f in FLEETS])
    check(9, [[label, *[counts(m_save(s, f=f)) for f in FLEETS]] for s, label in zip(SOLVERS[1:], LABELS[1:])])

    def over_tk(f):
        return [(main[k[:-1] + ("TABU_KOPT",)]["after"]["costCents"] - v["after"]["costCents"]) / 100 for k, v in group(main, "TABU_KOPT_SIZE_3", f)]

    check(10, [[f, f"{mean(stat(v, 'steps') for _, v in group(main, 'TABU_KOPT', f)):.1f}", f"{mean(stat(v, 'steps') for _, v in group(main, 'TABU_KOPT_SIZE_3', f)):.1f}",
                f"{mean(stat(v, 'moves') for _, v in group(main, 'TABU_KOPT', f)):,.0f}", f"{mean(stat(v, 'moves') for _, v in group(main, 'TABU_KOPT_SIZE_3', f)):,.0f}",
                f"{mean(over_tk(f)):+.2f}", counts(over_tk(f))] for f in FLEETS])
    rules = [("Tabu + K opt everywhere", lambda f: "TABU_KOPT"), ("Tabu + K opt size 3 everywhere", lambda f: "TABU_KOPT_SIZE_3"),
             ("K opt to 20, then Tabu + K opt (the first study)", lambda f: "KOPT" if f <= 20 else "TABU_KOPT"),
             ("K opt to 20, then Tabu + K opt size 3", lambda f: "KOPT" if f <= 20 else "TABU_KOPT_SIZE_3")]
    check(11, [[name, *[f"{mean(m_save(choice(f), f=f)):+.2f}" for f in FLEETS], dollars(mean(mean(m_save(choice(f), f=f)) for f in FLEETS))] for name, choice in rules])
    check(12, [[label, f, *[f"{mean(l_save(s, f=f, b=b)):+.2f} ({counts(l_save(s, f=f, b=b))})" for b in (240, 480, 600)]]
               for s, label in zip(LONG_SOLVERS[1:], LONG_LABELS[1:]) for f in (30, 50)])

    def own(s, f, short, longer):
        return [long[k[:4] + (short * 1000, s)]["after"]["costCents"] - v["after"]["costCents"] for k, v in group(long, s, f, b=longer)]

    def per(cents, minutes=1):
        # Exact rational mean of integer cents, rounded half up by hand rather than through Decimal.
        numerator, denominator = sum(cents), len(cents) * minutes
        hundredths = (2 * numerator + denominator) // (2 * denominator) if numerator >= 0 else -((-2 * numerator + denominator) // (2 * denominator))
        return dollars(hundredths / 100)

    check(13, [[label, f, per(own(s, f, 240, 480)), per(own(s, f, 240, 480), 4), per(own(s, f, 480, 600)), per(own(s, f, 480, 600), 2)]
               for s, label in zip(LONG_SOLVERS, LONG_LABELS) for f in (30, 50)])
    check(14, [[label, *[f"{mean(v['after']['fairness']['variance'] for _, v in group(main, s, f)):.4f}" for f in FLEETS],
                f"{mean(v['after']['fairness']['variance'] for _, v in group(main, s)):.4f}"] for s, label in zip(SOLVERS, LABELS)])
    fv = lambda s, f, stage: mean(v[stage]["fairness"]["variance"] for _, v in first_group(s, f))
    check(15, [[f, f"{fv('TABU', f, 'before'):.4f}", f"{fv('TABU', f, 'reference'):.4f}", f"{fv('TABU_KOPT', f, 'reference'):.4f}",
                f"{fv('TABU', f, 'after'):.4f}", f"{fv('TABU_KOPT', f, 'after'):.4f}"] for f in (5, 10, 20, 50)])
    check(16, [[label, counts(m_save(s)), f"{min(m_save(s)):+.2f}", f"{median(m_save(s)):+.2f}", f"{max(m_save(s)):+.2f}"] for s, label in zip(SOLVERS[1:], LABELS[1:])])
    seq = {k[:-1]: (confirm[k[:-1] + ("TABU",)]["after"]["costCents"] - v["after"]["costCents"]) / 100 for k, v in group(confirm, "TABU_KOPT_SIZE_3")}
    par = {k: (main[k + ("TABU",)]["after"]["costCents"] - main[k + ("TABU_KOPT_SIZE_3",)]["after"]["costCents"]) / 100 for k in seq}
    assert len(seq) == 16
    tests = [("All", lambda k: True), ("20 technicians", lambda k: k[0] == 20), ("50 technicians", lambda k: k[0] == 50)] + [(label, lambda k, w=w: k[1] == w) for w, label in zip(WORKLOADS, WORK)]
    check(17, [[name, sum(map(test, seq)), f"{mean(v for k, v in seq.items() if test(k)):+.2f}", f"{mean(v for k, v in par.items() if test(k)):+.2f}",
                counts([v for k, v in seq.items() if test(k)])] for name, test in tests])
    for i, f in enumerate(FLEETS):
        check(19 + i, [[w[0], b, f"{mean(v['after']['costCents'] for _, v in group(main, 'TABU', f, w, b)) / 100:,.2f}",
                        *[f"{mean(m_save(s, f=f, w=w, b=b)):+.2f}\n{counts(m_save(s, f=f, w=w, b=b))}" for s in SOLVERS[1:]]]
                       for w in WORKLOADS for b in (60, 120)])
    check(24, [[label, f, f"{mean(stat(v, 'rate') for _, v in group(main, s, f)):.1f}", f"{mean(stat(v, 'steps') for _, v in group(main, s, f)):,.1f}",
                f"{mean(stat(v, 'moves') for _, v in group(main, s, f)):,.0f}", f"{mean(stat(v, 'best') for _, v in group(main, s, f)) * 100:.0f}%",
                f"{mean(stat(v, 'best') > .9 for _, v in group(main, s, f)) * 100:.0f}%", "Control" if s == "TABU" else f"{mean(m_save(s, f=f)):+.2f}"]
               for s, label in zip(SOLVERS, LABELS) for f in FLEETS])
    check(25, [[label, wl, *[f"{mean(m_save(s, f=f, w=w)):+.2f}" for f in FLEETS]] for s, label in zip(SOLVERS[1:], LABELS[1:]) for w, wl in zip(WORKLOADS, WORK)])
    check(26, [[f, w[0], b, f"{mean(v['after']['costCents'] for _, v in group(long, 'TABU_KOPT', f, w, b)) / 100:,.2f}",
                *[f"{mean(l_save(s, f=f, w=w, b=b)):+.2f}\n{counts(l_save(s, f=f, w=w, b=b))}" for s in LONG_SOLVERS[1:]]]
               for f in (30, 50) for w in WORKLOADS for b in (240, 480, 600)])

    charts = json.loads((HERE / "generated/chart_data.json").read_text())

    def close(actual, expected):
        assert len(actual) == len(expected)
        assert all(isclose(a, e, rel_tol=1e-10, abs_tol=1e-10) for a, e in zip(actual, expected)), (actual, expected)

    for actual, s in zip(charts["cost_fleet"]["values"], SOLVERS[1:]):
        close(actual, [mean(m_save(s, f=f)) for f in FLEETS] + [mean(m_save(s))])
    for d in charts["replication"]:
        close(d["new"], [mean(savings(main, group(main, d["solver"], f, b=60), "TABU", "reference")) for f in (5, 10, 20, 50)])
        close(d["earlier"], [mean(savings(first, first_group(d["solver"], f, 90), "TABU", "reference")) for f in (5, 10, 20, 50)])
    for d in charts["steps"]:
        close(d["steps_per_second"], [mean(stat(v, "steps") for _, v in group(main, d["solver"], f)) for f in FLEETS])
        close(d["moves_per_step"], [mean(stat(v, "moves") for _, v in group(main, d["solver"], f)) for f in FLEETS])
    combo = charts["combination"]
    close(combo["tabu_kopt_size_3"], [mean(m_save("TABU_KOPT_SIZE_3", f=f)) for f in FLEETS])
    close(combo["direct_means"], [mean(over_tk(f)) for f in FLEETS])
    for d in charts["fleet_budget"]:
        close(d["savings"], [mean(m_save(d["solver"], f=f, b=d["seconds"])) for f in FLEETS])
    for d in charts["convergence"]:
        close(d["best_percent"], [mean(stat(v, "best") for _, v in group(main, d["solver"], f)) * 100 for f in FLEETS])
        close(d["late_best_percent"], [mean(stat(v, "best") > .9 for _, v in group(main, d["solver"], f)) * 100 for f in FLEETS])
    for d in charts["long_budget"]:
        close(d["savings"], [mean(l_save(d["solver"], f=d["fleet"], b=b)) for b in (240, 480, 600)])
    for c in charts["time_curve"]:
        if c["source"] == "5d15129b":
            expected = mean(savings(first, first_group(c["solver"], 50, c["seconds"] * 3 // 2), "TABU", "reference"))
        else:
            raw = main if c["source"] == "main" else long
            expected = mean(savings(raw, group(raw, c["solver"], 50, b=c["seconds"]), "TABU", "reference"))
        close([c["saving"]], [expected])
    for d in charts["fairness"]:
        close(d["savings"], [mean(m_save(d["solver"], f=f)) for f in FLEETS])
    for d in charts["variation"]:
        close(d["case_savings"], sorted(m_save(d["solver"])))
        close(d["means"], [mean(m_save(d["solver"], x=x, seed=seed)) for x, seed in d["combinations"]])
    close([p["sequential"] for p in charts["confirmation"]], [seq[tuple(p["key"])] for p in charts["confirmation"]])
    close([p["parallel"] for p in charts["confirmation"]], [par[tuple(p["key"])] for p in charts["confirmation"]])

    assert sha256(REFERENCE.read_bytes()).hexdigest() == REFERENCE_SHA
    with ZipFile(REFERENCE) as reference, ZipFile(OUTPUT) as out:
        for name in (*PRESERVE, *[n for n in reference.namelist() if "footer" in n]):
            assert reference.read(name) == out.read(name), name
        for name in out.namelist():
            if name.endswith(".xml"):
                text = out.read(name).decode("utf-8")
                assert chr(0x2014) not in text and chr(0x2013) not in text, name  # No em or en dashes.
        embedded = {sha256(out.read(n)).hexdigest() for n in out.namelist() if n.startswith("word/media/")}
        assert embedded == {sha256(p.read_bytes()).hexdigest() for p in (HERE / "generated").glob("*.png")}
    assert doc.sections[0]._sectPr.xml == Document(REFERENCE).sections[0]._sectPr.xml
    assert all(shape._inline.docPr.get("descr") for shape in doc.inline_shapes)
    assert all(row._tr.get_or_add_trPr().find(qn("w:cantSplit")) is not None for t in doc.tables for row in t.rows)
    print("PASS: 1,200 main, 192 long budget and 32 confirmation cases, 4,480 first study cases; 20 numerical tables; 11 chart datasets and images; template parts.")


if __name__ == "__main__":
    verify()
