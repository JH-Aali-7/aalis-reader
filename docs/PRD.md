# Product Requirements Document

**Product:** Aali's Reader — AI Study Reader
**Platform:** Android 7.0 and above
**Version covered:** 1.8 (hackathon build)
**Author:** Aali · Team [placeholder]
**Event:** Pak Angels Generative & Agentic AI Training, Cohort 11 — Hackathon
**Date:** September 2026
**Status:** Working build, submitted for judging

---

## 1. Summary

Aali's Reader is an offline first reading app for students who study from their phones. It opens the
five formats course material actually arrives in — PDF, EPUB, TXT, PPTX and DOCX — and adds the three
things a student needs on top of reading: instant word meanings without leaving the page, text
recognition that brings scanned notes back to life, and an AI summary for revision. Everything
essential works with no internet connection, because that is the condition most study happens in.

## 2. Problem statement

Students in Pakistan read course material on a mid range Android phone, often with no data left in
the month. Three specific failures make that painful.

**2.1 Vocabulary friction.** Science texts are dense with unfamiliar terms. Looking one up means
leaving the app, opening a browser, waiting for a page, and losing the thread. Existing readers
either have no dictionary or need a connection for it.

**2.2 Scanned material is unreadable by software.** A large share of shared notes are photographs of
pages. They cannot be searched, selected, copied or spoken. A student who prefers or needs to listen
is locked out entirely, and so is anyone revising by searching for a keyword.

**2.3 Format fragmentation.** Slides, assignments, textbooks and novels come as four different file
types. Most readers do one well. Converting a deck to text destroys the diagrams and layout that
carry the meaning.

**2.4 Constraints that shape the design.** Mid range hardware with 2 to 4 GB of RAM. Files up to
200 MB, including scanned books. Intermittent, metered connectivity. No budget for paid services.

## 3. Goals and non goals

### Goals

| # | Goal | Measure of success |
| --- | --- | --- |
| G1 | Every core reading task works offline | Dictionary, read aloud, OCR, all five formats function in aeroplane mode |
| G2 | A word meaning is one tap away | Under 300 ms from tap to definition for a bundled word |
| G3 | Scanned pages become usable text | OCR recognises a typical page in under 4 seconds on a mid range phone |
| G4 | Documents keep their original design | Slides and Word pages render with real positions, colours, fonts, images and tables |
| G5 | Nothing is lost when the phone changes | One zip restores books, progress, highlights, notes and settings |
| G6 | Runs on a 2 GB phone | A 200 MB scanned PDF opens without an out of memory crash |

### Non goals

- No user accounts, cloud sync service or social features. Backup is a file the user controls.
- No book store or content catalogue. The user brings their own files.
- No paid AI. The user supplies a free Gemini key, or skips AI entirely.
- iOS and desktop are out of scope for this version.

## 4. Users

**Primary — Bilal, 20, BS Chemistry.** Reads scanned lecture notes and textbook PDFs on a 3 GB phone,
usually on the bus with no data. Needs meanings of technical terms and a way to revise a chapter
quickly before a quiz.

**Secondary — Sana, 17, A levels.** Studies from slide decks her teacher shares. Wants the deck to
look like the deck, and to listen to notes while doing something else.

**Tertiary — Usman, 34, visually tiring work.** Reads long documents by ear. Needs read aloud that
tracks the spoken word so he can follow along and pick up where his attention drifted.

## 5. User stories

| ID | As a … | I want to … | So that … |
| --- | --- | --- | --- |
| US1 | student | tap an unknown word and see its meaning in place | I do not lose my place or spend data |
| US2 | student | look a rare term up online when I do have data | the offline dictionary is a floor, not a ceiling |
| US3 | student | hear the book read aloud with the current word highlighted | I can follow along and stay in place |
| US4 | student | have the page scroll itself while the voice reads | I do not touch the phone while listening |
| US5 | student | select text on a scanned page | photographed notes behave like real text |
| US6 | student | get an AI summary of a chapter | I can revise quickly before a test |
| US7 | student | open a PowerPoint deck and see the real slide | diagrams and layout survive |
| US8 | student | keep highlights as files with book name and page | I can revise from them outside the app |
| US9 | student | build a list of difficult words per book | I can revise vocabulary deliberately |
| US10 | student | see how long I read each day and keep a streak | I stay consistent |
| US11 | student | back up and restore everything | changing phones costs nothing |

## 6. Functional requirements

### 6.1 Library and files

- **FR1.1** Show all books from a visible phone folder (`Aali Reader/Books`) plus files imported
  through the system picker.
- **FR1.2** Support .pdf, .epub, .txt, .pptx and .docx.
- **FR1.3** Show cover, title and reading progress per book, and resume at the exact last position.
- **FR1.4** Request storage access at first run with a clear explanation and a direct route to the
  All files access screen on Android 11 and above.

### 6.2 Reading

- **FR2.1** PDF: continuous vertical scrolling, pinch zoom, go to page, side scroll bar, night mode.
- **FR2.2** EPUB and TXT: reflow to the chosen font size, line height and margin, with chapter
  navigation and a table of contents.
- **FR2.3** PPTX and DOCX: render each slide or page at its true size with original positions,
  theme colours, fonts, bullets, pictures and tables; scale to fit and allow pinch zoom. Speaker
  notes are shown below each slide.
- **FR2.4** Four themes: light, sepia, lavender, dark.
- **FR2.5** Auto scroll at a chosen reading speed between 150 and 400 words per minute.
- **FR2.6** Tapping the middle of the page hides the top and bottom bars.

### 6.3 Dictionary

- **FR3.1** A single tap on any word in any format, including on a rendered PDF page, opens its
  definition without leaving the page.
- **FR3.2** The bundled dictionary holds 282,942 definitions across 273,221 words, drawn from
  WordNet, Webster's 1913, a science glossary, the Gene Ontology and the Human Disease Ontology.
- **FR3.3** A multi word selection is looked up as a phrase first, then word by word.
- **FR3.4** Selection handles allow a single letter or a partial word.
- **FR3.5** When a connection exists, an online lookup (Wiktionary, then Wikipedia) supplements the
  offline result. Failure is silent and never blocks the offline answer.
- **FR3.6** Any looked up word can be saved to a per book list and to a master difficult words list.

### 6.4 Read aloud

- **FR4.1** Speak the whole book, the current chapter or the current selection.
- **FR4.2** Highlight the word being spoken, and the sentence containing it.
- **FR4.3** Scroll the page to keep the spoken word on screen, in portrait and in zoomed landscape.
- **FR4.4** Continue automatically into the next chapter or page.
- **FR4.5** Work with no connection, using the phone's own speech engine.

### 6.5 Offline text recognition

- **FR5.1** Detect pages with no embedded text and offer recognition.
- **FR5.2** Recognise with an on device model, no network call.
- **FR5.3** Produce word rectangles so recognised text can be tapped, selected, highlighted and read
  aloud like real text.
- **FR5.4** Cache the result so a page is recognised once.

### 6.6 AI summary

- **FR6.1** Summarise a selection, a chapter or a whole book using a user supplied Google Gemini key.
- **FR6.2** Convert LaTeX in the response into readable Unicode, so formulas display as V₂O₅ rather
  than raw markup.
- **FR6.3** Never block reading. Absence of a key or of a connection disables the feature with a
  clear message rather than an error.
- **FR6.4** Recover automatically when a model name is retired, by listing available models and
  retrying once.

### 6.7 Highlights, notes and export

- **FR7.1** Highlight any selection; store it in the database and append it to
  `Aali Reader/Highlights/<book>.txt` with the page number.
- **FR7.2** Attach a typed note or comment to any position.
- **FR7.3** Export all highlights and notes for a book as a formatted PDF.
- **FR7.4** Bookmarks and per book search history.

### 6.8 Statistics and backup

- **FR8.1** Record reading time per day; show a seven day chart, totals and a daily streak.
- **FR8.2** Export everything as one zip and import it on another device.

## 7. Non functional requirements

| ID | Requirement | Target |
| --- | --- | --- |
| NFR1 | Cold start | Under 2 seconds on a mid range phone |
| NFR2 | Dictionary lookup | Under 300 ms |
| NFR3 | Memory | A 200 MB PDF opens without exceeding the heap; parsing streams through temporary files, page caches are bounded |
| NFR4 | Install size | Under 55 MB for the per architecture APK |
| NFR5 | Offline | Every feature except online lookup and AI summary works with no connection |
| NFR6 | Privacy | No account, no analytics, no advertising; book content leaves the device only for an explicitly requested AI summary |
| NFR7 | Compatibility | Android 7.0 (API 24) and above, arm64 and arm32 |
| NFR8 | Resilience | A file that cannot be laid out falls back to a plain text view rather than failing to open |

## 8. Technical design

**How the code was produced.** The application was specified in plain language and generated with
**Claude Opus 5**, with an agent running the Gradle build on the developer's own machine and fixing
its own compile errors. Each of the nine builds was installed on a real phone and the failures fed
back as the next request. 35 Kotlin files, 8,521 lines, none typed by hand.

**Client only architecture.** There is no backend. The app is a single Kotlin Android application;
the only network calls are the optional dictionary and AI lookups, made directly from the device.

| Concern | Implementation |
| --- | --- |
| PDF display | AndroidPdfViewer (mhiew fork) |
| PDF text layer | PDFBox Android with a custom `PDFTextStripper` that emits a rectangle per character, so taps map to words and selection handles can reach a single letter |
| Large files | `MemoryUsageSetting.setupTempFileOnly()` plus bounded LRU caches and a large heap flag |
| Reflowable formats | WebView with an injected engine (`reader.js`, `reader.css`) handling word taps, selection, highlights and speech tracking |
| Office formats | `OfficeRenderer.kt` reads the OOXML drawing model — shape trees, EMU geometry, theme colours and fonts, placeholder inheritance from layout and master — and emits absolutely positioned HTML at the true page size |
| Dictionary | SQLite shipped gzipped in assets, unpacked once, indexed by word, with scientific plural morphology and source ranking |
| OCR | ML Kit text recognition with the bundled model; word boxes are converted into the same structure the PDF text layer produces, so every downstream feature works unchanged |
| Speech | `TextToSpeech` with `UtteranceProgressListener.onRangeStart` for word ranges, mapped back to on screen rectangles |
| Persistence | SQLite: progress, highlights, notes, bookmarks, searches, vocabulary, OCR cache, reading time |

## 9. Success metrics

| Metric | Target |
| --- | --- |
| Offline coverage | 100 percent of core features usable in aeroplane mode |
| Dictionary hit rate on science text | 90 percent of tapped terms resolved offline |
| Crash free sessions | 99 percent |
| Time to first meaning | Under one second from tap |
| Retention proxy | A user keeps a reading streak of five days or more in the first two weeks |

## 10. Risks

| Risk | Mitigation |
| --- | --- |
| Out of memory on very large scanned PDFs | Stream parsing to temporary files, bounded caches, trim memory callbacks, graceful catch of `Throwable` |
| Retired AI model names | Live model listing and one automatic retry |
| Unusual Office files | Fall back to the plain text reader instead of failing to open |
| Fonts missing on the device | Font stack falls back to the closest system face |
| APK size from the bundled OCR model | Per architecture APK splits, with a universal build as a fallback |

## 11. Roadmap

**Shipped in 1.8** — five formats, offline dictionary, word level read aloud, offline OCR, AI
summary, original design rendering for Office files, statistics and streaks, highlight and note
export, backup and restore.

**Next**

- Urdu and Arabic dictionary and text to speech
- Handwriting recognition for handwritten notes
- Flashcard generation from the difficult words list, with spaced repetition
- A question and answer mode over the current book
- Optional encrypted sync between the user's own devices

## 12. Team

| Name | Role | Contribution |
| --- | --- | --- |
| Aali | Team lead, Android development | Architecture, PDF text layer, Office renderer, dictionary pipeline |
| [Team member 2] | [Role] | [Contribution] |
| [Team member 3] | [Role] | [Contribution] |
| [Team member 4] | [Role] | [Contribution] |
| [Team member 5] | [Role] | [Contribution] |

## 13. Links

| Item | Link |
| --- | --- |
| Code | https://github.com/JH-Aali-7/aalis-reader |
| Download and project page | https://jh-aali-7.github.io/aalis-reader/ |
| APK download | https://github.com/JH-Aali-7/aalis-reader/raw/main/release/AaliReader-v1.8-arm64.apk |
| Presentation slides | [add link] |
| Presentation video | [add link] |
