# 🍏 Isaac Episodes 🎞️

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-episodes/main/isaac-episodes.png" alt="isaac-episodes" style="margin-right: 20px; margin-bottom: 10px;">

Episodic memory for [Isaac](https://github.com/slagyr/isaac): session policy, scene
distillation, embedding, indexing, recall tools, and operator CLIs.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Contributes the `:episodes`
component, session policy, `recall/search` and `recall/scene` tools, and the
`embed`, `episodes`, and `recall` commands.

<br>

[![Episodes](https://github.com/slagyr/isaac-episodes/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-episodes/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- Session policy that seals idle turns into scenes and gists.
- Distill, store, migrate, and index episodic layout on disk.
- Embedding APIs (Ollama, embeddings, grover) and recall scoring/injection.
- Crew tools `recall/search` and `recall/scene`.
- CLIs: `isaac embed`, `isaac episodes`, `isaac recall`.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-http/
  isaac-episodes/   # this repo
```

```sh
bb spec       # unit specs
bb features   # acceptance features
bb ci         # specs + features
```

From the JVM:

```sh
clj -M:spec
clj -M:features
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-episodes {:local/root "../isaac-episodes"}
;; or {:git/url "https://github.com/slagyr/isaac-episodes.git" :git/sha "..."}
```
