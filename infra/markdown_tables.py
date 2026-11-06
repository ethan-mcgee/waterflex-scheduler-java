"""Markdown tables aligned as AGENTS.md asks: padded pipes, numeric columns right-aligned.

The same helpers as research/tools/report-field-validation.py, kept here so committed code can import them.
"""
import re

NUMBER = re.compile(r"^[~<>=+\-$]*\d[\d,]*(?:\.\d+)?(?:\s*(?:%|x|s|ms|min))?(?:\s*(?:/|to|-)\s*[~<>=+\-$]*\d[\d,]*(?:\.\d+)?(?:\s*(?:%|x|s|ms|min))?)*(?:\s*\([^)]*\))?$")
MISSING = ("", "n/a", "not measured")


def align(cells):
    """Right-align a column when at least 60 percent of its present cells are numbers."""
    present = [c for c in map(str, cells) if c not in MISSING]
    return "---:" if present and sum(bool(NUMBER.match(c)) for c in present) / len(present) >= 0.6 else "---"


def table(headers, rows, text=()):
    """A Markdown table padded so its pipes line up, numeric columns right-aligned. Columns named in ``text`` (by index)
    stay left-aligned even when their cells look like numbers, such as period labels like 2024-03."""
    rows = [[str(cell) for cell in row] for row in rows]
    for row in rows:
        if len(row) != len(headers):
            raise ValueError(f"Row has {len(row)} cells for {len(headers)} headers: {row}")
    right = [k not in text and align(row[k] for row in rows) == "---:" for k in range(len(headers))]
    widths = [max(4 if right[k] else 3, len(h), *(len(row[k]) for row in rows)) for k, h in enumerate(headers)]
    pad = lambda cells: "| " + " | ".join(c.rjust(w) if r else c.ljust(w) for c, w, r in zip(cells, widths, right)) + " |"
    separator = "| " + " | ".join("-" * (w - 1) + ":" if r else "-" * w for w, r in zip(widths, right)) + " |"
    return "\n".join([pad(headers), separator] + [pad(row) for row in rows])
