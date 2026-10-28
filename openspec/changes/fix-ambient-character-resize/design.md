## Context

See proposal.md for the reported symptom. AmbientStage's absolute container currently stretches between top and bottom insets. Expanded chat raises its bottom edge by 55vh, and the portrait's percentage maxima shrink its content. Live2D uses the same changing parent. Native window minima are 360 by 560 CSS pixels.

## Goals / Non-Goals

**Goals:** Stable shared portrait dimensions and bounded positioning in the ambient desktop surface.

**Non-Goals:** New user scale settings, changes to native window limits, Galgame's responsive scene composition, and service changes.

## Decisions

1. Use one 350 by 540 CSS-pixel ambient character surface. This fits the smallest supported native window with horizontal margins and top/bottom clearance. A window resize cannot change its dimensions. Keeping percentage maxima on a viewport-sized parent would retain the original coupling.
2. Keep the character horizontally centered. Position its fixed surface above expanded chat when room permits, clamping its top to 8px. In compact windows chat's existing foreground layer overlaps the lower portrait. Moving the surface without a top bound would clip its head; scaling to avoid overlap would reproduce the bug.
3. Fill that surface for both PNG and Live2D. PNG retains object-fit: contain, including the Live2D fallback. Remove the redundant scoped Live2D size rule so ambient sizing lives in one stylesheet.

## Risks / Trade-offs

- [Compact expanded chat covers the lower portrait] → Preserve the character's size and visible top; keep the established chat foreground layer and controls usable.
- [PNG assets have different aspect ratios] → Contain them within the fixed surface without stretching.
- [Existing desktop process uses a built renderer] → Rebuild the production renderer; existing renderer refresh/restart loads the fix.

## Migration Plan

Update ambient CSS and remove its duplicate scoped rule, then build the renderer and record specification/source inspection. No preference migration is required. Rollback is restoring the prior ambient CSS.
