---
title: Localise a string or add a language
description: Add or change a user-visible string in English and German, format dates and counts correctly, teach quick add a new keyword, and add a whole new language.
sidebar:
  order: 4
---

Primico ships in English and German, and no user-visible string lives in Kotlin. This guide
covers the four jobs that come up: adding a string, using it correctly, adding a quick-add
keyword, and adding a language.

## Prerequisites

- A working build (see the [quickstart](../tutorials/quickstart.md)).
- For quick-add changes: a phone or emulator for the one instrumented test.

## Add or change a string

Every string the screens show lives in `:ui`'s Compose Multiplatform resources:

- English: [`ui/src/commonMain/composeResources/values/strings.xml`](../../ui/src/commonMain/composeResources/values/strings.xml)
- German: [`ui/src/commonMain/composeResources/values-de/strings.xml`](../../ui/src/commonMain/composeResources/values-de/strings.xml)

1. **Add the entry to both files under the same name.** The English file is the fallback, so a key
   missing from German silently shows English, and nothing fails. The PR checklist asks for both.

   ```xml
   <!-- values/strings.xml -->
   <string name="snackbar_task_title_empty">A task needs a title</string>

   <!-- values-de/strings.xml -->
   <string name="snackbar_task_title_empty">Eine Aufgabe braucht einen Titel</string>
   ```

   Arguments are positional (`%1$s`, `%1$d`), as in Android resources. German quotation marks
   are the typographic `„…“`, as in the existing entries.

2. **Put counts in `<plurals>`, even where English and German happen to agree:**

   ```xml
   <plurals name="task_count">
       <item quantity="one">%1$d task</item>
       <item quantity="other">%1$d tasks</item>
   </plurals>
   ```

3. **Build once.** The `Res` accessors are generated (`generateResClass = Always` in
   [`ui/build.gradle.kts`](../../ui/build.gradle.kts)), one top-level property per string, in the
   package `de.andi1984.cadence.ui.resources`. Screens import them with a star import rather
   than one line per key:

   ```kotlin
   import de.andi1984.cadence.ui.resources.*
   import org.jetbrains.compose.resources.pluralStringResource
   import org.jetbrains.compose.resources.stringResource

   Text(stringResource(Res.string.tags_title))
   Text(pluralStringResource(Res.plurals.projects_overdue_count, overdue, overdue))
   ```

   These are `org.jetbrains.compose.resources` functions, **not** `androidx.compose.ui.res`.
   The same call has to work on the desktop.

4. **Outside a composable, pass the resource, not the text.** The ViewModel can't resolve
   strings, so it hands over a `StringResource`, for example
   `showSnackbar(Res.string.snackbar_error, listOf(result.message))`. The snackbar composable
   resolves it. `SnackbarMessage.Counted` does the same for plurals.

:::note
`:app-android` keeps a few strings of its own in `app-android/src/main/res/values{,-de}/strings.xml`:
the launcher label, the notification channel and reminder texts, and the home-screen widgets.
Those are read by Android itself or by Glance, never by a `:ui` composable. A new widget or
notification string goes there, in both languages.
:::

## Format dates, times and labels correctly

- **Use `currentLocale()`** from
  [`ui/format/DateLabels.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/format/DateLabels.kt),
  never `Locale.getDefault()`. The app's language can differ from the system's (Android 13+ has a
  per-app picker), and `currentLocale()` reads `LocalAppLocale`, which the shell provides through
  `CadenceTheme`.
- **Formatters live in `ui/format/` and are `@Composable`**, because both the wording and the
  date patterns (`date_pattern_*`) come from resources.
- **The domain layer produces no prose.** `RecurrenceEngine.summarize()` returns a structured
  `RecurrenceSummary` that
  [`ui/format/RecurrenceLabels.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/format/RecurrenceLabels.kt)
  turns into words. `Priority` carries only `shortLabel` (`P2`); its spoken name lives in
  [`PriorityLabels.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/format/PriorityLabels.kt).

:::caution[Never branch on a formatted string]
An early bug compared a day header to `"Tomorrow"`, which broke the moment the app ran in German.
Compare the underlying `LocalDate`, enum or id, and format only at the very end.
:::

## Add a quick-add keyword

Quick add's vocabulary is data, not grammar. It lives in
[`QuickAddLexicon`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/parse/QuickAddLexicon.kt),
and the parser builds its patterns from whichever lexicon it's handed.

1. Find the right field on `englishWords` or `germanWords`: `tomorrow`, `every`, `units`,
   `afterCompletion`, `clock` and so on. Each field is documented in the class.
2. Add the word **in lower case**, with every inflected form spelled out (`tag`, `tage`, `tagen`),
   plus an umlaut-free spelling if people type one (`taeglich` next to `täglich`).
3. Don't list weekday or month names. They come from `java.time` for the locale.
4. Add a case to
   [`QuickAddParserTest`](../../core/src/jvmSharedTest/kotlin/de/andi1984/cadence/QuickAddParserTest.kt),
   parsing with `QuickAddLexicon.German` or `QuickAddLexicon.English` as the existing cases do.
5. Run the instrumented test on a device (see below).

Lexicons compose, and `forLocale` always folds English in, so `every 2 weeks` keeps working in a
German install.

## Add a whole language

Say you're adding French (`fr`).

1. **Screen strings:** copy `values/strings.xml` to
   `ui/src/commonMain/composeResources/values-fr/strings.xml` and translate every entry,
   `<plurals>` included. French has different plural rules, so give each plural the quantities
   French needs.
2. **Android-only strings:** add `app-android/src/main/res/values-fr/strings.xml` for the
   launcher, notification and widget strings.
3. **Per-app language picker:** add the locale to
   [`app-android/src/main/res/xml/locales_config.xml`](../../app-android/src/main/res/xml/locales_config.xml):

   ```xml
   <locale android:name="fr" />
   ```

4. **Quick-add lexicon:** in `QuickAddLexicon`'s companion, add a `frenchWords` lexicon (the
   structure words only: `demain`, `chaque`, `jours`, …) and a public
   `val French: QuickAddLexicon = English + frenchWords + namesOf(Locale.FRENCH)`. Then extend
   `forLocale`'s `when (locale.language)` with a `Locale.FRENCH.language -> French` branch.
   Without a lexicon, an unlisted language still reads its own weekday and month names (from
   `java.time`) plus every English keyword. Adding a language means adding a lexicon, never
   touching `QuickAddParser` or `QuickAddPatterns`.
5. **Tests:** parser cases for the new words in `QuickAddParserTest`, and make sure the locale is
   in `everyShippedLexiconCompilesUnderIcu` in
   [`QuickAddPatternsDeviceTest`](../../app-android/src/androidTest/java/de/andi1984/cadence/QuickAddPatternsDeviceTest.kt).
   French is already in that list, and any other language needs adding.

:::note
The desktop has no language setting yet. It passes `Locale.getDefault()` to `CadenceTheme`, so a
desktop user sees the new language when their OS runs in it. The explicit desktop setting from
[ADR 0001](../adr/0001-desktop-app-and-multi-device-sync.md) (decision 9) is still outstanding.
:::

## The regex trap: ICU on the device, `java.util.regex` in the tests

Android compiles regexes with **ICU**, and the unit tests use **`java.util.regex`**. They disagree
in two ways that pass every JVM test and then crash or misbehave on a phone:

- **ICU rejects the `(?u)` and `(?U)` inline flags** outright, so the quick-add sheet throws on
  the first keystroke. [`QuickAddPatterns`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/parse/QuickAddPatterns.kt)
  therefore spells word boundaries as `\p{L}` lookarounds (`START`/`END`), not `\b`.
- **`IGNORE_CASE` folds only ASCII on the JVM**, so `Übermorgen` would parse on the phone but
  not in a test. Every non-ASCII letter in a keyword is written as a two-case class. The
  `literal()` helper does that for every lexicon word, so words you add to a lexicon are safe
  automatically. The rule bites when you hand-write a pattern.

Don't put `\b` or an inline flag back into `QuickAddPatterns`. After any change to it or to a
lexicon, run the one test that uses the device's engine:

```bash
./gradlew connectedDebugAndroidTest
```

## Verify

- The CI test command passes (see [run the tests](run-tests.md)).
- Switch the phone's app language (Android 13+: system settings → Apps → Primico → Language) and
  check the new strings, plurals and dates on screen.
- Type a line using the new keywords into quick add on the device and check the chips.

## Related

- [Quick-add reference](../reference/quick-add.md): every token the parser understands.
- [Tasks, projects and tags](../concepts/tasks-projects-tags.md): why `@tag` creates and `#project`
  only selects.
- [CONTRIBUTING.md](../../CONTRIBUTING.md): the PR checklist item for German strings.
