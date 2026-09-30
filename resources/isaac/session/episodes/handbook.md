# isaac.session.episodes — episodes and recall

You are a crew running inside Isaac. This chapter covers what
**isaac-episodes** owns: the `:episodes` session policy, the episode
containers a crew's conversation lives in, sealing conversation into
recallable scenes, and recall (search, the recall tools, and recall-at-open
injection). Foundation's own chapter (`handbook__read` topic
`isaac.foundation`) covers config mechanics, the vocabulary table, and hot
reload — read it first if you haven't. Session and crew mechanics that
episodes builds on (session policies, session naming, tool grants) belong to
`isaac.agent`; this chapter names them once and moves on.

A crew's conversation runs under a **session policy**. Most crews use the
default (`chronicle`, plain append-only sessions); a crew with
`session-policy` set to `episodes` gets its conversation split into
**episodes** — closed, timestamped containers whose content is distilled
into **scenes** and made searchable through **recall**. The inbound session
id (a comm's canonical per-space id, or `--session <name>`) is always the
**session id**; it never changes. What changes underneath it is which
episode is currently open.

## Episodes

**What it is.** An episode is a container: one open episode per session-id
at a time, holding a backing session (the actual message transcript) plus a
record (`episode.edn`) of status, lineage, and sealed scene ids. The first
message on a session opens an episode. From there:

- **Warm** — the next message arrives within `:episodes :ttl-minutes`
  (default 60 `[verify: default lives in code]`) of the episode's last
  entry: it appends to the same open episode, no recall injected.
- **Cold** — the next message arrives after that window: the open episode
  closes (sealing its whole unsealed tail into scenes) and a **successor**
  episode opens on the same session-id, linked by `:parent-episode`. This is
  a **chain**, not a new session — `isaac episodes list` shows the whole
  chain under one session-id, oldest first.
- **Compaction** — when the agent module compacts a session's context, the
  episodes policy closes the current episode against the pre-compaction
  transcript and opens a successor seeded with the compaction summary, in
  the same turn. A cold session never compacts; on a cold open the successor
  starts with an **empty transcript** except for recall — the compaction
  check that used to run against yesterday's already-closed episode was
  itself the bug (isaac-1vx0).
- **Explicit close** — `isaac episodes close --crew <crew>` seals every open
  episode for a crew right now, independent of TTL.
- **Housekeeping** — a scheduled worker (inside the server process only)
  ticks every 30s and TTL-closes cold episodes automatically; see Sealing
  scenes, below, for what else that tick does. An open episode with no
  unsealed content (only a compaction marker, nothing sealable) is
  **deleted**, not closed — an episode with no content has no recall value
  (isaac-9tjo).

Episode ids are 17-digit timestamps (`yyyyMMddHHmmssSSS`), minted from the
clock at creation — never from a message timestamp. Session ids are named
the same way any session is named (`isaac.agent`'s naming strategy: the
caller's `--session name`, or adjective-noun / sequential when none is
given); episodes never mints a session id of its own.

**How to change it.** Turn a crew into an episodes crew:

```
config set crew.cordelia.session-policy episodes
```

Tune its lifecycle:

```
config set episodes.ttl-minutes 90
config set crew.cordelia.episodes.ttl-minutes 20
config set episodes.gist-model gist
```

`:episodes` config lives at the top level (every episodes crew) and can be
overridden per crew at `crew.<id>.episodes` — the crew's own settings merge
over the global ones, key by key. `:episodes :gist-model` names the model id
used to gist scenes on close/seal; unset, it falls back to `:defaults :crew
:model`. Sealing (an LLM call) cannot proceed without a resolved gist model
or provider — a close/seal with neither fails loudly rather than silently
skipping.

**How to verify.** `isaac episodes list --crew <crew>` prints every episode
for a crew (id, status, session-id, scene count), oldest chain member first.
`handbook__read` topic `config:episodes.ttl-minutes` shows the live value
plus its schema. `isaac episodes close --crew <crew>` reports how many
episodes it closed (and indexed, if embedding is configured).

### Troubleshooting

- **A short, quiet conversation never shows up for other threads.** Recall
  only sees *sealed, indexed* scenes; an episode that stays open indefinitely
  (never closes, never live-seals) stays invisible. See Sealing scenes,
  below — this is what idle sealing exists to fix.
- **An episode with real content got deleted instead of closed.** Deletion
  only happens for episodes with *nothing sealable* in the transcript (a
  compaction marker with no messages after it). A deleted episode plus its
  backing session are gone entirely — this is intentional cleanup, not data
  loss of real conversation.
- **Compaction seems to run again on every turn (`:session/compaction-started`
  repeating).** The episodes policy's compaction path measures progress
  against the *successor* episode it just opened, not the one it closed — if
  you see repeated compaction attempts with no progress, that's the bug
  isaac-jom5 fixed; report it rather than assuming the model is stuck.
- **A crew's first checkpoint or tool call throws `AbstractMethodError`.**
  The episodes policy must implement every `isaac.agent.session.policy/SessionPolicy`
  method the agent module's protocol declares (isaac-rmbz added
  `append-checkpoint!`); see foundation's chapter, Runtime → the
  babashka/JVM protocol trap, for why this fails on the JVM specifically and
  not always on babashka.
- **`open-session!` throws "session name required".** A blank or nil session
  name is refused outright, not silently defaulted — episodes never
  synthesizes a session id from the episode clock.

## Sealing scenes into memory

**What it is.** Sealing is what turns raw transcript into recallable
material: an LLM pass segments a run of messages into **scenes** — a start
id, end id, and one-sentence **gist** — and writes each as an immutable
markdown file (frontmatter + distilled text) under the episode. A scene
marked routine (segmentation model prefixes the gist with `~`, or the whole
slice is tool-marker/empty) is procedural noise — running tests, loading
skills, processing a webhook stream — and is skipped by indexing, so it
never surfaces in recall.

Scenes seal three ways:

- **On close** (TTL cold, compaction, or explicit `episodes close`) — the
  whole unsealed tail is segmented and sealed.
- **Live, mid-episode**, on any of three triggers, checked on every worker
  tick against each crew's open episodes:
  - **idle** — the episode has an unsealed tail and its last message is
    older than `:episodes :seal :idle-minutes` (default 3
    `[verify: default lives in code]`); the *whole* tail seals and the
    episode stays open and warm. This is what makes a short conversation
    that goes quiet recallable within minutes instead of staying invisible
    until it eventually goes cold (isaac-q34y).
  - **size-cap** — the unsealed tail reaches `:episodes :seal :size-cap`
    messages (default 80 `[verify: default lives in code]`); segments and
    seals, leaving the newest scene open unless there's only one.
  - **drift** — a rolling embedding of the open scene's recent exchanges
    drops below `:episodes :seal :drift-threshold` cosine similarity
    against the new exchange (only checked once the tail is at least
    `:episodes :seal :min-tail` messages); this seals a finished topic well
    under the size cap. Both `drift-threshold` and `min-tail` are unset by
    default, so drift sealing is off unless you configure both. Drift needs
    `:episodes :embedding` configured — without it, drift is inert and only
    idle/size-cap sealing fire.
- **A resumed topic** — segmentation can mark a later scene as `(cont
  <earlier>)`, which resolves to a `:continues` link to the earlier scene id
  at seal time, so returning to a topic doesn't fragment its history.

Sealing indexes the fresh scenes immediately when `:episodes :embedding` is
configured (a quiet no-op otherwise — see Recall, below, for catching up
later with `isaac episodes index`).

**How to change it.**

```
config set episodes.seal.idle-minutes 5
config set episodes.seal.size-cap 120
config set episodes.seal.drift-threshold 0.82
config set episodes.seal.min-tail 4
```

**How to verify.** After a tick, `isaac episodes list --crew <crew>` shows
scene counts; `isaac logs server` (or `cli` for an ACP process) has
`:episodes/live-sealed` (trigger + count), `:episodes/closed` /
`:episodes/deleted` (TTL sweep), and `:episodes/tick` (one summary line per
tick: episodes examined, sealed, closed, transcript reads). A seal that
never got a segmentation response logs `:episodes/seal-failed`; the sweep
that finds nothing to do is silent on repeat ticks (an unchanged episode is
not re-read).

### Troubleshooting

- **Idle sealing never fires.** It only runs from the episodes *worker*,
  which is a server-process component (`:isaac/component`) on a 30s tick —
  a plain CLI command never seals on idle. Confirm the server is running.
- **The gist model keeps failing and I only see one warning, not one per
  tick.** Repeated seal failures on the same episode report as a *streak*
  (`:episodes/seal-failed` with `:consecutive`, logged at power-of-two
  counts: 1, 2, 4, 8…) instead of once per 30s tick — this is deliberate
  noise control, not a dropped failure. If `:attention` is configured
  (`isaac.agent`'s attention seam), a seal failure on the gist provider also
  posts an attention notice through that seam.
- **A resumed conversation's scenes look duplicated instead of linked.** A
  `(cont ...)` mark that can't resolve to a sealed scene in the same batch
  (its target is still open, or unknown) is dropped with a
  `:episodes/cont-dropped` warning rather than silently producing a bad
  link — check that log line for which case it was.
- **Drift sealing "fires" with no embedding configured.** It can't — drift
  needs a rolling vector from `:episodes :embedding`; without it the size
  cap is the only live trigger besides idle.
- **A `--session` name reused after a long gap starts a brand-new episode
  with no memory of the old one in its own transcript.** That's correct: a
  cold reopen is a **chain**, not a resume. The old content isn't lost — the
  new episode's first turn gets it back through recall-at-open (see Recall,
  below), and `isaac episodes list` still shows the lineage.

## Recall

**What it is.** Recall is how a crew gets earlier material back: a hybrid
search over a crew's sealed, indexed scenes, blending four channels — cosine
similarity against a scene's full text, cosine against its gist, IDF-weighted
lexical term overlap, and recency decay — into one score. Recall shows up in
two forms:

- **Recall-at-open** — when an episode opens or chains (cold open,
  compaction), episodes runs the opening message as a search query, plus
  (on a chain) seeds the parent episode's own scene gists as **lineage**.
  Matching results are held and prefixed onto that same message before it
  reaches the model, framed as `[Recalled memory; not a request]` so a
  quoted prior request is never mistaken for the current one (isaac-8l2u).
  A top-tier hit or two comes with its full excerpt; the rest come as gist
  lines only, each citable by id.
- **The recall tools** — `recall__search` (free-text query) and
  `recall__scene` (fetch one scene's full text by id) are crew tools the
  model can call mid-turn. Every episodes crew is granted both automatically
  — whether or not its `tools.allow` names them — unless its `tools.deny`
  names `:recall/*` (deny still wins). A `chronicle` (non-episodes) crew
  gets them only if its allow list says so explicitly.

**How to change it.**

```
config set episodes.recall.half-life 45
config set episodes.recall.weights.text 1.2
config set episodes.recall.weights.recency 0.3
config set episodes.recall.inject.full 2
config set episodes.recall.inject.gists 3
config set episodes.embedding.floor-cos 0.5
```

`:episodes :recall :half-life` (days, default 30
`[verify: default lives in code]`) controls how fast the recency channel
decays. `:episodes :recall :weights` (`:text`/`:gist`/`:lex`/`:recency`, each
defaulting to 1.0/1.0/1.0/0.5 `[verify: defaults live in code]`) sets how the
four channels blend. `:episodes :recall :inject` (`:full`/`:gists`, default
1/2 `[verify: defaults live in code]`) sets how many top search hits get
full-text vs. gist-only treatment on recall-at-open — the CLI (`isaac
recall`) always shows every requested hit regardless of the inject split,
since that split only governs what gets prefixed onto a live turn. The match
floor (`:episodes :embedding :floor-cos`, default 0.47
`[verify: default lives in code]`) gates admission: a hit below the floor is
still admitted if its lexical overlap crosses a fixed rare-term threshold, so
an exact-term match isn't lost to a weak embedding score. Setting the floor
to `0` disables the floor entirely.

**How to verify.** `isaac recall <query> --crew <crew>` ranks a crew's index
against a query from the CLI, printing each channel's contribution
(`text`/`gist`/`lex`/`rec`), matched terms, and timing/index-size stats — the
fastest way to check a weight or floor change without running a live turn.
Every recall-at-open decision logs `:episodes/recalled` (or
`:episodes/recall-empty` when nothing was injected) with the crew, thread,
lineage/search counts, best score, and floor — read `isaac logs server` (or
`cli`) rather than guessing whether a turn remembered anything (isaac-80vq).
The recall tool itself logs `:recall/search` / `:recall/scene` on each call.

### Troubleshooting

- **Recall injects nothing even though a clearly relevant scene exists.**
  Check `:episodes/recall-empty`'s `best` score against the active floor —
  a `floor-cos` set too high (or an embedding model too dissimilar from the
  scene's) will suppress a real match. `isaac recall <query> --crew <crew>`
  shows the same channels outside a live turn.
- **`isaac recall` says "no index for crew ... — run isaac episodes index"
  or "no rows for model ... — run isaac episodes index".** Recall is
  read-only against a prebuilt index; it never embeds on the fly. Run `isaac
  episodes index --crew <crew>` (add `--rebuild` after switching embedding
  models — old rows for a stale model are kept, not silently reused, so a
  model switch is loud rather than quietly wrong).
- **A crew's recall tools show up even though `tools.allow` never named
  them, or don't show up despite an explicit allow.** The grant is part of
  choosing `session-policy episodes`, independent of `tools.allow` — the
  only way to suppress it is `tools.deny [:recall/*]`.
- **Recalled memory bleeds into a new scene's gist.** The recall block is
  framed as context, not conversation, specifically so segmentation
  distills only what the episode itself said and did — a gist describing
  "remembering" something is a sign this framing broke, not a model quirk.
- **Half-life or a weight rejects with a coercion error on `config set`.**
  These are typed (`half-life`/weights: numeric); a crew-level override
  reports as `crew.<id>.episodes.recall.<field>` in the error, same schema
  as the global one.

## Embedding

**What it is.** Embedding is an **optional** capability recall and drift
sealing build on: text in, a vector out, dispatched by `:episodes :embedding
:api` to whichever API is registered against the
`:isaac.session.episodes/embedding-api` berth. Three ship today:

| `:api` | Notes |
|---|---|
| `grover` | Built-in deterministic test stub — never call it in a real deployment. |
| `ollama` | `POST {base-url}/api/embed`; `base-url` defaults to `http://localhost:11434`. |
| `embeddings` | OpenAI-compatible `POST {base-url}/embeddings`; `base-url` defaults to `https://api.openai.com/v1`, `api-key` sent as a bearer token. |

Absence of `:episodes :embedding` is a **legal configuration**, not an
error — episodes and recall-by-text still work; embedding-backed cosine
scoring, drift sealing, and `isaac episodes index` simply have nothing to
embed with (indexing reports "no embedding configured" rather than
silently no-opping).

**How to change it.**

```
config set episodes.embedding.api ollama
config set episodes.embedding.model nomic-embed-text
config set episodes.embedding.base-url http://localhost:11434
config set episodes.embedding.api-key ${EMBEDDINGS_API_KEY}
config set episodes.embedding.timeout 30000
```

An unregistered `:api` value is refused at config-validate time, not
silently ignored at request time.

**How to verify.** `isaac embed <text...>` embeds each argument directly
against the configured API and prints one vector per argument — the
fastest way to confirm credentials/base-url without running a turn.

### Troubleshooting

- **`isaac embed` (or an indexing/recall path) says "no embedding
  configured".** That's the optional-capability message, not a fault —
  set `:episodes :embedding {:api ... :model ...}` if you want
  embedding-backed recall.
- **`config validate` rejects `episodes.embedding.api` with "bad value" /
  "must be a registered contribution".** The `:api` value isn't registered
  against `:isaac.session.episodes/embedding-api` — check spelling, or that
  the module contributing it is installed.
- **An `api-key` shows up unresolved.** Same as any `${VAR}` secret
  (foundation's chapter, Config → Secrets): the environment variable isn't
  set where Isaac can see it.

## Recall ledger

**What it is.** An opt-in, per-crew research log: with `:episodes :recall
:ledger` true, every recall decision — recall-at-open or a `recall__search`
call — appends one EDN line to `sessions/<crew>/recall/ledger.ednl`: the
query, every candidate scene above the admission floor (score, gist), and
which scene ids were actually injected. It's independent of the regular
operational logs and off by default; turn it on only when you need to
audit recall quality, not as a standing feature.

**How to change it.**

```
config set episodes.recall.ledger true
```

**How to verify.** Tail `sessions/<crew>/recall/ledger.ednl` directly (it's
plain EDN lines, not a `handbook__read` topic or a CLI command of its own).

### Troubleshooting

- **The ledger file never appears.** Confirm `:episodes :recall :ledger` is
  `true` for the crew in question (it can be set globally or per crew, same
  merge rule as the rest of `:episodes`) — a write failure on the ledger
  itself is warned once (`:recall.ledger/write-failed`) and never blocks the
  turn, so a missing file after that is a permissions/disk issue on
  `sessions/<crew>/recall/`, not a silent recall failure.

## Operating episodes from the CLI

Three commands are hosted here:

- `isaac episodes migrate-session <session-id>` — materialize an *existing*
  plain session (one that predates the episodes policy, or a chronicle crew
  you want to backfill) as a closed episode: segments its whole transcript
  into scenes without touching the original session. Re-running is a no-op
  ("already migrated"); `--force` re-segments and replaces scenes in place.
  A partial run (some spans flagged as unparseable segmentation output)
  reports which spans and resumes them on the next run without re-touching
  already-sealed spans.
- `isaac episodes migrate-layout` — a one-time, idempotent structural
  migration folding older flat `sessions/<sid>/` and `episodes/<crew>/<eid>/`
  layouts into the current nested `sessions/<crew>/<sid>/episodes/<eid>/`
  tree; `--dry-run` prints the plan without moving anything. You should not
  need this on a fresh install.
- `isaac episodes close [--crew <crew>]` / `isaac episodes list [--crew
  <crew>]` / `isaac episodes index [--crew <crew>] [--rebuild]` — covered
  under Episodes, Sealing scenes, and Recall above respectively.

Two more commands round out the module: `isaac recall <query> [--crew
<crew>]` (Recall, above) and `isaac embed <text...>` (Embedding, above).

### Troubleshooting

- **`isaac episodes migrate-session <id>` fails with "unknown session".**
  The session id has to already exist in the registered session store —
  this command materializes an episode from an existing session's
  transcript; it does not create sessions.
- **`isaac episodes migrate-session` aborts immediately on a provider
  error, no retry, no flagged span.** That's deliberate: a segmentation
  *parse* failure gets one retry and a flagged span for later resumption; a
  *provider/auth* error aborts the whole run instead, since retrying an
  auth failure wastes calls for no chance of success.
- **`isaac episodes migrate-layout` reports "nothing to migrate" on every
  run.** That's the expected steady state once the fold is done — it's
  idempotent and safe to run again, but only does work when it finds a
  leftover flat layout.
