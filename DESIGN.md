# Open Hype Design Notes

## Palette

- Brand accent: warm orange for selected navigation, playback progress, active chips, and favorite emphasis.
- Phone browsing canvas: warm cream with dark feature cards.
- Player canvas: near-black, subtly warm, with artwork glow and orange controls.
- Android Auto: system media templates own most chrome. App metadata should remain compact and high-contrast.

## Typography

- Product UI uses the platform sans family through Material 3 typography.
- Headlines are bold but should stay compact enough for translated titles and Samsung display scaling.
- Body copy should truncate deliberately in cards and car rows.

## Components

- Track cards: fixed artwork sizing, clear title/artist/source hierarchy, visible play affordance.
- Mini-player: dense, always reachable, with previous/play-next/open controls and progress.
- Full player: artwork, track identity, scrubber, and transport controls are the primary hierarchy.
- Settings: grouped controls, clear storage state, explicit destructive action.

## Motion

- Motion exists for state feedback and spatial continuity only.
- Tap feedback should be fast, subtle, and reusable.
- Gestures use short, interruptible spring/tween transitions.
- No page-load choreography in the product surface.

## Accessibility

- Touch targets should stay at or above 44dp, with 48dp preferred on phone.
- Pressable elements need visible feedback and semantic roles.
- Text contrast should meet WCAG AA on both cream and dark surfaces.
