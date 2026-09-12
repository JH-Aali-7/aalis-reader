# Aali's Reader — AI Study Reader

An offline first Android reading app for students. It opens PDF, EPUB, TXT, PowerPoint and Word files,
lets you tap any word for an instant dictionary meaning, reads the book aloud while highlighting the
word being spoken, recognises text inside scanned pages without any internet, and can summarise a
chapter with AI when you are online.

Built for the **Pak Angels Generative & Agentic AI Hackathon (Cohort 11)**.

<p align="center">
  <a href="https://github.com/JH-Aali-7/aalis-reader/raw/main/release/AaliReader-v1.8-arm64.apk">
    <b>Download the APK</b>
  </a>
  &nbsp;·&nbsp;
  <a href="https://jh-aali-7.github.io/aalis-reader/">Project page</a>
  &nbsp;·&nbsp;
  <a href="docs/PRD.md">PRD</a>
</p>

---

## The problem

A science student in Pakistan reads most of their course material on a phone, from PDFs and scanned
notes shared on WhatsApp. Three things get in the way:

1. **Unknown words break reading.** Switching to a browser to look up *anhydrous* or *glomerulus*
   costs attention and mobile data, and often there is no data at all.
2. **Scanned notes are dead images.** A photographed page cannot be searched, selected, copied or
   read aloud, which also shuts out anyone who reads with their ears rather than their eyes.
3. **Study material lives in five formats.** Lecture slides are .pptx, assignments are .docx,
   textbooks are .pdf, novels are .epub. Most readers handle one of them.

## The solution

One reader that stays useful with the aeroplane mode on.

| Capability | How it works | Needs internet |
| --- | --- | --- |
| Tap a word for its meaning | 273,221 word dictionary bundled in the APK as SQLite | No |
| Deeper or rarer terms | Wiktionary and Wikipedia lookup as a second opinion | Yes |
| Read aloud with word tracking | Android TTS, the spoken word is highlighted and the page follows the voice | No |
| Scanned pages become text | ML Kit text recognition, model bundled in the APK | No |
| Chapter and book summary | Google Gemini, using your own free API key | Yes |
| Slides and documents in their real design | Custom OOXML renderer, not a text dump | No |

## Features

**Reading**

- PDF, EPUB, TXT, PPTX and DOCX in one library
- PowerPoint and Word open in their **original layout** — shape positions, theme colours and fonts,
  pictures and tables — scaled to fit and pinch zoomable exactly like a PDF page
- Continuous vertical PDF scrolling, night mode, go to page, side scroll bar
- Immersive mode: tap the middle of the page to hide the bars
- Auto scroll with real reading speeds (150 to 400 words per minute)
- Light, sepia, lavender and dark themes, font size, line height and margin controls
- Resume where you stopped, per book

**Understanding**

- Tap any word on any format, including on the PDF page itself, for an instant definition
- Select a phrase for a combined lookup, or drag the handles down to a single letter
- Difficult word lists, one per book plus a master list, saved for revision
- Per book search history
- AI summary of a chapter, a selection or the whole book, with equations converted from LaTeX
  into readable Unicode (V₂O₅, 1.06 F g⁻¹) instead of raw markup

**Listening**

- Read aloud the whole book, a chapter or just the selected passage
- The word being spoken is highlighted and the page scrolls with the voice, in portrait and in
  zoomed landscape
- Speed and voice follow the phone's text to speech settings

**Keeping**

- Highlights and notes saved to the database **and** written as plain .txt files with book name and
  page number into a visible `Aali Reader/Highlights` folder on phone storage
- Export highlights and notes to a formatted PDF
- Bookmarks, comments and notes anywhere in a book
- Reading statistics: minutes per day, a 7 day chart and a daily streak
- Full backup and restore as a single zip, so a new phone keeps everything

## Screens

| Library | Reader with dictionary | Read aloud | Statistics |
| --- | --- | --- | --- |
| Your books, covers and progress | Tap a word, meaning appears in place | Spoken word highlighted | Minutes per day and streak |

## Technology

| Layer | Choice |
| --- | --- |
| Language | Kotlin, minSdk 24 (Android 7), targetSdk 34 |
| UI | Material 3, view based, purple theme |
| PDF render | `com.github.mhiew:android-pdf-viewer` |
| PDF text geometry | `com.tom-roush:pdfbox-android`, custom `PDFTextStripper` subclass producing per character rectangles |
| Reflowable formats | WebView with an injected reading engine (`reader.js`, `reader.css`) |
| Office formats | `OfficeRenderer.kt`, a from scratch OOXML to absolutely positioned HTML renderer |
| Dictionary | SQLite, 282,942 definitions over 273,221 words, gzip compressed in assets |
| OCR | `com.google.mlkit:text-recognition`, bundled model, fully offline |
| Speech | Android `TextToSpeech` with `UtteranceProgressListener.onRangeStart` for word level tracking |
| AI | Google Gemini REST (`gemini-flash-latest`), key supplied by the user |
| Storage | SQLite for progress, highlights, notes, bookmarks, searches, vocabulary, OCR cache and reading time |
| Build | Gradle 8.7, AGP 8.5.2, ABI split APKs |

### How the parts fit together

```
                       ┌───────────────────┐
                       │  LibraryActivity  │  books, covers, progress, import
                       └─────────┬─────────┘
             ┌───────────────────┴───────────────────┐
             ▼                                       ▼
  ┌────────────────────┐                  ┌────────────────────────┐
  │  PdfReaderActivity │                  │   HtmlReaderActivity   │
  │  PDFView + overlay │                  │  WebView + reader.js   │
  └─────────┬──────────┘                  └───────────┬────────────┘
            │ per character rectangles                │ EPUB / TXT / PPTX / DOCX
            ▼                                         ▼
  ┌────────────────────┐                  ┌────────────────────────┐
  │  PdfTextExtractor  │                  │  EpubParser /          │
  │  PDFBox + OCR      │                  │  OfficeRenderer        │
  └─────────┬──────────┘                  └───────────┬────────────┘
            └───────────────┬─────────────────────────┘
                            ▼
     ┌──────────────────────────────────────────────────────┐
     │  Shared services                                     │
     │  DictionaryHelper (offline SQLite) · OnlineDictionary │
     │  TtsManager (word ranges) · OcrHelper (ML Kit)        │
     │  GeminiClient + TextFormat · Db · PdfExporter         │
     │  BackupManager · ReadingTimer                         │
     └──────────────────────────────────────────────────────┘
```

## Install

1. Download **[AaliReader-v1.8-arm64.apk](https://github.com/JH-Aali-7/aalis-reader/raw/main/release/AaliReader-v1.8-arm64.apk)** (49 MB).
2. On the phone, allow installing from unknown sources when asked.
3. Open the app, grant storage access, then copy any book into `Aali Reader/Books` or import from the
   library screen.
4. Optional: Settings → AI key, paste a free Google Gemini key from
   [aistudio.google.com](https://aistudio.google.com/app/apikey) to switch AI summaries on.

If the main APK refuses to install on an older or unusual phone, use the
[universal APK](https://github.com/JH-Aali-7/aalis-reader/raw/main/release/AaliReader-v1.8-universal.apk) instead.

## Build from source

```bash
git clone https://github.com/JH-Aali-7/aalis-reader.git
cd aalis-reader
# point local.properties at your Android SDK, for example
#   sdk.dir=C:\\Users\\you\\AppData\\Local\\Android\\Sdk
./gradlew assembleRelease
```

APKs land in `app/build/outputs/apk/release/`. Open the folder in Android Studio and press Run for
day to day work.

## Repository layout

```
app/src/main/java/com/aali/ebookreader/   all Kotlin sources
app/src/main/assets/dict.db.gz            offline dictionary, unpacked on first run
app/src/main/assets/reader.js|.css        the reading engine injected into the WebView
docs/PRD.md                               product requirements document
docs/index.html                           project page published with GitHub Pages
release/                                  signed APKs you can install directly
```

## Data and privacy

Books never leave the phone. The dictionary, speech and OCR all run on the device. Two things reach
the internet, and only when you ask for them: an online dictionary lookup, and an AI summary sent to
Google Gemini with your own key. There is no account, no analytics and no advertising.

## Licence

MIT. See [LICENSE](LICENSE).

Dictionary data comes from Princeton WordNet, Webster's 1913 Unabridged Dictionary (public domain),
the Gene Ontology and the Human Disease Ontology, each under its own permissive licence.
