# Arist.ai × AhaKey OLED brand specification

Authoritative sources:

- `E:\Projects\Arist Demo\src\components\shared\Logo.tsx`
- `E:\Projects\Arist Demo\public\favicon.svg`
- `E:\Projects\Arist Demo\public\arist-logo-white.svg`

## Canonical brand mark

- Name: D-Anchored (final/canonical)
- Geometry: golden-ratio rectangle, `100 × 61.8034` (`1.6180339887:1`)
- Vertical cut: `x = 61.8034`
- Upper-right anchor cell: `38.1966 × 38.1966`
- Canonical stroke width: `2.5` viewBox units
- The gold cell shares the outer frame's top/right edges and the vertical divider. Draw one shared outer grid; do not stack a second top/right stroke.

## Color and wordmark

- Dark background: `#0A0A0D`
- Light frame/text: `#F5F2EA`
- Gold gradient at 135 degrees: `#FEF3C7 → #FBBF24 → #F59E0B`
- Wordmark: `Arist` weight 700; `.ai` weight 600 with the gold gradient
- Tracking: `-0.035 × font size`
- Typeface: Modern Gothic Trial VF from the Arist.ai repository; Inter/Arial/Helvetica are the official SVG fallbacks

## AhaKey output

- Canvas: exactly `160 × 80 px`
- File: RGB PNG; the AhaKey Studio encoder converts it to RGB565
- Primary/recommended: canonical mark only, `112 × 69 px`, centered on `#0A0A0D`
- Alternative: horizontal Mark + `Arist.ai` lockup; mark `50 × 30.9 px`, gap `8 px`, wordmark `24 px`
- Keep high contrast and the canonical geometry. Do not introduce cyan/purple, arrows, sparks, slogans, shadows, 3D effects, or extra symbols.
