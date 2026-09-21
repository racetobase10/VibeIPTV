# Vibe IPTV v1.2 — UI Polish Plan ("world class" pass)

## Reference material studied
- One genuine MYTVOnline2 in-app screenshot (welcome / add-portal screen, from a
  public setup guide). Design language observed: near-black charcoal background,
  bright blue primary accent, large headline type with muted secondary text, big
  rounded cards (16dp+) with circular icon badges, generous whitespace.
- Formuler launcher screenshots were NOT useful (launcher, not in-app UI).
- A Play Store screenshot found was a different app (green theme) — discarded.

## Hard constraints
- Original code and assets only. NO Formuler/MYTVOnline names, logos, or copied
  artwork anywhere. Take only the *design language* (dark theme, blue accent,
  card layout, spacing rhythm) — never brand elements.
- TV D-pad navigation must stay first-class: every focusable element needs a
  clear focused state (ring / scale / accent background).
- All existing features keep working. Polish is visual + structural, not
  behavioral. No feature removals.
- Min SDK 26, target 35. Keep edge-to-edge inset handling added in v1.1.

## Design tokens (apply app-wide)
- Background: near-black `#101014` (keep existing `@color/bg` if close, else update)
- Surface/cards: `#1B1B22` with 16dp corner radius
- Primary accent: bright blue `#2E9BFF` (buttons, focus ring, active states)
- Text primary `#FFFFFF`, secondary `#B3B3B3`, tertiary/hint `#7A7A85`
- Spacing scale: 8 / 12 / 16 / 24 dp. Screen edge margin 16dp phone, 24dp+ TV.
- Type scale: headline 22sp bold, title 18sp, body 14sp, caption 12sp
- Focused card: 2dp blue stroke + 1.06 scale (TV); keep ripple/touch feedback on phone
- Unify the two button drawables (`btn_accent_focusable`, `item_focusable`) into
  one coherent family: filled blue primary, outlined/ghost secondary.

## Per-screen checklist
1. **Portal setup / manager** — Welcome-style empty state inspired by the
   reference: app wordmark, one-line headline, two large cards
   ("Add Portal" blue filled, "Add Playlist" surface). Portal list rows become
   cards with connection-status dot and edit/delete affordances.
2. **Home** — Sectioned dashboard: "Continue watching" rail (if data), then
   large cards for Live TV / Movies / Series / Guide / Favorites / Recordings.
   Cards show icon + label + count subtitle. Consistent 16:9 or square tiles.
3. **Live TV** — Left category rail (fixed width, icons), channel rows: logo
   thumb (rounded, 16:9), channel name (title), now/next EPG two-line caption,
   favorite star. Selected row: blue tint + focus ring. Keep search bar styled
   to match.
4. **Guide (EPG timeline)** — Sticky time header, channel gutter with logos,
   now-line indicator in accent blue, program blocks with rounded corners and
   readable 12sp titles. Empty slots dimmed, not blank.
5. **Movies / Series grids** — True poster aspect (2:3) cards, rounded 12dp,
   title + year + rating badge (with source label, e.g. TMDB 7.5) overlaid or
   below. Consistent 16dp grid spacing. Skeleton/placeholder while loading.
6. **Detail screens** — Hero: backdrop art with bottom gradient scrim, title
   (headline), meta row (year • duration • rating badge), overview (body),
   primary actions row (Play blue filled, + Favorite, + Resume). Episodes as
   numbered cards with stills.
7. **Player** — Keep v1.1 compact OSD buttons. Polish: OSD bar becomes a single
   rounded (24dp) floating pill with scrim, top banner gets gradient scrim
   instead of flat translucent box, channel OSD (number/info banner) styled to
   match. Loading spinner uses accent blue. Error toasts -> styled snackbar.
8. **Settings** — Grouped sections with headers (Playback, EPG, Ratings,
   Parental, About), consistent 56dp rows, switches in accent blue, summary
   text in secondary. API-key fields with proper input styling.
9. **Recordings / Favorites** — Empty states with icon + headline + action
   ("No recordings yet", "Browse Live TV"). Rows match Live TV card style.
10. **Dialogs** — All AlertDialogs get the app theme: rounded 20dp, dark
    surface, blue positive button. Subtitle picker, track picker, sleep timer,
    PIN pad included.

## Execution notes
- Work screen by screen; build after each screen (`assembleRelease`) to catch
  resource errors early.
- Do a final visual-consistency sweep: grep for hardcoded colors/margins that
  bypass the tokens and replace them.
- Update README "What's new" for v1.2.
- Deliverable: versionCode 3, versionName '1.2', signed APK, GitHub release v1.2.
