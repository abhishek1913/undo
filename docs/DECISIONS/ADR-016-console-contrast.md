# ADR-016: Console colors that meet 4.5:1 text contrast

Status: accepted (M5)

## Context

Section 20.2 fixes the color tokens and says status is a dot plus a word. Section 20.6 says text
contrast must be at least 4.5:1 in both themes, checked with axe. Some token pairs break 20.6
when used as text or as a background for text:

| Pair | Ratio |
|---|---|
| white on `--accent` light `#C75050` (primary button) | 4.48 |
| white on `--accent` dark `#E07A6E` | 2.93 |
| `--warn` light `#B7791F` as text on white | 3.64 |
| `--muted` light `#8C847B` as text on white | below 4.5 |
| white on `--warn` dark `#E0A84A` (nav badge) | 2.13 |

axe in the Playwright suite reported these as serious `color-contrast` violations.

## Decision

The PRD tokens stay as they are. Their use changes, and two derived tokens are added:

1. **Status pill:** the dot has the status color and the word is in `--ink`. The status is still
   "dot + word", and color is still never the only signal.
2. **Primary button:** new tokens `--accent-fill` / `--on-accent`. Light: `#BE4A4A` with white text
   (4.92:1), one small step darker than `--accent`. Dark: `#E07A6E` (the dark accent unchanged)
   with `#151311` text (6.34:1). `--accent` is still used for focus rings and accents.
3. **Nav badges:** outlined (border in `--warn` / `--bad`, a 15 % tint, text in `--ink`) instead of
   white text on a solid fill.
4. **Scrollable JSON blocks** can take keyboard focus (`tabIndex=0`, `role=region`, label). axe
   rule `scrollable-region-focusable`.

## Consequences

`e2e/console.spec.ts` runs axe on Overview, Runs, Run detail, Approvals, Action detail and
Settings/keys in light and dark: 0 serious or critical violations. Big tile numbers (28 px) still
use status colors; large text needs only 3:1, and all status tokens meet that.
