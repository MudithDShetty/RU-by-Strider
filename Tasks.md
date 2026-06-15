# StriderQuanto Search — Problem & Solution Handoff

**Context:** ~24k files indexed on phone. Typing results are okay; keyboard Search is good. Most users **don't press Search** — they need typing to use the **same pipeline**. Person names inside PDFs (e.g. `Tanuj`, `Nikharv` in `NDA- Quantoo .pdf`) rank ~#1500 and don't surface.

**Do not re-fix already merged work unless regressions appear.** This doc is for the next implementation thread.

---

## Architecture (current)

```
Typing (debounced)  →  often lexical + filename; semantic only if library ≥5k
Keyboard Search     →  full hybrid: FTS pool → lexical → dense scan (top-K) → RRF → GraniteReranker

Indexing: buildFileMetadata → embed ~400 char metadataString (96 tokens) → SQLite + FTS keywords
Search debug: etDebugTarget + tap status line (SearchDebug logcat)
```

**Key files:** `MainActivity.kt`, `SearchPipeline.kt`, `DiskSearch.kt`, `RetrievalScaling.kt`, `EmbeddingIndex.kt`, `FilenameSearch.kt`, `DatabaseHelper.kt`, `FileIndexer.kt`, `ContentExtractor.kt`, `OwnerExtraction.kt`, `GraniteReranker.kt`, `LexicalSearch.kt`, `SearchDiagnostics.kt`

---

## Problem 1 — Typing ≠ Search (most users never press Search)

### Symptom
- While typing: okay but not as good as Search.
- After Search: good.
- Users expect live results to be final.

### Root cause
Two modes in `MainActivity.kt`:
- `TextWatcher` → debounced, historically `semanticEnabled = false` (now semantic preview only if `indexer.size >= 5000`).
- `IME_ACTION_SEARCH` → always full hybrid.

Same user query, different pipelines.

### Solution
1. **Single pipeline:** both triggers call the same `searchWithDiagnostics(..., semanticEnabled = true)` (or one `performSearch()` with no mode flag).
2. **Debounce:** 500–700ms; keep `searchGeneration` stale guard.
3. **Optional progressive UI:** show filename/lexical hits in ~100ms, replace when ONNX hybrid finishes (same final ranking as Search).
4. **Product:** hint "Results update as you type"; don't imply Search is required (`IME_ACTION_DONE` or neutral action).

### Acceptance
Same query typed (wait for debounce, no Search) vs Search → **same file in top 3** (ideally #1).

---

## Problem 2 — Accuracy collapses as library grows (3k → 24k)

### Symptom
Good at ~3k files; bad at ~24k.

### Root cause
Retrieval was tuned for small libraries:
- Dense scan kept only **top 50–80** of ~24k (~0.3%).
- FTS/candidate pool capped ~500–1800.
- "Recent files" flooded the lexical pool.

### Already implemented (verify, don't redo)
- `RetrievalScaling.kt` — dense top-K ~**350** at 24k, wider RRF/rerank pools.
- Full-library dense scan in `SearchPipeline.denseRetrieve()`.
- `EmbeddingIndex.kt` — RAM cache (~37 MB @ 24k).
- `DiskSearch.kt` — scaled FTS/category caps; reduced recent-file injection.
- `StriderApp` — `warmEmbeddingCache()` on startup.

### Remaining gap
Files at dense rank **351–1500+** still dropped before rerank. **Raising top-K alone is not enough** at 24k (too many files score ~0.45–0.55).

### Solution (if still failing after filename/content fixes)
- **Tiered retrieval:** cheap exact passes first (filename, content, entities), then dense top-K — not dense-only.
- Optional: in-memory cache warm indicator so first search isn't slow/cold.

---

## Problem 3 — Filename match missed (e.g. `NDA- Quantoo .pdf`)

### Symptom (debug example)
```
Dense rank: 6272 / top-350 cutoff (0.500)
In FTS/candidate pool: NO
Lexical rank: not scored
Verdict: semantic match exists but outside top-350
```

### Root cause
- File not in FTS/recency pool (WhatsApp path, 24k competition).
- Semantic rank weak; filename not in query embedding strongly.
- Query `"nda quantoo"` should be filename-driven, not semantic.

### Already implemented
- `FilenameSearch.kt` + `DatabaseHelper.searchPathsByNameTokens()` — full-library SQL on `COL_NAME` / `COL_PATH`.
- `DiskSearch` — filename hits pinned, not dropped by pool cap.
- `SearchPipeline.buildRerankPaths()` — filename paths prepended.
- `GraniteReranker` — boost when all query tokens appear in filename.

### Verify / finish
- Re-index not required for filename SQL (reads live DB).
- Test: search `nda quantoo` → pool ✓, lexical #1, shown #1.
- Debug field tracks **filename**, search bar uses **natural query tokens**.

---

## Problem 4 — Person names in PDF content not findable (`Tanuj`, `Nikharv`)

### Symptom
Names appear in `NDA- Quantoo .pdf` body but search `Tanuj` or `Nikharv` → dense rank ~**1500**, not in UI.

### Root cause (multi-layer)

| Layer | Issue |
|--------|--------|
| **Name extraction** | `OwnerExtraction.extractNamesFromText()` only matches **labeled** fields (`Name:`, `S/o`, etc.). Party names in NDA body text are **not** extracted. |
| **Content truncation** | PDF: first **3 pages**, **800 chars** raw → `cleanToKeywords()` → **60 distinct words** (`ContentExtractor.kt`). Names on page 4+ or dropped by `distinct()`/`STOPWORDS` never indexed. |
| **Embedding** | `metadataString` capped **400 chars**, **96 tokens**. Query `"Tanuj"` vs NDA-heavy embedding → weak cosine (~0.5), rank ~1500 among 24k. |
| **Retrieval** | No **content keyword SQL pass** (unlike filename pass). Relies on FTS + semantic; FTS may hit but lexical pool/ranking loses; semantic rank too low for top-350. |
| **Query type** | Person-name queries need **keyword/entity retrieval**, not bi-encoder semantic rank. |

### Solution (implement in order)

#### 4a. Content token search (mirror `FilenameSearch`) — **high priority**
- Add `DatabaseHelper.searchPathsByContentTokens(tokens)` — `LIKE` on `COL_CONTENT_SNIP`, `COL_METADATA_STR`, optionally FTS `keywords`.
- New `ContentSearch.gatherPaths(db, query)` — same token rules as filename.
- Inject into `DiskSearch` + `SearchPipeline.buildRerankPaths()` like filename hits.
- **Acceptance:** `Tanuj` → file in pool, lexical hit on content, top 10 without relying on dense rank.

#### 4b. Extract and index party/person names from PDF text — **high priority**
- Extend `OwnerExtraction` or new `EntityExtraction.kt`:
  - Parse **raw PDF text** (not only labeled lines): capitalized tokens, "between X and Y", signature blocks, "Party A/B", email-local-part patterns.
  - Store in new column e.g. `COL_ENTITIES` or append to FTS keywords explicitly.
- `buildKeywordString()` must include **all** extracted person/org names (not only `ownerEntities` capped at 4).
- Re-index affected files (or full re-index once).

#### 4c. Person-name query routing — **medium priority**
- In `QueryEnricher`: if query is 1–2 tokens, looks like a name (`isNameLike`), set flag `queryType = PERSON_NAME`.
- When `PERSON_NAME`: skip dense cutoff reliance; weight lexical/content/entity match heavily in `GraniteReranker` (similar to filename boost).

#### 4d. Richer PDF indexing (longer term)
- Increase `MAX_CHARS` for PDF **entity extraction** path (separate from embed snippet).
- Multi-chunk embed: title + page1 + pages with detected names; store multiple vectors or merge best chunk at search time.
- Optional: second FTS field `entities` for names only.

### Acceptance
- Search `Tanuj` and `Nikharv` → `NDA- Quantoo .pdf` in **top 3** (typing and Search).
- Debug: `pool ✓`, `lexical #1–5` or `content match`, dense rank may still be ~1500 — **that's OK** if keyword path wins.

---

## Problem 5 — Early-indexed files disappear after full index

### Symptom
Files indexed early visible when library small; gone at 24k.

### Root cause
Not bad embeddings — **recall caps** and recency bias excluded old files from lexical pool; dense top-K cut them.

### Already implemented
- Full-library dense scan.
- Filename search.
- Reduced recent-file flooding.

### Remaining
- Old files with **no filename/content token overlap** still depend on dense rank vs top-350.

---

## Problem 6 — Semantic score clustering at scale

### Symptom
Many files at cosine ~0.45–0.55; correct file at rank 500–6000.

### Root cause
- Single 384-dim vector per file from short generic metadata.
- 24k media/docs share similar phrases ("pdf document in WhatsApp folder…").

### Solution
- **Don't** fix by top-K → 6000.
- Fix by **exact passes** (filename, content, entities) + reranker.
- Long term: PDF chunks, better metadata, optional cross-encoder reranker (separate model).

---

## Problem 7 — FTS / indexing limits

### Symptom
Keywords exist but file not in candidate pool.

### Root cause
- FTS result limits (~600–2500) at 24k.
- Deferred FTS backfill on startup (`StriderApp` — search ready before backfill completes).
- `cleanToKeywords()` — `distinct().take(60)` drops repeated/significant terms.

### Solution
- Full-library SQL passes bypass FTS caps (filename ✅, **content/entities ❌**).
- Block search or show "indexing keywords…" until FTS backfill done, OR run backfill synchronously for small batches.
- For names: store in dedicated column, always searchable via SQL.

---

## Problem 8 — Weak signals for photos / media

### Symptom
`IMG_2847.jpg` only findable by date/folder, not scene/content.

### Root cause
No OCR; embed EXIF + folder only.

### Solution (out of scope unless requested)
- OCR pipeline, or exclude DCIM from full index / separate "Documents only" mode.

---

## Problem 9 — Debug tooling (keep)

### Current
- `etDebugTarget` (when `HYBRID_DEV_MODE = true`).
- Status line summary; tap for full report; logcat tag `SearchDebug`.
- Result badges: `d#N l#N`.

### For next thread
Use debug on failing cases before tuning. Turn off before release.

---

## Recommended implementation order (next thread)

| Priority | Task | Status |
|----------|------|--------|
| **P0** | Unify typing + Search → same `SearchPipeline`, semantic on | Done — `MainActivity.performSearch()` |
| **P0** | Content token SQL search (`Tanuj`, `Nikharv`) | Done — `ContentSearch.kt`, `DatabaseHelper.searchPathsByContentTokens` |
| **P0** | Dual-channel index: entities + context keywords | Done — `EntityExtraction.kt`, `COL_ENTITIES` (DB v4), re-index on upgrade |
| **P1** | Person-name query routing + reranker boost | Done — `QueryType.PERSON_NAME`, `GraniteReranker`, `LexicalSearch` |
| **P1** | Verify filename search for `nda quantoo` on device | **Manual QA** — see test matrix below |
| **P2** | PDF multi-chunk or longer entity extraction text | `ContentExtractor`, `FileMetadata` |
| **P2** | FTS backfill / warm cache UX | `StriderApp`, `MainActivity` |
| **P3** | OCR / docs-only indexing mode | product decision |

---

## Test matrix (hand to QA / next agent)

| Query | Expected file | Must pass typing + Search |
|-------|---------------|---------------------------|
| `nda quantoo` | `NDA- Quantoo .pdf` | Top 1 (filename) |
| `Tanuj` | `NDA- Quantoo .pdf` | Top 3 (content/entity) |
| `Nikharv` | `NDA- Quantoo .pdf` | Top 3 (content/entity) |
| Early-indexed doc (old path) | (your sample) | Top 10 |
| Vague semantic query | relevant doc | Top 5 |

Use debug report fields: `pool`, `dense rank`, `lexical rank`, `shown rank`.

---

## Explicit non-goals (don't waste time)

- Raising dense top-K above ~350–400 for 24k as primary fix.
- Swapping embedding model without eval set.
- Maintaining two long-term search algorithms (typing vs Search).
- ANN/HNSW until RAM scan proven too slow after cache warm.

---

**Start with P0: unified typing/search + content token search + PDF name extraction** — that covers filename cases, person-name cases, and the UX gap in one pass.
