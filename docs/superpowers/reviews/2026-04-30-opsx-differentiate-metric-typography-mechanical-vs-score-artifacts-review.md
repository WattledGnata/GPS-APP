# differentiate-metric-typography-mechanical-vs-score artifacts review

Date: 2026-04-30
Change: `differentiate-metric-typography-mechanical-vs-score`
Scope: OpenSpec artifacts review for TrackTech metric typography role split.

## Result

CHANGES REQUESTED.

`openspec validate differentiate-metric-typography-mechanical-vs-score --strict` passes, but the artifact set still has two review findings.

## Findings

### [P2] FRESH seconds value would still render a letter in Mechanical

Files:

- `openspec/changes/differentiate-metric-typography-mechanical-vs-score/design.md:210-214`
- `openspec/changes/differentiate-metric-typography-mechanical-vs-score/specs/metric-typography-roles/spec.md:111-115`
- `openspec/changes/differentiate-metric-typography-mechanical-vs-score/tasks.md:61-63`

The artifacts classify `GpsDetailsScreen` FRESH as a Mechanical field. In the current code, however, `freshDisplayStr` becomes `"${freshMs / 1000}.${(freshMs % 1000) / 100}s"` for 1-9.9s, while `freshDisplayUnit` is `null`. If apply follows the spec, the `s` suffix remains inside the Mechanical/DSEG7 value, preserving the exact kind of letter deformation this change is supposed to remove. Either split seconds into `value = "1.2"` + `unit = "s"` and then keep FRESH Mechanical, or classify FRESH as Score when the value contains an inline suffix. The cleaner contract is to normalize the formatter so Mechanical only receives numeric glyphs and units stay in `UiTextSmall`.

### [P2] tasks §0.2 baseline count is internally inconsistent

File:

- `openspec/changes/differentiate-metric-typography-mechanical-vs-score/tasks.md:28-40`

The precheck says `5 处调用 + 3 处定义 = 8 处命中`, but the bullets enumerate 3 alias definitions + 3 `MetricNumber` references + `RecordsHomeScreen` + 2 `TrackTechTestExecutionScreen` refs + `GpsDetailsScreen`, which is 10 total hits. Current grep confirms the broader count. This is not a code design flaw, but it will mislead apply/review at the very first gate. Update the expected count and wording, preferably separating `TrackTechTypography.kt` definitions, `MetricNumber.kt` internal mapping refs, and direct screen refs.

## Decision Review

- D1 dual-role typography + deprecated aliases: Accepted after the findings above are fixed.
- D2 default `MetricKind.Score`: Accepted. It is the safer default.
- D5 split list: Mostly accepted, but FRESH needs the seconds suffix fix.
- D7 `ScoreSmall` ExtraBold Italic first pass: Accepted, with true visual tuning left to manual gate.
- D8 no unit tests: Accepted for this visual-only role split, assuming grep gates are corrected.

## Current Gate

Do not apply yet. Fix the two artifact issues above, rerun:

```bash
openspec validate differentiate-metric-typography-mechanical-vs-score --strict
```

Then request a mini review.

## Mini Review V2

Date: 2026-05-01

`openspec validate differentiate-metric-typography-mechanical-vs-score --strict` passes.

### Resolved

- Previous P2: FRESH seconds value still rendering a letter in Mechanical.
  - Resolved by D9: split `"1.2s"` into value `"1.2"` plus unit `"s"`, so Mechanical receives only numeric glyphs.
- Previous P2: tasks §0.2 baseline count inconsistent.
  - Resolved by splitting the 10 expected hits into Typography definitions, MetricNumber internal mapping, and screen direct references.

### New Finding

#### [P2] TrackTechRow ellipsis requires a constrained text column

Files:

- `openspec/changes/differentiate-metric-typography-mechanical-vs-score/specs/track-tech-card-single-line-policy/spec.md:46-56`
- `openspec/changes/differentiate-metric-typography-mechanical-vs-score/tasks.md:229-231`

The new single-line policy requires `TrackTechRow` title/subtitle to use `maxLines = 1` and `overflow = TextOverflow.Ellipsis`, but current `TrackTechRow` lays out icon + text in an unweighted inner `Row`, then places the chevron as a sibling in a `SpaceBetween` row. In Compose, ellipsis only works when `Text` is measured with a bounded max width. With the current unconstrained `Column`, long subtitles can keep their full intrinsic width and push/overlap the trailing chevron instead of ellipsizing. The tasks/spec should require adding a width constraint, such as `Modifier.weight(1f)` on the text `Column` or the leading content row, plus an explicit gap before the chevron. Then add a grep/source check for that constraint or include it in the manual gate.

### Non-Blocking Notes

- `proposal.md` lists `TrackTechBottomNav.kt` in affected files but also says `TrackTechBottomNav` is not affected under "不受影响". This should be clarified, but it is wording-only if the implementation follows tasks.

## Mini Review V3

Date: 2026-05-01

Result: PASS. No P1/P2 findings.

`openspec validate differentiate-metric-typography-mechanical-vs-score --strict` passes.

The previous P2 about `TrackTechRow` ellipsis has been addressed:

- `design.md` now explains that `maxLines = 1` plus `TextOverflow.Ellipsis` requires bounded width.
- `track-tech-card-single-line-policy/spec.md` adds a dedicated `TrackTechRow` layout requirement.
- `tasks.md` §3.0 now requires deleting `Arrangement.SpaceBetween`, adding `Modifier.weight(1f)` to the leading row, adding `Modifier.weight(1f, fill = false)` to the text column, and inserting a fixed spacer before the chevron.
- `tasks.md` §7.2 adds grep gates for the same layout constraints.

Greenlight to apply.
