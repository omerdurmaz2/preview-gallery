# Spike — can the plugin put `debugScreenshotTest` classes on the render classpath?

**Status:** answered, 2026-08-10 · **Blocks:** F5's diff half, and through it F6
**Follows:** [2026-08-10-screenshottest-render-spike.md](2026-08-10-screenshottest-render-spike.md)

## The question

The first spike established that rendering a `@PreviewTest` composable fails on the last rung: module
attribution, facet, build target, configuration and layoutlib inflation all succeed, and the composition then
cannot load the snapshot class, because AS's `ClassFileFinder` calls the module's holder ambiguous and falls back
to main. The compiled class is on disk. One directory is missing from the render classpath.

> Can a plugin inject the `debugScreenshotTest` classes directory into `StudioModuleRenderContext`'s classloader?

## Answer

**Yes. The seam is the plugin's, and the whole chain composes out of public API — nothing has to be
reimplemented.**

Two halves, one verified at runtime and one by inspecting the shipped jars.

### The seam is on the render path (runtime)

`RenderModelResolver` builds an `AndroidFacetRenderModelModule` and hands it to `LiveRenderer` inside
`Resolved`. Wrapping that module in a delegate that overrides `getClassLoaderProvider` and logging the call
showed the render does ask **our** object for its class loader:

```
PG-SPIKE2 getClassLoaderProvider(private=false)
  -> com.android.tools.idea.rendering.AndroidFacetRenderModelModule$$Lambda/0x…
```

`RenderModelModule` is an interface, `AndroidFacetRenderModelModule` merely implements it, and the plugin already
constructs that instance. So a plugin-supplied `ClassLoaderProvider` is used, not bypassed.

### Every piece below it is public (`javap` against the shipped jars)

| Declaration | Shape | Why it matters |
|---|---|---|
| `StudioModuleClassLoaderManager.getPrivate(parent, StudioModuleRenderContext, ClassTransform, ClassTransform)` | public, returns `ModuleClassLoaderManager.Reference<StudioModuleClassLoader>` | Builds the loader for us, and **takes a render context** — the injection point is a parameter |
| `StudioModuleRenderContext` | **open** class, `protected` constructor taking a `BuildTargetReference`, plus static `forBuildTargetReference` / `forModule` | Subclassable |
| `StudioModuleRenderContext.createInjectableClassLoaderLoader()` | **open**, returns `ProjectSystemClassLoader` | The override point — the same method the first spike's stack trace named |
| `ProjectSystemClassLoader(Function1<String, ClassContent>)` | public constructor | Class resolution as a lambda: `fqcn → bytes` |
| `ProjectSystemClassLoader.injectClassFile(String, ClassContent)` | public | A second, more direct route |
| `ClassContent.loadFromFile(File)` | public static | Reads a `.class` straight off disk |
| `ModuleClassLoaderManager.Reference(T, Function1)` | public constructor | The return wrapper, if one is ever needed by hand |

The composition that follows:

1. Subclass `StudioModuleRenderContext`, overriding `createInjectableClassLoaderLoader()` to return a
   `ProjectSystemClassLoader` whose lambda looks in the module's
   `build/intermediates/built_in_kotlinc/debugScreenshotTest/compileDebugScreenshotTestKotlin/classes/` first
   (`ClassContent.loadFromFile`) and delegates to the real loader otherwise.
2. Have the `RenderModelModule` delegate's `getClassLoaderProvider` call
   `StudioModuleClassLoaderManager.getPrivate(parent, thatContext, projectTransform, nonProjectTransform)`.
3. Return the `Reference` it already produces.

**`ModuleClassLoader` is abstract with about ten abstract members, and none of them has to be implemented** — the
manager builds a `StudioModuleClassLoader` itself. That is the difference between this being a compose and a
rewrite, and it is what moves F5's diff half from L to M.

### What is NOT proven

This spike proved the seam is reachable and every piece is public. It did **not** run the composed chain. Three
things remain genuinely unknown until the implementation runs:

- whether `ProjectSystemClassLoader`'s lambda is consulted for *this* class, or whether an earlier loader in the
  `MultiLoaderWithAffinity` chain answers first;
- whether the parent loader and the two `ClassTransform`s the provider receives can be passed through unchanged;
- whether `getPrivate` (rather than the `getShared` the render asks for — the log shows `private=false`) is the
  right choice. It probably is: sharing a loader that carries `screenshotTest` classes with ordinary `@Preview`
  renders would leak test classes into the shared cache.

Treat the first render of a `screenshotTest` composable as the real proof, not this document.

## What this changes for F5

The diff half is no longer gated on an unanswered AS-internal question — it is gated on ordinary work with a
known shape. Its own design still has to answer:

- **Cache hygiene.** A private loader per screenshotTest render, released properly; `Reference` is `Closeable`
  and `StudioModuleClassLoaderManager.release` exists.
- **Staleness.** The classes directory only exists once the module's screenshot tests have been compiled, and it
  goes stale the moment the source changes. A diff against a stale render is worse than no diff, so the design
  must decide what it shows when the directory is absent or older than the source.
- **Degrade-don't-break.** Same posture as every other AS-internal call in `render/`: guarded against `Exception`
  and `LinkageError`, falling back to today's behaviour rather than throwing out of the render.

## Method

Two throwaway edits, both reverted: a `RenderModelModule` delegate in `RenderModelResolver` that logs
`getClassLoaderProvider`, and the `PreviewGalleryPanel` probe from the first spike that drives a snapshot row
through the render path. Three snapshot rows in `features/favorites/ui`, against `hepsi-android` in a `runIde`
sandbox (Android Studio Panda 4, 2025.3.4 Patch 1, `AI-253.32098.37.2534.15336583`). The API table came from
`javap` against `Android Studio.app/Contents/plugins/android/lib/*.jar`.
