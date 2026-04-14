# Echo — Deck Import Spec (Paste-to-Import)

> **Status:** Draft v1 · **Scope:** Product + UX spec for the primary deck-import flow. Engineering RFC comes later.
> **Reads alongside:** [`design/DESIGN_GUIDELINE.md`](../design/DESIGN_GUIDELINE.md)

---

## 1. Summary

Paste-to-Import is **the primary way users build decks in Echo v1**. The user pastes any structured or semi-structured text — a vocab list, a Notion table, a spreadsheet column, a chat message, a glossary — and Echo turns it into cards. Echo auto-detects the separator, shows a live preview as real flip-cards (not a table), and hands the parsed cards to a swipe-based triage queue before committing them to a deck.

It is inspired by Anki's "Import File" pattern but rebuilt for touch: no file pickers, no column indexes, no desktop-era settings dialog. The user pastes, glances at the preview, taps import.

**Who it's for:** Every Echo user. Language learners pasting vocab lists, students pasting glossaries, hobbyists pasting their own notes. No technical literacy required.

**Why this instead of AI or `.apkg`:** It's instant, offline, free, private, and covers the single most common import shape — two columns of text the user already has somewhere. AI import and OCR (see §14) will come later as alternative entry points into the same triage queue.

---

## 2. Goals

- **One-tap primary import:** Users should reach the paste screen in ≤2 taps from Decks or the empty state.
- **Zero-config in the common case:** Auto-detect the separator correctly for ≥90% of pastes. The user should rarely need to touch the override.
- **Preview as cards, not a table:** Show the first 3 parsed items as real Echo flip-cards so the user feels the result before committing.
- **Triage before commit:** Every imported card passes through the swipe queue. Users approve, edit, or discard each card before it enters their deck.
- **Native feel:** iOS and Android variants follow §4 of the brief — SF Pro / Roboto, system sheets, platform haptics on successful import.
- **Accessible:** Full VoiceOver / TalkBack support, dynamic type, reduce-motion alternatives.

## 3. Non-goals (v1)

Explicitly deferred — do **not** design or build:

- `.apkg` / `.colpkg` Anki file import
- CSV / TSV file upload from device storage
- AI-generated cards (paste article → cards)
- OCR / photo-of-page import
- Audio recording during import
- Image-folder bulk import
- Drag-and-drop (desktop only — Echo is mobile in v1)
- Cloze deletion syntax (`{{c1::...}}`)
- Re-import / sync with an external source
- Private decks of any kind (see §11)

---

## 4. User stories

1. **Vocab list from a textbook.** A Spanish learner pastes 40 lines of `palabra — word` from their notes. Echo detects the em-dash separator, previews three cards, they swipe through the triage queue and commit. Total time: under a minute.
2. **Notion table.** A student copies a 2-column Notion table into Echo. Echo sees the pipe-delimited markdown table, parses it, previews, done.
3. **Hand-typed quick list.** A user types `capital of France: Paris` and a few similar lines directly into the paste box. Echo auto-detects `: ` and parses each line into a card.
4. **Spreadsheet with tags.** A power user pastes three tab-separated columns: front, back, tags. Echo detects three fields and prompts them to map the third column to tags. Tags split on commas within the cell.
5. **Re-opener.** A user who already imported once taps "+" on Decks. The paste screen is empty and focused on the text field. No history, no drafts — each import is a fresh canvas.

---

## 5. UX flow

### 5.1 Entry points

Three ways to reach the paste screen:

- **Decks tab → floating "+" → "Paste to import"** (primary)
- **Decks empty state** — the CTA under the illustration reads *"Paste a list to get started"*
- **Deck editor ([§6.5 of brief](../design/DESIGN_GUIDELINE.md)) → "Add cards in bulk"** — imports into an existing deck instead of creating a new one

All three routes land on the same paste screen.

### 5.2 Paste screen

- **Top:** platform-native nav bar. Title: *"Paste cards"*. Left: cancel. Right: disabled *"Next"* button until text is pasted.
- **Body:**
  - A large rounded text field with the hint *"Paste your list here — one card per line"*. The field auto-focuses and auto-expands as text grows.
  - Below the field, once text exists: a **detected-separator chip** reading e.g. *"Detected: em-dash · tap to change"*. Tapping opens a bottom sheet with the override options (§6).
  - Below the chip: **live preview** — the first three parsed items rendered as real Echo flip-cards using the existing Card component from [§7 of brief](../design/DESIGN_GUIDELINE.md). The user can tap any preview card to flip it and see the back. The preview updates within ~150 ms of any change to the text or separator.
  - A subtle counter: *"42 cards will be imported"*.
- **Sticky footer:** *"This deck will be public on your Pubky"* notice (see §11) + primary *"Next"* button.

### 5.3 Field mapping (only when ≥3 columns)

If the parser detects three or more columns:

- Instead of jumping straight to preview, show a small **column mapping row** above the preview: three chips labeled with the first row's content, each tappable to assign `Front / Back / Tags / Ignore`.
- Defaults: col 1 → Front, col 2 → Back, col 3 → Tags, col 4+ → Ignore.
- Preview re-renders on every change.

### 5.4 Triage queue

Tapping *"Next"* hands the parsed cards to the triage queue:

- Full-screen swipe interface. One card at a time, rendered with the full Card component (including flip).
- **Swipe right:** keep. **Swipe left:** discard. **Tap edit icon:** inline edit front / back / tags without leaving the queue.
- Progress indicator at top: *"12 of 42"*.
- Skip-to-end shortcut: *"Approve all remaining"* in the nav bar for users who trust their paste.
- Haptic tick on each swipe (iOS `UIImpactFeedback.light`, Android equivalent).

### 5.5 Commit

- On triage completion, a confirmation screen: *"42 cards ready. 3 discarded."* + deck metadata form (title, description, cover image, tags). Tags pre-fill from any tag column plus any tags the user had added in triage.
- Primary action: *"Publish deck"* (see §11 on why it's "publish," not "save").
- Success: celebratory micro-animation (§3 of brief), haptic success, land on the new deck detail screen.

### 5.6 Undo window

A snackbar appears on the deck detail screen for 10 s after commit: *"Deck published. Undo"*. Tapping undo deletes the deck from the user's homeserver and returns to the paste screen with the original text still in the field.

---

## 6. Separator detection rules

The parser tries rules **in this order** and stops at the first rule that produces a consistent parse (≥80% of non-empty lines parse cleanly):

1. **Markdown table** — lines starting with `|`, optional `---` divider row
2. **Blank-line-separated pairs** — `Q\nA\n\nQ\nA` pattern
3. **Tab** (`\t`)
4. **Semicolon** (`;`)
5. **Pipe** (`|`) outside of markdown tables
6. **Em-dash with spaces** (` — `) or en-dash (` – `)
7. **Colon with space** (`: `)
8. **Comma** (`,`) — last resort; risky for natural-language answers containing commas
9. **Fallback: single column** — treat each non-empty line as front-only; back is empty, user fills it in triage

The user can always override via the detected-separator chip. The override sheet lists: `Auto`, `Tab`, `Semicolon`, `Pipe`, `Em-dash`, `Colon`, `Comma`, `Blank line`, `Custom…` (opens a single-character input).

---

## 7. Parsing rules

- **Quoting:** double-quoted fields support embedded separators and newlines, CSV-style. `""` escapes a literal quote.
- **Trimming:** leading and trailing whitespace stripped from every field.
- **Blank lines:** ignored unless the active rule is blank-line-separated pairs.
- **Max paste size:** 10,000 characters OR 500 cards, whichever comes first. Over that, show a soft error: *"That's a lot. Try splitting into smaller decks for now."*
- **Dedupe:** exact-match duplicates within a single paste are collapsed silently. Near-duplicates are not touched in v1.
- **Character encoding:** UTF-8 only. Paste buffer is read as UTF-8; invalid sequences are replaced with `�` and the affected line is flagged in triage.
- **Line endings:** `\r\n`, `\n`, and `\r` all normalized to `\n`.

---

## 8. Field mapping details

- **Defaults:** col 1 → Front, col 2 → Back, col 3 → Tags. If only two columns, no mapping UI appears.
- **Tags column:** comma-separated within a cell. `es,vocab,a1` → three tags.
- **Ignore:** users can mark a column ignored; it is dropped from the preview and the committed cards.
- **Reassignment:** tapping a column chip cycles through `Front → Back → Tags → Ignore`. A long-press opens a menu with the same options.

---

## 9. Edge cases & errors

| Case | Behavior |
|---|---|
| Single-column paste | Treat every line as front-only. Back is empty, user fills in triage. Show a banner: *"No separator found. Each line became a card front — fill in the backs in the next step."* |
| Mismatched row lengths | Rows with fewer fields than the majority show as red in preview with a warning icon. User can edit or discard in triage. |
| Paste over max size | Soft error (§7). "Next" button disabled. |
| Pasted nothing but whitespace | "Next" stays disabled. No error. |
| Invalid UTF-8 | Replace with `�`, flag the line, let the user fix or discard in triage. |
| RTL text (Arabic, Hebrew) | Fully supported. Preview cards render RTL per device locale. Separator detection is unaffected. |
| Very long single card (>2000 chars on one side) | Card commits, but triage shows a *"Long card"* flag. Text is not truncated. |
| Duplicate cards within paste | Silently deduped before preview. Counter reflects the deduped total. |
| All lines are identical | One card after dedupe. Warning in triage: *"Just one unique card in that paste."* |

---

## 10. States

Every state from §6 of the brief is enumerated here. The designer must produce each one.

1. **Empty** — no text pasted. Text field auto-focused. Preview area shows a faint illustration and *"Your cards will show up here as you paste."*
2. **Paste detected** — text just landed. Separator chip and preview animate in.
3. **Preview loading** — parser running. Preview cards show skeletons (~150 ms max).
4. **Preview ready** — first three cards rendered. Counter populated. "Next" enabled.
5. **Parse error** — paste is unparseable or over-size. Red banner, "Next" disabled, suggestion offered.
6. **Mapping required** — ≥3 columns detected. Column chips appear above preview.
7. **Triage in progress** — full-screen swipe UI.
8. **Importing** — triage done, cards being written to the homeserver. Progress indicator.
9. **Success** — confirmation screen with counts.
10. **Undo window** — snackbar visible on deck detail for 10 s.
11. **Post-undo** — user is back on the paste screen, text preserved.
12. **Network error on commit** — toast: *"Couldn't reach your homeserver. Try again?"* with retry. Triage decisions preserved.

---

## 11. Pubky considerations

- **No private decks in v1.** Every imported deck is published to the user's Pubky homeserver and is discoverable via Discover and the user's profile.
- This **overrides** the public/private toggle described in [§9.5 of the brief](../design/DESIGN_GUIDELINE.md#96-self-custodial-framing). Flag as an open question (§13) for the designer and PM.
- The paste screen and commit screen must both show a plain-language notice: *"This deck will be public on your Pubky."* — no fine print, no tooltips, no toggle.
- The primary commit button reads **"Publish deck"**, not "Save deck" or "Create deck." The word choice matters: it tells the user something public is about to happen.
- Tags from the tags column are written via Pubky's native tag primitive ([§9.3 of brief](../design/DESIGN_GUIDELINE.md)), not a custom Echo system.
- The author field on the published deck resolves to the user's pubky identity per [§9.2 of brief](../design/DESIGN_GUIDELINE.md).

### 11.1 Published deck shape

Each published deck is stored as multiple records under the author's pubky so that edits and sync stay cheap:

```
/pub/echo/decks/{deckId}/manifest.json
/pub/echo/decks/{deckId}/cards/{cardId}.json
/pub/echo/decks/{deckId}/media/{sha256}.{ext}
```

- **Manifest** carries deck metadata (title, description, cover, tags) plus an ordered list of card IDs with `updated_at` timestamps. Reordering rewrites only the manifest.
- **One record per card.** Editing a single card rewrites that card's record and bumps its entry in the manifest — no need to rewrite the whole deck.
- **Media under the deck path.** Images and audio are stored as blobs keyed by sha256; cards reference them by relative path. Dedupe within a deck is free.
- **Sync is driven by `updated_at`.** Clients diff the manifest against their cache and only fetch cards whose timestamp advanced. Deletions are implicit: a card ID missing from the manifest is gone.
- **Last-write-wins** for v1; no multi-device conflict resolution and no tombstones.

See [Architecture.md §8](./Architecture.md#8-data-model--persistence) for the full JSON schemas and Kotlin domain types.

---

## 12. Accessibility

- **VoiceOver / TalkBack:** Every preview card announced as *"Card 1 of 3 preview, front: …, double-tap to flip."* Separator chip announced as *"Separator: em-dash. Double-tap to change."* Triage swipes exposed as explicit *"Keep"* and *"Discard"* buttons for switch-control and non-gesture users.
- **Dynamic type:** Paste box font and preview card text both scale with OS setting. At the largest setting, the preview switches from 3 cards to 1 card.
- **Reduce Motion:** Triage swipe becomes tap-to-decide with Keep / Discard buttons. Flip animation in preview becomes a crossfade.
- **Colorblind:** Any status color in the preview (red for mismatched rows, green for the dedupe badge) is paired with an icon and text label.
- **Tap targets:** Separator chip and column-mapping chips are ≥44×44 pt iOS / 48×48 dp Android.
- **Contrast:** Paste box text, separator chip, and banners meet WCAG AA on both themes.

---

## 13. Open questions

1. **No private decks** — confirm with PM that publishing every imported deck is intentional for v1 and not just "not yet built." The brief §9.5 implies a toggle exists. This spec removes it. Needs a decision before design kickoff.
2. **Deck metadata timing** — should title / description be required before triage or after? Current spec says after. Alternative: pre-triage so users can abandon early. Designer call.
3. **Triage "Approve all remaining"** — is the bulk-approve shortcut too easy to misuse? Could land junk cards. Consider requiring swipe-through of at least the first N cards before unlocking it.
4. **Max paste size** — is 10k chars / 500 cards the right cap? Anki imports can be tens of thousands. We can lift the cap later once the triage queue proves it can handle it.
5. **Image and audio in paste** — out of scope for v1, but does the parser need to gracefully ignore pasted image data so the user isn't blocked? Likely yes.
6. **Undo after leaving the screen** — if the user leaves the deck detail before the 10 s timer expires, the undo vanishes. Is that acceptable? Probably yes; matches standard snackbar behavior.

---

## 14. Future extensions

These are natural next steps that reuse this spec's triage queue and commit flow:

- **AI "Paste anything"** — user pastes an article or transcript, Claude/Gemini extracts Q/A pairs, the result feeds straight into the same triage queue. No new commit UI needed.
- **OCR photo import** — textbook page → Vision / ML Kit text → same triage queue.
- **URL import** — paste a YouTube or article URL, fetch + AI-extract, same triage queue.
- **`.apkg` import** — once Anki refugees start asking, parse on-device and route through triage.
- **Private decks + public/private toggle** — if the open question in §13 resolves in favor of private decks, add a toggle to the commit screen.
- **Drafts** — save an unfinished paste if the user cancels, restore on next entry.

The triage queue is the reusable spine; every future import source plugs into it.

---

*End of spec. Hand off to designer for screens covering §5 flow and §10 states.*
