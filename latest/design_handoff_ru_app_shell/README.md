# Handoff: Ru — App Shell (Navigation · Index · Settings · Onboarding)

## Overview
This package specifies the redesigned **app shell** for Ru (Strider Quanto) — the on-device, multilingual semantic file-search Android app. It covers four things layered on top of the existing Home/search screen:

1. A **bottom tab bar** (Home · Index · Settings)
2. The **Index screen** (scan device → build the semantic index, with a progress dial and category buckets)
3. The **Settings screen** (search toggles, indexing options, about, clear index)
4. The **"What's your name?" onboarding** overlay + a confirmation toast

The Home screen, search, and results sheet already exist in both the prototype and the app and are **not** part of this port — only the shell around them.

## About the Design Files
The file in this bundle — **`Ru - App.html`** — is a **design reference created in HTML**. It is a high-fidelity prototype that demonstrates the intended **look, spacing, copy, and interaction behavior**. It is **not production code to copy**.

The task is to **recreate these designs in the existing Android codebase** (`RU-by-Strider/`, package `com.strider.ru`, Kotlin + Android Views/XML + Material Components) using its established patterns — Fragments, view binding, `RecyclerView`, `BottomSheetBehavior`, theme attributes, and the existing resource files. Do **not** embed the HTML in a WebView.

> **Good news:** the codebase is already aligned with this design. `res/values/colors.xml` already defines the exact brand + category colors used here, and `res/values/strings.xml` already contains **every string** this design needs (settings rows, onboarding, index status, nav labels). This is a styling + layout + light-wiring job, not a from-scratch rebuild. Reuse those resources — do not hardcode hex or copy.

## Fidelity
**High-fidelity (hifi).** Colors, spacing, typography, radii, and interactions are final. Recreate pixel-faithfully using Android-native components. Where a web effect has no Android equivalent (noted inline), use the documented fallback — do not attempt to reproduce it.

### Web techniques with no clean Android equivalent (use fallbacks)
- **Tiled "doodle" SVG background pattern** (diya/kite/tabla line-art) and the animated **Ink-theme `feTurbulence` "boil" filter** → **do not port.** Use a flat themed background (`@color/ru_bg`). These are decorative only and carry no function.
- **Sepia / Ink / time-of-day theme switching** in the prototype → out of scope for this port. Implement against the app's **single existing warm palette** (`ru_bg` cream / `ru_dark` ink / `ru_crimson` accent). The prototype is shown in its "sepia" theme; map its semantic tokens to the existing named colors (table below).
- `color-mix()`, `oklab`, `backdrop-filter`, flex `gap` → use resolved hex, standard Material elevation/scrim, and margins/`ConstraintLayout` spacing.

---

## Design Tokens

Map the prototype's CSS variables to **existing** `colors.xml` entries. Do not add new colors unless noted.

| Prototype token (sepia) | Value | Use as (existing resource) |
|---|---|---|
| `--bg` | `#F3E7CE` (sepia) | `@color/ru_bg` (`#FEF9E6`) — app's canonical canvas |
| `--ink` | `#3A2A18` (sepia) | `@color/text_primary` (`#1A1530`) |
| `--ink-soft` | ~58% ink | `@color/text_secondary` (`#888888`) |
| `--crimson-on` | `#BC2138` (sepia) | `@color/ru_crimson` (`#C41E3A`) |
| `--card` | `#FBF3E2` | `@color/surface_solid` (`#FFFFFF`) |
| `--card-line` | ~8% ink | `@color/divider` (`#0D000000`) |
| `--chip` | ~5% ink | a 4–6% black overlay / `?attr/selectableItemBackground` for press |
| `--gold` | `#B37200` | `@color/ru_amber` (`#8B6200`) |

**Category bucket colors** (already in `colors.xml`):
`Identity` `@color/cat_identity` #C41E3A · `Work` `@color/cat_work` #3B65DC · `Education` `@color/cat_education` #8B6200 · `Personal` `@color/cat_personal` #0D9E76 · `Media` `@color/cat_media` #7C3AED · `General` `@color/cat_general` #888888

**Spacing scale** (dp): 4, 6, 8, 13, 14, 15, 18, 22, 26, 30
**Radii** (dp): rows/cards **20**, chips/pills **100** (full), buttons **26**, icon tiles **11**, switch **20**
**Card shadow**: subtle — Material elevation **2–3dp** (the web uses a soft 18px ambient shadow; elevation 2dp is the closest native match)

### Typography
The prototype uses Google webfonts. Bundle as font resources or substitute:
- **Fraunces** (serif, weight 600) — display/wordmark/titles. Used at: wordmark 58sp, page titles 26sp, index count 46sp.
- **Hanken Grotesk** (sans, 400/500/600) — all UI text, labels, body.
- **Tiro Devanagari Hindi** — Hindi strings only (`tagline_hi`, etc.).

Type ramp (sp): page title **26** / 600 · section header **10.5** / 600 uppercase tracked +1.2 · row title **14.5** / 500 · row subtitle **12** / 400 · tab label **10** / 600 · button **16** / 600 · index count **46** / 600.

---

## Screens / Views

### 1. Bottom Tab Bar
**Target file:** `res/layout/activity_main.xml` (already hosts navigation — restyle), nav labels from `nav_home` / `nav_index` / `nav_settings`.

- **Layout:** pinned to bottom, full width with **14dp** side inset and a floating rounded container (radius 22dp), height **62dp**, surface color `@color/surface_solid` at ~88% with 2dp elevation + 1px divider hairline. Three equal-width tabs.
- **Each tab:** vertically stacked icon (22dp, stroke ~1.8) over label (10sp / 600), centered, 3dp gap.
- **States:** inactive = `@color/text_secondary`; active = `@color/ru_crimson` (icon + label). Switch instantly on tap (no color cross-fade — see note).
- **Icons:** Home = house outline; Index = list/ledger; Settings = gear. Use existing vector drawables or Material Symbols outlined.
- **Behavior:** swaps the visible Fragment (Home / Index / Settings). **Hide the tab bar entirely while the search results sheet is expanded** (the prototype fades it out + disables it).
- **Persistence:** remember the last-selected tab across process death (`SavedStateHandle` / `SharedPreferences`).

> **Implementation note (from prototype bug):** avoid animating the tab text/icon `color` across a transition — on the web engine it stuck mid-tween. Native `ColorStateList` selectors are fine and preferred; just switch state directly.

### 2. Index Screen
**Target file:** `res/layout/fragment_index.xml` (exists as a plain `ProgressBar` flow — **replace** its body with this richer layout). Strings: `btn_index`, `btn_reindex`, `files_indexed`, `search_indexing_warming_up`, status strings.

**Purpose:** user scans device storage to build/refresh the semantic index.

**Layout** (centered column, 30dp horizontal padding):
- **Wordmark** "Ru." — Fraunces 58sp, `ru_crimson`, the "." in `ru_amber`. Subtitle "on-device semantic search" 13sp secondary.
- **Progress dial** — 188dp square. A circular track (`divider` color, 6dp stroke) with a crimson progress arc (6dp, round cap) that sweeps with completion fraction. Center shows the live **count** (Fraunces 46sp, tabular numerals) over label "files indexed" (12sp secondary). On Android: a custom `View` drawing two arcs, or a styled circular `ProgressBar` overlaid with a `TextView`.
- **Phase line** (18dp tall): idle = "Tap below to scan your device"; running = "Scanning **Documents**" (current bucket bold-crimson); done = "Indexed · **N** files ready".
- **Category buckets** (done state only): wrap of pill chips, each = colored 8dp dot + bold count + muted name, on a surface pill (radius 100, 1px divider, elevation 2). Hidden until indexing completes. Counts per category.
- **Primary button** (`btn_index` → `btn_reindex` once indexed): full-pill 52dp, radius 26, `ru_crimson` fill, white 16sp/600 label, leading refresh icon. Disabled + dimmed while running, label "Indexing…".
- **Running extras:** an **indeterminate scanning bar** (248dp, 4dp, crimson, sliding) and a monospace **current-filename** ticker (11.5sp secondary). Both hidden when idle/done.
- **Hint** (12sp secondary): idle "Documents & Downloads scanned first" · running "You can keep using Ru while this runs" · done "Last indexed just now · incremental re-scan".

**Index run behavior:** tap → button disables, count animates 0→total over ~3.4s with ease-out, phase cycles through storage buckets (Documents → Downloads → DCIM/Camera → WhatsApp → Media/Music), filename ticker updates, arc fills. On finish: buckets appear, button becomes "Re-index", toast "N files indexed". (In the real app this is driven by the actual indexer service + `notification_indexing_*`, not a timer.)

### 3. Settings Screen
**Target files:** `res/layout/fragment_settings.xml` + `res/layout/item_settings_row.xml`. **Every label/subtitle below already exists in `strings.xml`** — reference them.

**Layout:** scrolling list, 18dp horizontal padding, grouped into labeled sections. Section header = 10.5sp/600 uppercase, tracked +1.2, `text_secondary`, 22dp top / 10dp bottom margin. Each group is a **card** (`surface_solid`, radius 20, elevation 2) containing rows divided by 1px `divider` hairlines.

**Row anatomy** (`item_settings_row.xml`): 14dp/15dp padding, 13dp gap →
`[36dp rounded-square icon tile, radius 11, chip bg, crimson glyph] [title 14.5sp/500 over subtitle 12sp/secondary — stacked with **6dp** gap between them] [trailing control: chevron, value text, or switch]`.
Row press state = `chip`/`selectableItemBackground`.

> The **6dp title↔subtitle gap** was an explicit correction in design review — keep it; titles and subtitles must not crowd.

**Sections & rows:**

**Search**
| Row | Strings | Trailing |
|---|---|---|
| Your name | `settings_your_name` / dynamic: name or `settings_your_name_not_set` | chevron → opens onboarding overlay |
| Semantic search | `settings_semantic` / `settings_semantic_sub` | switch (default ON) |
| Voice search | `settings_voice` / `settings_voice_sub` | switch (default ON) |
| Multilingual | `settings_multilingual` / `settings_multilingual_sub` | switch (default ON) |

**Indexing**
| Row | Strings | Trailing |
|---|---|---|
| Read file content | `settings_content` / `settings_content_sub` | switch (default ON) |
| Max files | `settings_max_files` / `settings_max_files_sub` | value text — **tap cycles** 1,000 → 2,000 → 5,000 → 10,000 |
| Auto re-index | (Refresh every 24 hours) | switch (default ON) |

**About**
| Row | Strings | Trailing |
|---|---|---|
| Version | `settings_version` (Version 1.0.0 · On-device only) | — |
| Model | `settings_model` / `settings_model_val` | — |
| Clear index | `settings_clear` / `settings_clear_sub` | **danger** (crimson name + glyph) → resets index, toast "Index cleared" |

**Switch styling:** 46×27dp track, white 21dp thumb, OFF = `divider` grey, ON = `ru_crimson`. Use `SwitchMaterial` themed to crimson.
**Footer:** centered 11.5sp secondary — "Ru · Strider Quanto — find anything in your language".
**Persistence:** all toggles, max-files index, and name persist (`SharedPreferences`).

### 4. Onboarding — "What's your name?"
**Target:** overlay/`DialogFragment` (the app already references this flow via `onboarding_name_*` strings). Opens from Settings → Your name, and may be shown on first run.

- **Layout:** full-bleed themed overlay (`ru_bg`), centered column, 30dp padding: wordmark "Ru." → title `onboarding_name_title` (Fraunces 25sp) → body `onboarding_name_sub` (13.5sp secondary, line-height ~1.55, max ~300dp) → **text field** (56dp, radius 28, surface, 1px divider, crimson focus ring) with hint `onboarding_name_hint` → primary button `onboarding_name_save` (full-width crimson pill) → text button `onboarding_name_skip` (secondary).
- **Behavior:** empty + Continue → toast `onboarding_name_empty` ("Please enter your name"). Valid → persist name, close, toast `name_saved_reindex` (or "Name saved — personal search ready"), Settings "Your name" subtitle updates to the entered name. Skip → close, no change. Enter key submits.

> **Implementation note (from prototype):** the overlay show/hide was made `visibility`/`display`-driven rather than opacity-fade because the web engine froze opacity transitions in the scaled preview. On Android use a normal `DialogFragment` or `View.VISIBLE/GONE`; entrance animation optional (translateY 12dp), never gate visibility on a fade that could stick.

### 5. Toast / Confirmation
Lightweight bottom snackbar-style confirmation (dark `ru_dark` bg, light text, crimson check icon, radius 16, ~2.4s). Used by: name saved, setting toggled (e.g. "Voice search off"), max-files changed, index cleared, index complete, and the **voice-disabled gate**. Use Material `Snackbar` themed accordingly, or the app's existing toast pattern.

---

## Interactions & Behavior (summary)
- **Tab nav:** switches Fragment, persists selection, hidden during expanded results sheet.
- **Voice gate:** if Voice search setting is OFF, tapping the mic does nothing except show toast `voice_disabled` ("Voice search is disabled in Settings"). The prototype intercepts the mic tap; mirror with the real `settings_voice` pref.
- **Max files:** tap-to-cycle value, persisted; update `settings_max_files` display.
- **Clear index:** resets count + buckets to zero, toast.
- **Re-index after name:** saving a name should trigger re-index per `name_saved_reindex` copy.
- **Animations:** count roll-up ease-out ~3.4s (real: tied to indexer progress); switch thumb 250ms; respect `prefers-reduced-motion` → on Android, honor system "remove animations" / `Settings.Global.ANIMATOR_DURATION_SCALE`.

## State Management
Persist via `SharedPreferences` (or DataStore): `userName`, `semantic/voice/multilingual/content/reindex` booleans, `maxFilesIndex`, `indexedCount` + per-category counts, `lastTab`. Index progress/counts in the real app come from the existing indexing service — wire the dial/buckets/phase to its progress callbacks rather than the prototype's timer.

## Assets
- **Icons:** simple stroked line icons (~1.8 stroke) — home, list, gear, user, target, mic, globe, document, sliders, refresh, info, chip, trash, chevron, check. Use Material Symbols (outlined) or existing vector drawables; no custom art needed.
- **Decorative doodle pattern & Ink boil filter:** **not** ported (see Fidelity).
- **Fonts:** Fraunces, Hanken Grotesk, Tiro Devanagari Hindi (Google Fonts) — bundle as `res/font` or use Downloadable Fonts.

## Files in this bundle
- `Ru - App.html` — the hifi prototype (open in a browser). Home tab = existing screen; **Index**, **Settings**, the **tab bar**, **onboarding**, and **toast** are what this handoff covers.
- `DESIGN_TOKENS.md` — condensed token reference.
- `screenshots/annotated-1-home.png` — Home + tab bar, numbered callouts → Android targets.
- `screenshots/annotated-2-index.png` — Index screen (done state), dial/buckets/button spec.
- `screenshots/annotated-3-settings.png` — Settings rows, sections, switches, 6dp gap.
- `screenshots/annotated-4-onboarding.png` — "What's your name?" overlay spec.

Each annotated screenshot has a numbered legend on the right mapping every marker to its component, exact resource (`@color/…`, `strings.xml` name), and the layout file to edit.

## Target codebase files to touch
- `res/layout/activity_main.xml` — bottom tab bar styling + results-sheet hide rule
- `res/layout/fragment_index.xml` — replace body with dial + buckets + button layout
- `res/layout/fragment_settings.xml` + `res/layout/item_settings_row.xml` — sectioned cards, 6dp title/subtitle gap, switch/value/chevron variants
- onboarding `DialogFragment` (uses existing `onboarding_name_*` strings)
- `res/values/colors.xml`, `strings.xml` — **already contain everything; reference, don't duplicate**
- Kotlin: Fragment swap + persistence, max-files cycle, voice gate, clear-index, name-save → re-index
