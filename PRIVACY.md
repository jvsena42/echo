# Loopky Privacy Policy

**Last updated:** 23 August 2026

Loopky is a flashcards app built on [Pubky](https://pubky.org). This policy describes what happens
to your information when you use it.

The short version: **Loopky has no servers.** There is no Loopky account, no Loopky database, and no
analytics. Your data is written to a homeserver that you control, using an identity that lives in a
separate app.

---

## 1. Who is responsible

Loopky is an open-source project by João Victor Sena. The source is at
[github.com/jvsena42/loopky](https://github.com/jvsena42/loopky).

Questions about this policy: **jvsena16@gmail.com**

---

## 2. What Loopky does not do

- **No backend.** The project operates no server that receives your data.
- **No analytics or telemetry.** No usage tracking, no event logging, no advertising identifiers,
  no third-party SDKs for measurement or attribution.
- **No crash reporting.** Nothing is transmitted when the app fails.
- **No advertising**, and no sale or sharing of personal information for advertising.
- **No account with Loopky.** There is no email address, password, or profile held by us.

---

## 3. Your identity

Your identity is a Pubky keypair held by **[Pubky Ring](https://pubky.app)**, a separate app. Loopky
never sees your private key or your recovery phrase.

When you sign in, Ring grants Loopky a **session secret** scoped to Loopky's own storage area. That
secret is stored on your device in the Android Keystore (`EncryptedSharedPreferences`) and is sent
only to your homeserver, to authorise your own reads and writes. Signing out deletes it.

Deleting your Loopky data does not delete your Pubky identity. That is managed in Pubky Ring.

---

## 4. Where your content is stored

Your decks, cards, images, tags, follows and study progress are written to **your own Pubky
homeserver** under `/pub/loopky/`. They are not stored by Loopky.

**Published decks are public.** Loopky has no private or local-only decks. Anything you publish can
be read by anyone who knows your public key, and is indexed so that it can be discovered by search
and by topic. Please do not put anything in a deck that you would not put on a public web page.

Your review history and study settings are also written to your homeserver, so that they follow you
to another device. They live under your own key and are not published to any index by Loopky, but
they are subject to whatever access rules your homeserver applies.

Some information is kept only on your device and is never uploaded: your study counters for the
current day, unsent reviews waiting to be written, and your app preferences.

---

## 5. Services Loopky contacts

Loopky talks to a small number of services. Each is contacted only for the purpose listed, and each
has its own operator and its own privacy practices.

| Service | When | What reaches it |
|---|---|---|
| **Your Pubky homeserver** | Whenever you read or write your content | Your content, your public key, your session secret, your IP address |
| **Pubky auth relay** (`httprelay.pubky.app`) | Sign-in only | The encrypted approval handed back by Pubky Ring, your IP address |
| **Pubky Nexus indexer** (`nexus.pubky.app`) | Search, discovery, trending topics, profile pictures | Your search terms, the public keys and decks you look up, your IP address |
| **Homegate** (`homegate.pubky.app`) | Signing up for a new homeserver account only | Your phone number if you verify by SMS, or Lightning payment details if you pay. Your phone number is handled by Homegate and is never stored by Loopky |
| **Unsplash** (`api.unsplash.com`) | Only when you search for a picture from the web | Your search terms and your IP address, under [Unsplash's privacy policy](https://unsplash.com/privacy) |
| **Google Play** | Only if you tap to install Pubky Ring | Standard Play Store request |

Loopky does not contact any of these to report on you. It contacts them to do the thing you asked
for.

---

## 6. Microphone

The **Speak** practice mode records audio so you can practise pronunciation.

- Audio is passed to your device's own speech recognition service and converted to text there.
- Loopky does **not** store the recording, and does **not** upload it anywhere.
- Only the recognised text is used, only to grade the card you are answering, and it is discarded
  when you move on.
- The permission is requested the first time you use Speak, and Speak is optional. Declining it
  leaves the rest of the app working normally.

Depending on your device settings, your device's speech recognition may itself run in the cloud
rather than on-device. That processing is governed by your device manufacturer's or Google's
privacy policy, not this one.

---

## 7. Photos and images

Choosing a picture for a card uses the Android system photo picker, which hands Loopky only the one
image you select. Loopky requests no storage or gallery permission.

Images you attach to a published deck are uploaded to your homeserver and are public along with the
deck.

---

## 8. Content from other people

Decks, profiles and tags published by other people are fetched from their homeservers and from the
Nexus indexer. Loopky does not verify or moderate them. Treat text and links inside someone else's
deck the way you would treat any content from a stranger on the internet.

---

## 9. Deleting your data

- **Sign out** (Settings) removes the session secret and your locally cached data from the device.
- **Delete a deck** removes its records, cards and images from your homeserver.
- **Delete account** (Settings) permanently removes everything Loopky has written to your
  homeserver, and removes your profile from Loopky's directory so that you no longer appear in
  search or discovery. It cannot be undone.

Delete account does not delete your Pubky identity or your homeserver account, because Loopky does
not own them. Those live in Pubky Ring, and your profile and social follows in the wider Pubky
network are left untouched.

Copies that other people made of a deck you published are theirs, on their own homeservers, and
cannot be recalled. This is a property of publishing to a public network.

---

## 10. Children

Loopky is not directed at children under 13, and we do not knowingly collect information from them.
Because Loopky holds no user data at all, there is nothing for us to delete on request. An account
is created in Pubky Ring, whose own terms apply.

---

## 11. Changes to this policy

Material changes will be published here with an updated date above. The revision history of this
document is public in the repository, so every change is inspectable.

---

## 12. Your rights

Because Loopky holds no personal data, requests to access, correct, export or delete data cannot be
served by us. Your content is already in your possession, on a homeserver you control, and Delete
account removes it. If you have a question about any of this, write to **jvsena16@gmail.com**.
