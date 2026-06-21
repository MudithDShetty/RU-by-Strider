# RU App Shell — Design Tokens (condensed)

All map to **existing** `res/values/colors.xml` / `strings.xml`. Reference resources; don't hardcode.

## Colors (existing resources)
| Role | Resource | Hex |
|---|---|---|
| Canvas / bg | `ru_bg` | #FEF9E6 |
| Primary text / ink | `text_primary` | #1A1530 |
| Secondary text | `text_secondary` | #888888 |
| Hint text | `text_hint` | #AAAAAA |
| Accent / crimson | `ru_crimson` | #C41E3A |
| Accent light | `ru_crimson_light` | #F5E6E9 |
| Gold | `ru_amber` | #8B6200 |
| Surface (card) | `surface_solid` | #FFFFFF |
| Divider / hairline | `divider` | #0D000000 (5% black) |

### Category buckets
| Bucket | Resource | Hex |
|---|---|---|
| Identity | `cat_identity` | #C41E3A |
| Work | `cat_work` | #3B65DC |
| Education | `cat_education` | #8B6200 |
| Personal | `cat_personal` | #0D9E76 |
| Media | `cat_media` | #7C3AED |
| General | `cat_general` | #888888 |

## Spacing (dp)
4 · 6 · 8 · 13 · 14 · 15 · 18 · 22 · 26 · 30

## Radius (dp)
icon tile **11** · card/row **20** · switch **20** · button **26** · pill/chip **100 (full)** · phone-field **28**

## Elevation
cards / tab bar / chips: **2dp** (≈ web's soft 18px ambient shadow)

## Typography
| Use | Family | Size (sp) | Weight |
|---|---|---|---|
| Wordmark "Ru." | Fraunces | 58 | 600 |
| Index count | Fraunces | 46 | 600 (tabular) |
| Page title | Fraunces | 26 | 600 |
| Onboarding title | Fraunces | 25 | 600 |
| Section header | Hanken Grotesk | 10.5 | 600, UPPER, +1.2 tracking |
| Row title | Hanken Grotesk | 14.5 | 500 |
| Row subtitle | Hanken Grotesk | 12 | 400 |
| Button | Hanken Grotesk | 16 | 600 |
| Tab label | Hanken Grotesk | 10 | 600 |
| Body / hint | Hanken Grotesk | 12–13.5 | 400 |
| Hindi text | Tiro Devanagari Hindi | — | 400 |

## Components quick-ref
- **Tab bar:** 62dp tall, 14dp side inset, radius 22, 3 equal tabs, icon 22dp + label 10sp. Inactive `text_secondary` → active `ru_crimson`. Hidden when results sheet expanded.
- **Switch:** 46×27dp track, 21dp white thumb; OFF `divider`, ON `ru_crimson`.
- **Settings row:** 36dp icon tile (radius 11) + title/subtitle stacked with **6dp** gap + trailing (switch / value / chevron). 1px `divider` between rows.
- **Index dial:** 188dp, 6dp strokes, track `divider` + arc `ru_crimson` round-cap.
- **Primary button:** 52dp pill, radius 26, `ru_crimson` fill, white 16sp/600.
- **Bucket chip:** pill (radius 100), 8dp category dot + bold count + muted name.
- **Toast:** `ru_dark` bg, light text, crimson check, radius 16, ~2.4s.
