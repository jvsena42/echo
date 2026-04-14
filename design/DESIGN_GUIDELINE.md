# Echo — Designer AI Brief

> A self-contained design brief for producing screens, components, and a design system for **Echo**, a native iOS + Android flashcards app. Read top-to-bottom; no external context required.

---

## 1. Product overview

**Echo** is a mobile flashcards app that fuses three influences:

- **Duolingo TinyCards** — playful, visual, card-flipping fun
- **Anki** — serious spaced-repetition (SRS) scheduling for long-term retention
- **Pubky** — decentralized identity and social graph (login, follow, tag, share)

**Who it's for:** Curious learners (languages, study, hobbies) who want Anki's effectiveness without its intimidation, plus a social layer where friends can publish and discover decks without a centralized account system.

**Platforms:** Native iOS and native Android. Shared brand, platform-native components.

**Positioning sentence:** *"Echo is the flashcards app where serious spaced repetition feels like a game, and every deck you make can be shared with friends — no email, no password, just your key."*

---

## 2. Brand & personality

**Personality:** Playful, vibrant, encouraging, a little mischievous. Think bright primaries, rounded everything, micro-celebrations on correct answers. Never clinical, never crypto-bro.

**Tone of voice:**
- Warm and second-person ("Nice work — three more to go.")
- Short sentences. Active verbs.
- Celebrates effort, not just correctness ("You showed up today. That's the hard part.")
- Avoids jargon — both academic ("interleaved retrieval practice") and crypto ("self-sovereign identity")

**Mascot / illustration direction:** A friendly character (designer's call — bird, fox, ghost, etc.) used sparingly for empty states, onboarding, and celebration moments. Illustrations should be flat, rounded, expressive. No stock-photo realism.

---

## 3. Design system foundations

### Color
- **Light + dark themes, system-driven by default** (follow OS setting; user can override in settings)
- Vibrant accent palette: at least one hero color + one secondary + one celebratory (used for streaks/wins)
- Semantic tokens: `success`, `warning`, `danger`, `info`
- SRS rating colors: distinct hues for **Again / Hard / Good / Easy** (red → orange → green → blue, or designer's call — must be colorblind-safe)
- Contrast must meet WCAG AA on both themes, including the vibrant accents

### Typography
- **iOS:** SF Pro (system)
- **Android:** Roboto (system)
- Type scale: Display, H1, H2, H3, Body, Body-Small, Caption, Button
- Card content text uses a slightly larger, friendlier scale than chrome — cards are the hero

### Spacing & shape
- 4pt base grid
- **Generously rounded** corners (cards ~16–20pt radius, buttons fully pill-shaped where appropriate)
- Soft elevation / shadow system; avoid harsh borders

### Iconography
- Rounded, filled-or-duotone style (not thin line icons)
- Custom set preferred; SF Symbols / Material Symbols acceptable as fallback

### Motion
- Snappy, springy, never sluggish
- Card flip = the signature interaction; nail it
- Celebratory micro-animations on streak milestones, deck completion, correct answer streaks
- Respect "Reduce Motion" OS setting

---

## 4. Platform-native guidance

Echo uses **shared brand tokens** (colors, type scale, illustrations) but **platform-native component patterns**. The same screen should feel native on each OS.

| Concern | iOS | Android |
|---|---|---|
| System font | SF Pro | Roboto |
| Top navigation | Large title nav bar (HIG) | Top app bar (Material 3) |
| Bottom tabs | UITabBar style | Material NavigationBar |
| Modals | Sheets / page sheets | Bottom sheets / full-screen dialogs |
| Buttons | HIG filled / tinted / plain | Material filled / tonal / text |
| Lists | Inset grouped / plain | Material list items |
| Haptics | UIImpactFeedback (use generously on card flips, SRS taps, streak hits) | HapticFeedbackConstants equivalents |
| Back navigation | Swipe-from-edge + back chevron | System back gesture + up arrow |

Designers should produce **two flows per key screen** (iOS and Android) where the patterns meaningfully diverge. Where they don't (e.g., the study card itself), one shared design is fine.

---

## 5. Information architecture

Primary bottom navigation (4 tabs):

1. **Study** (default landing) — today's review queue
2. **Decks** — user's library of decks (own + saved)
3. **Discover** — browse by Pubky tags, see what friends published
4. **Profile** — own profile, friends, settings

---

## 6. Key screens to design

Each screen requires the following **states**: empty, loading, populated/success, error. Note loading and error states explicitly — don't only design the happy path.

### 6.1 Onboarding + Login with Pubky Ring
- Welcome carousel (2–3 slides) explaining: SRS that works, decks you make, friends you follow
- Single primary action: **"Sign in with Pubky Ring"**
- Plain-language explainer ("Pubky Ring is the keychain on your phone that signs you in. No email. No password. Your key, your account.") — designed for users who have never heard of Pubky or crypto
- Secondary action: "I don't have Pubky Ring" → link to install
- Loading state while waiting for the deeplink callback
- Error state if the user cancels in Ring

### 6.2 Home / Daily study queue
- **Front-and-center:** "X cards due today" with a giant primary "Start studying" CTA
- Below: streak counter, today's progress bar, decks contributing to today's queue
- Empty state: "You're all caught up. Add a deck or browse Discover."

### 6.3 Study session
- **Card front** → tap or swipe to reveal back
- **Card back** with the four SRS rating buttons: **Again / Hard / Good / Easy** (each shows the next interval, e.g., "10m / 1d / 3d / 7d")
- "Speak" button on text cards triggers **native TTS** (see §8)
- Progress indicator (X of Y due today)
- Pause / exit affordance
- Celebration moment when the queue empties

### 6.4 Deck detail
- Deck cover image, title, description, author (pubky identity)
- Tag chips (Pubky tags) — tappable to discover similar decks
- Card count, last studied, SRS stats
- Primary CTA: **Study this deck**
- Secondary: Edit (if owner), Share, Save/Bookmark, Follow author
- List or grid of cards inside the deck

### 6.5 Deck editor / card creation
- Edit deck metadata: title, description, cover image, **tags** (writes to Pubky tag primitive), **public/private** toggle
- Add card flow with three content modes:
  - Text front + text back
  - Image (front and/or back) + text
  - Recorded audio + text
- Image picker uses native OS picker
- Audio recorder uses native OS recording with waveform feedback
- Drag-to-reorder cards
- Bulk import (out of scope for v1 visuals but leave room)

### 6.6 Discover
- Top of screen: **tag-driven discovery** powered by Pubky tags (trending tags, followed tags, search)
- Below: decks from people the user follows
- Tag chips are the primary navigation primitive — not categories, not algorithms
- Empty state when the user follows nobody yet: "Follow a friend to see their decks here"

### 6.7 Friend / other-user profile
- Their pubky avatar, display name, bio (pulled from their Pubky homeserver profile)
- Truncated pubky identifier (copyable)
- Follow / Unfollow button
- **Primary content: a grid of their public decks with tags** — this is what the screen is for
- No activity feed, no stats wall in v1

### 6.8 Own profile + settings
- Same layout as friend profile (your public decks grid) at the top
- Settings entry point
- Settings includes: theme override, notifications, study reminders, **pubky identity & homeserver info**, sign out
- Sign out makes clear: "Your decks stay on your homeserver. Signing back in restores everything."

---

## 7. Component library

Design these reusable components with all states (default, hover/pressed, disabled, loading, selected):

- **Card** (study card — front, back, flip animation, swipe affordances)
- **Deck tile** (cover image, title, tag chips, card count, author avatar)
- **Tag chip** (selected, unselected, with count, removable in editor)
- **SRS rating button row** (Again / Hard / Good / Easy with interval labels)
- **Friend row** (avatar, name, truncated pubky, follow button)
- **Audio player** (waveform, play/pause, scrubber) — for recorded audio cards
- **Image picker tile** (empty + filled states)
- **Speak button** (TTS trigger, with active "speaking" state)
- **Empty state block** (illustration + headline + subhead + optional CTA)
- **Streak indicator** (number + flame or equivalent celebratory element)
- **Progress bar** (linear, for study session and daily goal)
- **Bottom sheet** (platform-appropriate)
- **Toast / snackbar** (success, error, info)

---

## 8. Card anatomy

Three card content variants must be designed:

1. **Text-only** — front: prompt text. Back: answer text. Both sides include a **Speak** button that triggers native OS text-to-speech: **iOS `AVSpeechSynthesizer`**, **Android `TextToSpeech`**. No third-party voices. A language/voice picker (in deck settings) is populated from voices the OS already has installed. When the user taps Speak, the button shows an active "speaking" state with subtle animation.

2. **Image + text** — image hero on the front (or back), text below. Image fills card width, maintains aspect ratio, rounded corners matching card.

3. **Recorded-audio + text** — separate from TTS. The deck author records audio while building the card (e.g., a native pronunciation, a music phrase). Plays via the audio player component. Text accompanies the audio.

All three variants share: card chrome (rounded container, shadow), flip animation, SRS rating row on the back.

---

## 9. Pubky-specific UX patterns

Echo's auth and social layer is built on **Pubky** — a decentralized identity and data system. The designer must internalize these patterns; they shape multiple screens.

### 9.1 Login flow (deeplink contract)
1. User taps **Sign in with Pubky Ring** in Echo
2. Echo opens a deeplink to the Pubky Ring app: `pubkyring://session?x-success=echo://login-callback`
3. Pubky Ring shows the user their list of pubkys (keys); user picks one and approves the requested capabilities
4. Pubky Ring signs in to the user's homeserver, then returns to Echo via: `echo://login-callback?pubky=<publickey>&session_secret=<secret>&capabilities=<list>`
5. Echo stores the session and lands the user on the Study tab

Design the screens for: pre-handoff (the Echo "signing in…" loading screen), the moment of return (success animation), and cancellation (user backed out of Ring).

### 9.2 Identity display
- Users have a **public key (pubky)**, not a username/email
- Display name, avatar, and bio come from the user's **profile on their homeserver** (editable by them)
- Show the pubky as a **truncated, copyable string** (e.g., `pk:abc123…xyz789`) on profile screens and in settings — never as the primary identifier in feeds
- Avatars, when set, are the primary visual identifier

### 9.3 Tags as a first-class primitive
**This is the most important Pubky pattern in Echo.** Pubky has a native tag primitive — tags are user-defined labels attached to any object. Echo decks are tagged via this primitive directly. Design implications:

- The Discover tab is **tag-driven**, not algorithm-driven
- Tag chips appear on every deck tile and deck detail
- Tag input in the deck editor reads from and writes to Pubky tags (designer should treat this like a familiar hashtag input, but understand it's not a custom Echo system)
- Tapping any tag anywhere in the app navigates to a tag-filtered Discover view

### 9.4 Follows as a first-class primitive
- Pubky has native follow relationships; Echo's friends list is derived from follows (not a custom social graph)
- "Follow" / "Unfollow" buttons on profile screens write to Pubky follows
- The Discover tab surfaces decks from people the user follows

### 9.5 Sharing a deck = publishing
- A "public" deck is **published to the user's Pubky homeserver** and discoverable by anyone
- A "private" deck stays on the user's homeserver but isn't surfaced in Discover or on their profile
- Make the public/private toggle prominent in the deck editor with a clear plain-language explanation of each state
- "Share" action surfaces the deck's Pubky URI and a shareable link

### 9.6 Self-custodial framing
- **There is no "forgot password" flow.** The Pubky Ring app holds the key. If users lose Ring without backup, they lose access.
- Onboarding must communicate this **without scaring non-crypto users**. Frame it positively: "Your account lives on your phone, not on a company's server. Back up your Ring to keep it safe."
- Settings should include a gentle reminder/CTA to back up Pubky Ring

---

## 10. Accessibility

- **Contrast:** WCAG AA on both light and dark themes, including all vibrant accent uses. Don't sacrifice contrast for the playful palette.
- **Dynamic type:** All text scales with the OS text-size setting. Card content especially must remain readable at the largest setting.
- **Screen readers:** Every interactive element has a VoiceOver (iOS) / TalkBack (Android) label. Card flip announces the new face. SRS buttons announce their interval.
- **Audio cards:** Provide a text caption alongside recorded audio for users who can't hear it.
- **Reduce Motion:** Respect the OS setting — replace card flip with a crossfade, disable celebratory animations.
- **Colorblind safety:** SRS rating buttons must not rely on color alone; pair with distinct shapes, icons, or labels.
- **Tap targets:** Minimum 44×44pt (iOS) / 48×48dp (Android).

---

## 11. Deliverables expected from the designer AI

1. **Figma file** organized by: Foundations · Components · iOS screens · Android screens · Prototypes
2. **Design tokens** as JSON (colors, type, spacing, radii, motion durations) — both light and dark
3. **Component specs** for every component in §7, with all states
4. **Screen designs** for all eight screens in §6, with all required states (empty, loading, success, error), in both iOS and Android variants where patterns diverge
5. **Card flip prototype** — interactive, showing the signature animation
6. **Onboarding + Pubky Ring login prototype** — the full flow including the deeplink handoff loading state
7. **Illustration set** for empty states and onboarding (mascot direction is the designer's call within the playful/vibrant brief)
8. **Iconography set** covering the components in §7

---

## 12. Out of scope for v1

Do not design these — they may come later but should not influence v1 architecture:

- Comments / replies on decks or cards
- Likes, reactions, or any engagement counters
- Leaderboards or competitive features
- Paid decks, marketplace, or any monetization
- Web app or desktop app
- AI-generated cards / decks
- Group study rooms or live multiplayer
- Notifications inbox (system push reminders are in scope; an in-app inbox is not)

---

*End of brief. Designer AI: produce a Figma-ready design system and screen set for Echo following everything above. Ask for clarification only if a specific decision is missing — do not invent product features beyond what is described here.*
