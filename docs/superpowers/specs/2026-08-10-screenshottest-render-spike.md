# Spike — can the plugin render a `screenshotTest` composable?

**Status:** answered, 2026-08-10 · **Blocks:** F5 (reference vs. live diff), and through it F6

## The question

F5 wants a pixel diff between a composable's live render and its committed golden. The golden was produced by
running the `screenshotTest` function, whose body wraps the content in `PreviewComponent { PrimusTheme { … } }`;
the plugin renders the `main` source set's `@Preview`, which has no such wrapper. Diffing those two compares
different pictures — the wrapper alone changes every pixel. An honest diff needs the plugin to render the
**`screenshotTest` function itself**.

The roadmap flagged this as unverified: `screenshotTest` only enters the AGP model when
`-Pandroid.experimental.enableScreenshotTest=true` is set, and the reference project is synced without it.
Phase 14 sidestepped the question for *indexing* by reading the source set from the VFS, and deliberately left
*rendering* untouched.

> Can `RenderModelResolver` + `LiveRenderer` resolve and draw a `@PreviewTest` function living in
> `src/screenshotTest/kotlin/…`, in a project synced without the experimental flag?

## Method

`PreviewGalleryPanel.routeSelection` normally refuses to ask — it calls `pipeline.select(null)` for a snapshot
row, and `selectedSnapshotEntry`'s own KDoc says a snapshot "can never be mistaken for something renderable"
(PG13 spec D8). A snapshot row already *is* a `PreviewEntry` whose `file` points at the `screenshotTest` source,
so the probe was one throwaway call on that existing object: log the module attribution, then run
`LiveRenderer.render(snapshot)` and log the outcome. Reverted after the run; nothing shipped.

Three snapshot rows in `features/favorites/ui`, against `hepsi-android` in a `runIde` sandbox
(Android Studio Panda 4, 2025.3.4 Patch 1, `AI-253.32098.37.2534.15336583`).

## Answer

**No — but it fails on the last rung, and for one narrow reason.**

All three rows produced the same outcome:

```
outcome=FAILURE Inflating the preview failed: …EditListSnapshotsKt :: status=ERROR_INFLATION
```

Everything upstream of the class load works:

| Rung | Result |
|---|---|
| `ProjectFileIndex.getModuleForFile` | **passes** — attributed to `hepsi-android.features.favorites.ui`, without the flag |
| Android facet, `AndroidBuildTargetReference`, `AndroidFacetRenderModelModule` | passes — the resolver returned `Resolved`, not `NoFacet`/`Failed` |
| `Configuration`, preview element, `ComposeViewAdapter` XML | passes |
| layoutlib inflation, Compose composition start | passes — the trace reaches `Recomposer.composeInitial` |
| **loading the snapshot class** | **fails** |

The failure is a class load, and Android Studio says why in its own log:

```
ClassFileFinder for Module: 'hepsi-android.features.favorites.ui' holder module requested.
This is ambiguous. Falling back to the main module.
  at GradleBuildSystemFilePreviewServices$getRenderingServices$1.getClassFileFinder(…:127)
  at StudioModuleRenderContext.createInjectableClassLoaderLoader$lambda$0(…:29)
  at ProjectSystemClassLoader.getClassContentForFqcn(…:56)
  … ModuleClassLoaderImpl.loadClass → Class.forName
  at androidx.compose.ui.tooling.ComposableInvoker.invokeComposable(…:214)
```

`ComposeViewAdapter` reflectively asks for `…EditListSnapshotsKt`, the render classloader resolves against the
**main** module's output, and the class is not there.

**The class file exists.** It is on disk, compiled, in the module's own build directory:

```
features/favorites/ui/build/intermediates/built_in_kotlinc/debugScreenshotTest/
  compileDebugScreenshotTestKotlin/classes/com/hepsiburada/ui/feature/favorites/screen/editlist/EditListSnapshotsKt.class
```

So this is **not** "the code was never compiled" and **not** "the model cannot see the source". It is one
directory missing from the render classpath.

## What this changes

1. **The flag is less load-bearing than assumed.** `enableScreenshotTest` appears in neither `gradle.properties`
   nor `local.properties`, yet module attribution and model resolution both succeeded. Whatever the flag gates,
   it is not the part F5 was expected to trip over.
2. **The blocker is `ClassFileFinder`, not the project model.** AS resolves a "holder module", calls the choice
   ambiguous, and falls back to main. The fix shape is "point the render classloader at the
   `debugScreenshotTest` classes directory", not "get the source set into the model".
3. **`createInjectableClassLoaderLoader` is the name to chase.** It sits directly above the failing finder in the
   trace, and *injectable* is the whole question: whether a plugin may contribute a class source to a render.
   That is the next unknown, and it is AS-internal — same degrade-don't-break posture as every other
   AS-internal call in `render/`.
4. **A precondition F5 must state either way.** The classes exist here because the screenshot tests have been run
   locally. On a fresh clone they would not, and no classpath fix helps. This is mild — goldens come from the
   same Gradle task, so a project with goldens to diff against has usually built them — but F5's design must say
   what it does when the directory is absent, and must not present a stale class as a fresh render.

## Recommendation

Split F5, and ship the halves in order:

- **Reference tab** — load and display the committed golden beside the live `main` render. Depends on none of
  the above, and PG18's `ReferenceImageLocator` + `GoldenInspector` already resolve and decode exactly these
  files. Buildable today.
- **Diff tab** — gated on a second, much narrower spike: can a plugin inject the `debugScreenshotTest` classes
  directory into `StudioModuleRenderContext`'s classloader? If yes, F5 is whole. If no, the diff is only
  honest against a render the plugin cannot currently produce, and F5 stops at the reference tab.

Do not design the diff before that second spike answers. The first one moved the unknown; it did not remove it.
