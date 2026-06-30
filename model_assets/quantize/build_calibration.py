#!/usr/bin/env python3
"""Generate calibration_texts.txt for static INT8 quantization (Step 1)."""
from __future__ import annotations

import random
from pathlib import Path

OUT = Path(__file__).resolve().parent / "calibration_texts.txt"

SAMPLES = [
    # English filenames + metadata
    "budget spreadsheet Q3 finance report xlsx quarterly revenue",
    "NDA quantoo agreement contract legal document pdf",
    "resume cv biodata software engineer experience skills",
    "invoice march 2024 client payment receipt pdf",
    "meeting minutes project kickoff agenda notes docx",
    "passport scan identity document travel visa",
    "python tutorial machine learning notes assignment college",
    "selfie beach vacation goa trip photo portrait",
    "song playlist bollywood music mp3 audio track",
    "screenshot error log debug android kotlin stacktrace",
    # Hindi / Hinglish
    "mera aadhaar card pdf identity document",
    "pichle mahine ki salary slip payroll hindi",
    "diwali party photos family celebration pictures",
    "budget wali file excel pichle quarter ki",
    "assignment submit karna hai notes pdf college",
    "grocery list hindi list vegetables market",
    # Short ( ~20 tokens )
    "photo jpg",
    "notes txt",
    "report pdf",
    "invoice",
    # Long (~96 token budget simulation)
    " ".join(["content snippet from pdf page one financial summary revenue growth "
              "expense breakdown operating margin quarterly results forecast"] * 3),
    # Edge cases
    "[empty]",
    "untitled document final version copy 2 revised",
    "Tanuj Nikharv party name NDA signature page",
    # Extension + folder context
    "Documents Download DCIM Camera WhatsApp Media Pictures",
]

TEMPLATES = [
    "{name} {ext} {folder} {snippet}",
    "{snippet} {categories} {type_label} {age}",
    "{name} {ext} {content}",
]

NAMES = [
    "Q3_finance", "NDA-Quantoo", "resume_2024", "invoice_march", "notes_lecture",
    "aadhaar_scan", "party_photos", "budget_sheet", "assignment_final", "report_draft",
    "Tanuj_contract", "screenshot_bug", "playlist_summer", "passport_copy", "meeting_notes",
]

EXTS = ["pdf", "docx", "xlsx", "txt", "md", "jpg", "png", "mp3", "mp4", "csv", "json", "html"]
FOLDERS = ["Documents", "Download", "DCIM", "WhatsApp", "Pictures", "Music", "Movies"]
SNIPPETS = [
    "financial quarterly revenue budget expense",
    "contract agreement party signature legal",
    "tutorial chapter exercise solution homework",
    "portrait photo vacation travel beach",
    "audio track album artist metadata",
    "",
]
CATEGORIES_LIST = ["work", "identity", "education", "personal", "media", "general"]
TYPE_LABELS = ["document", "photo image", "spreadsheet", "audio music", "video"]
AGES = ["today", "this-week", "this-month", "last-quarter", "this-year", "older"]


def generate_line(rng: random.Random) -> str:
    if rng.random() < 0.15:
        return rng.choice(SAMPLES)
    tpl = rng.choice(TEMPLATES)
    return tpl.format(
        name=rng.choice(NAMES).replace("_", " "),
        ext=rng.choice(EXTS),
        folder=rng.choice(FOLDERS),
        snippet=rng.choice(SNIPPETS),
        categories=" ".join(rng.sample(CATEGORIES_LIST, k=rng.randint(1, 2))),
        type_label=rng.choice(TYPE_LABELS),
        age=rng.choice(AGES),
        content=rng.choice(SNIPPETS) or rng.choice(NAMES),
    ).strip()


def main() -> None:
    rng = random.Random(42)
    lines = list(SAMPLES)
    while len(lines) < 600:
        line = generate_line(rng)
        if line and line not in lines:
            lines.append(line)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote {len(lines)} lines to {OUT}")


if __name__ == "__main__":
    main()
