Feature: Episodes storage layout — one directory per session under sessions/<crew>/, episodes nested inside (isaac-b6w0)
  The disk session store's representation of the store primitives:
    sessions/index.edn                       id → crew, session-policy, updated-at (derived, rebuildable)
    sessions/<crew>/recall/                  crew documents: recall index + vectors (not granted to the crew)
    sessions/<crew>/<sid>/session.edn        identity + overrides once, stamped :session-policy
    sessions/<crew>/<sid>/current.ednl …     chronicle: the transcript and its rotated segments
    sessions/<crew>/<sid>/episodes/<cid>/    one directory per episode: episode.edn (status, timestamps,
                                             parent-episode, scene-ids, transcript-level counters),
                                             current.ednl, scenes/
  Session ids are unique fleet-wide; the crew is immutable on the session. Episode and
  scene ids are yyyyMMddHHmmssSSS minted from the clock at creation (bump 1 ms on a
  collision in the same parent); pre-existing ids are kept as-is by migration.
  Decisions (2026-09-09, Micah): crew-nested layout; sessions index; recall index under
  the crew's sessions dir; counter split (session.edn = across episodes, episode.edn =
  one transcript); migrate-layout moves everything and stamps the policy.

  Background:
    Given default Grover setup

  Scenario: a cold open on an episodes crew creates the session directory once and the first episode beneath it
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When a charge is dispatched with:
      | key         | value          |
      | session-key | lantern-room   |
      | crew        | cordelia       |
      | input       | Light the lamp |
    Then the isaac file "sessions/cordelia/lantern-room/session.edn" EDN contains:
      | path           | value        |
      | id             | lantern-room |
      | crew           | cordelia     |
      | session-policy | :episodes     |
    And the directory "sessions/cordelia/lantern-room/episodes" has exactly 1 file
    And an episode exists for crew "cordelia" matching:
      | key        | value        |
      | id         | #"\d{17}"    |
      | status     | open         |
      | session-id | lantern-room |
    And the isaac file "sessions/index.edn" EDN contains:
      | path                        | value    |
      | lantern-room.crew           | cordelia |
      | lantern-room.session-policy | :episodes |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Light the lamp     |
      | message | assistant    | Charted, keep west |

  Scenario: a successor episode after compaction is a sibling under the same session; the closed episode keeps its final counters
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 100        |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | local            |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following sessions exist:
      | name         | crew     | last-input-tokens |
      | lantern-room | cordelia | 85                |
    And session "lantern-room" has transcript:
      | type    | message.role | message.content  |
      | message | user         | old message one  |
      | message | assistant    | old response one |
      | message | user         | old message two  |
      | message | assistant    | old response two |
    And the following model responses are queued:
      | type | content               | model      |
      | text | Full summary of prior | test-model |
      | text | 1-4: Prior voyage     | gist       |
      | text | New response          | test-model |
    When the user sends "new input" on session "lantern-room"
    Then the directory "sessions/cordelia/lantern-room/episodes" has exactly 2 files
    And crew "cordelia" has 2 episodes
    And an episode exists for crew "cordelia" matching:
      | key               | value        |
      | status            | closed       |
      | session-id        | lantern-room |
      | last-input-tokens | 85           |
    And an episode exists for crew "cordelia" matching:
      | key            | value        |
      | status         | open         |
      | session-id     | lantern-room |
      | parent-episode | #"\d{17}"    |
    And the isaac file "sessions/cordelia/lantern-room/session.edn" EDN contains:
      | path           | value        |
      | id             | lantern-room |
      | crew           | cordelia     |
      | session-policy | :episodes     |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | new input       |
      | message | assistant    | New response    |

  Scenario: a session-level pin set once applies to every episode of that session
    Given the isaac EDN file "config/models/alpha.edn" exists with:
      | path     | value   |
      | model    | alpha-1 |
      | provider | grover  |
    And the isaac EDN file "config/models/beta.edn" exists with:
      | path     | value  |
      | model    | beta-1 |
      | provider | grover |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | alpha            |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the isaac EDN file "config/isaac.edn" exists with:
      | path                 | value |
      | episodes.ttl-minutes | 5     |
    And the following sessions exist:
      | name         | crew     | model |
      | lantern-room | cordelia | beta  |
    And the following model responses are queued:
      | type | content | model  |
      | text | one     | beta-1 |
      | text | two     | beta-1 |
    And the current time is "2026-03-01T10:00:00"
    When the user sends "first" on session "lantern-room"
    Then the last chat request on session "lantern-room" used model "beta-1"
    Given the current time is "2026-03-01T10:30:00"
    When the user sends "second" on session "lantern-room"
    Then the last chat request on session "lantern-room" used model "beta-1"
    And crew "cordelia" has 2 episodes
    And the isaac file "sessions/cordelia/lantern-room/session.edn" EDN contains:
      | path  | value |
      | model | beta  |

  Scenario: a chronicle session carries the policy stamp and the listing shows the policy column for both kinds
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
      | text | Lit     | echo  |
    When the user sends "Status?" on session "harbor-log"
    And the user sends "Light the lamp" on session "lantern-room" as crew "cordelia"
    Then the isaac file "sessions/main/harbor-log/session.edn" EDN contains:
      | path           | value      |
      | id             | harbor-log |
      | crew           | main       |
      | session-policy | :chronicle  |
    And the isaac file "sessions/main/harbor-log/current.ednl" exists
    And the isaac file "sessions/index.edn" EDN contains:
      | path                        | value     |
      | harbor-log.session-policy   | :chronicle |
      | lantern-room.session-policy | :episodes  |
    When isaac is run with "sessions list"
    Then the stdout matches:
      | pattern                                                                  |
      | SESSION .* AGE .* SIZE .* USED .* WINDOW .* PCT .* CREW .* POLICY          |
      | harbor-log\s+\S+\s+\S+\s+[\d,]+\s+[\d,]+\s+\d+%\s+main\s+chronicle         |
      | lantern-room\s+\S+\s+\S+\s+[\d,]+\s+[\d,]+\s+\d+%\s+cordelia\s+episodes    |
    And the exit code is 0

  Scenario: a session is found by id alone through the sessions index, and an id that exists under another crew is refused
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When the user sends "Light the lamp" on session "lantern-room" as crew "cordelia"
    And isaac is run with "sessions show lantern-room"
    Then the stdout contains "Crew"
    And the stdout contains "cordelia"
    And the exit code is 0
    When isaac is run with "prompt --crew main --session lantern-room --create always -m 'hello'"
    Then the stderr contains "session lantern-room belongs to crew cordelia"
    And the exit code is 1
    And the isaac file "sessions/main/lantern-room/session.edn" does not exist

  Scenario: the sessions index is derived — a directory the index does not know is found by scan and the index repaired
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | echo             |
      | soul  | You are Cordelia |
    And the isaac EDN file "sessions/cordelia/lantern-room/session.edn" exists with:
      | path           | value        |
      | id             | lantern-room |
      | name           | Lantern Room |
      | crew           | cordelia     |
      | session-policy | :chronicle    |
    And the isaac EDN file "sessions/index.edn" exists with:
      | path            | value |
      | harbor-log.crew | main  |
    When isaac is run with "sessions show lantern-room"
    Then the stdout contains "cordelia"
    And the exit code is 0
    And the isaac file "sessions/index.edn" EDN contains:
      | path                        | value     |
      | harbor-log.crew             | main      |
      | lantern-room.crew           | cordelia  |
      | lantern-room.session-policy | :chronicle |

  Scenario: the recall index locates a scene by session id, episode id, and scene id
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And crew "cordelia" has a closed episode "20260301100000000" on session "lantern-room" with scenes:
      | id                | started-at          | ended-at            | gist | text  |
      | 20260301100005000 | 2026-03-01T10:00:05 | 2026-03-01T10:05:00 | wine | pinot |
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "2 new rows"
    And the exit code is 0
    And the isaac file "sessions/cordelia/recall/index.edn" exists
    And the index for crew "cordelia" has rows:
      | session-id   | episode-id        | scene-id          | kind | model      |
      | lantern-room | 20260301100000000 | 20260301100005000 | gist | mini-embed |
      | lantern-room | 20260301100000000 | 20260301100005000 | text | mini-embed |
    And the isaac file "sessions/cordelia/lantern-room/episodes/20260301100000000/scenes/20260301100005000.md" exists
    And the crew "cordelia" allows tools: "recall/*"
    And the current session is "lantern-room"
    When the tool "recall__scene" is called with:
      | scene_id | 20260301100005000 |
    Then the tool result is not an error
    And the tool result contains "pinot"

  Scenario: migrate-layout moves every session under its crew, folds a legacy episode into its session, rebuilds both indexes, and is a no-op the second time
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the isaac EDN file "sessions/harbor-log/session.edn" exists with:
      | path | value      |
      | id   | harbor-log |
      | name | Harbor Log |
      | crew | main       |
    And the isaac file "sessions/harbor-log/current.ednl" exists with:
      """
      {:type "message" :id "m1" :timestamp "2026-03-01T09:00:00" :message {:role "user" :content "Status?"}}
      """
    And the isaac EDN file "sessions/2026-03-01-1000-ab12/session.edn" exists with:
      | path | value                |
      | id   | 2026-03-01-1000-ab12 |
      | name | Lantern Room         |
      | crew | cordelia             |
    And the isaac file "sessions/2026-03-01-1000-ab12/current.ednl" exists with:
      """
      {:type "message" :id "m2" :timestamp "2026-03-01T10:00:00" :message {:role "user" :content "pinot"}}
      """
    And the isaac EDN file "episodes/cordelia/2026-03-01-1000-ab12/episode.edn" exists with:
      | path      | value                  |
      | id        | 2026-03-01-1000-ab12   |
      | crew      | cordelia               |
      | status    | closed                 |
      | thread    | lantern-room           |
      | scene-ids | [2026-03-01-1000-s1x1] |
    And the isaac file "episodes/cordelia/2026-03-01-1000-ab12/2026-03-01-1000-s1x1.md" exists with:
      """
      ---
      id: 2026-03-01-1000-s1x1
      started-at: 2026-03-01T10:00:00
      ended-at: 2026-03-01T10:05:00
      gist: wine
      ---
      pinot
      """
    When isaac is run with "episodes migrate-layout --dry-run"
    Then the stdout matches:
      | pattern                                                                                                       |
      | sessions/harbor-log -> sessions/main/harbor-log \(chronicle\)                                                  |
      | sessions/2026-03-01-1000-ab12 -> sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12                 |
      | episodes/cordelia/2026-03-01-1000-ab12 -> sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12        |
      | dry run: 0 moved                                                                                               |
    And the exit code is 0
    And the isaac file "sessions/harbor-log/session.edn" exists
    When isaac is run with "episodes migrate-layout"
    Then the exit code is 0
    And the isaac file "sessions/main/harbor-log/session.edn" EDN contains:
      | path           | value     |
      | crew           | main      |
      | session-policy | :chronicle |
    And the isaac file "sessions/main/harbor-log/current.ednl" exists
    And the isaac file "sessions/cordelia/lantern-room/session.edn" EDN contains:
      | path           | value        |
      | id             | lantern-room |
      | crew           | cordelia     |
      | session-policy | :episodes     |
    And the isaac file "sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12/episode.edn" EDN contains:
      | path       | value        |
      | status     | closed       |
      | session-id | lantern-room |
    And the isaac file "sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12/current.ednl" exists
    And the isaac file "sessions/cordelia/lantern-room/episodes/2026-03-01-1000-ab12/scenes/2026-03-01-1000-s1x1.md" exists
    And the isaac file "sessions/index.edn" EDN contains:
      | path                        | value    |
      | harbor-log.crew             | main     |
      | lantern-room.crew           | cordelia |
      | lantern-room.session-policy | :episodes |
    And the index for crew "cordelia" has rows:
      | session-id   | episode-id           | scene-id             | kind |
      | lantern-room | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | gist |
      | lantern-room | 2026-03-01-1000-ab12 | 2026-03-01-1000-s1x1 | text |
    When isaac is run with "episodes migrate-layout"
    Then the stdout contains "nothing to migrate"
    And the exit code is 0

  Scenario: migrate-layout stamps a post-mmod thread session as episodes when its episodes nest under it
    After isaac-mmod an episode's backing transcript already lives under the
    stable session id (a Discord channel, an ACP session), so the flat layout
    holds sessions/<sid>/ plus episodes/<crew>/<eid>/ pointing at it. That
    session is owned by the episodes policy: session.edn and the index must
    both say so, while a plain flat session stays chronicle.
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the isaac EDN file "sessions/discord-c999/session.edn" exists with:
      | path | value        |
      | id   | discord-c999 |
      | name | discord-c999 |
      | crew | cordelia     |
    And the isaac file "sessions/discord-c999/current.ednl" exists with:
      """
      {:type "message" :id "m1" :timestamp "2026-03-02T11:00:00" :message {:role "user" :content "Light the lamp"}}
      """
    And the isaac EDN file "sessions/harbor-log/session.edn" exists with:
      | path | value      |
      | id   | harbor-log |
      | name | Harbor Log |
      | crew | main       |
    And the isaac file "sessions/harbor-log/current.ednl" exists with:
      """
      {:type "message" :id "m2" :timestamp "2026-03-02T09:00:00" :message {:role "user" :content "Status?"}}
      """
    And the isaac EDN file "episodes/cordelia/2026-03-02-1100-cd34/episode.edn" exists with:
      | path       | value                  |
      | id         | 2026-03-02-1100-cd34   |
      | crew       | cordelia               |
      | status     | closed                 |
      | session-id | discord-c999           |
      | thread     | discord-c999           |
      | scene-ids  | [2026-03-02-1100-s2y2] |
    And the isaac file "episodes/cordelia/2026-03-02-1100-cd34/2026-03-02-1100-s2y2.md" exists with:
      """
      ---
      id: 2026-03-02-1100-s2y2
      started-at: 2026-03-02T11:00:00
      ended-at: 2026-03-02T11:05:00
      gist: lamp
      ---
      Light the lamp
      """
    When isaac is run with "episodes migrate-layout --dry-run"
    Then the stdout matches:
      | pattern                                                                                          |
      | sessions/discord-c999 -> sessions/cordelia/discord-c999 \(episodes\)                             |
      | sessions/harbor-log -> sessions/main/harbor-log \(chronicle\)                                    |
      | episodes/cordelia/2026-03-02-1100-cd34 -> sessions/cordelia/discord-c999/episodes/2026-03-02-1100-cd34 |
    And the exit code is 0
    When isaac is run with "episodes migrate-layout"
    Then the exit code is 0
    And the isaac file "sessions/cordelia/discord-c999/session.edn" EDN contains:
      | path           | value        |
      | id             | discord-c999 |
      | crew           | cordelia     |
      | session-policy | :episodes     |
    And the isaac file "sessions/cordelia/discord-c999/current.ednl" exists
    And the isaac file "sessions/cordelia/discord-c999/episodes/2026-03-02-1100-cd34/episode.edn" EDN contains:
      | path       | value        |
      | status     | closed       |
      | session-id | discord-c999 |
    And the isaac file "sessions/cordelia/discord-c999/episodes/2026-03-02-1100-cd34/scenes/2026-03-02-1100-s2y2.md" exists
    And the isaac file "sessions/main/harbor-log/session.edn" EDN contains:
      | path           | value     |
      | session-policy | :chronicle |
    And the isaac file "sessions/index.edn" EDN contains:
      | path                        | value     |
      | discord-c999.session-policy | :episodes  |
      | harbor-log.session-policy   | :chronicle |

  Scenario: migrate-layout carries a crew's recall vectors into sessions/<crew>/recall/ and removes the legacy index
    The pre-b6w0 index lived at episodes/<crew>/{index.edn,vectors.json}.
    Re-embedding at migration would need the provider and change every
    vector; the rows are carried over as they are, re-keyed with the
    session id, and the legacy files are removed. (The emptied episodes/
    directories go too on a real filesystem; the in-memory test fs cannot
    delete a directory, so only the files are asserted here.)
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes         |
    And the isaac EDN file "sessions/discord-c999/session.edn" exists with:
      | path | value        |
      | id   | discord-c999 |
      | name | discord-c999 |
      | crew | cordelia     |
    And the isaac file "sessions/discord-c999/current.ednl" exists with:
      """
      {:type "message" :id "m1" :timestamp "2026-03-02T11:00:00" :message {:role "user" :content "Light the lamp"}}
      """
    And the isaac EDN file "episodes/cordelia/2026-03-02-1100-cd34/episode.edn" exists with:
      | path       | value                  |
      | id         | 2026-03-02-1100-cd34   |
      | crew       | cordelia               |
      | status     | closed                 |
      | session-id | discord-c999           |
      | thread     | discord-c999           |
      | scene-ids  | [2026-03-02-1100-s2y2] |
    And the isaac file "episodes/cordelia/2026-03-02-1100-cd34/2026-03-02-1100-s2y2.md" exists with:
      """
      ---
      id: 2026-03-02-1100-s2y2
      started-at: 2026-03-02T11:00:00
      ended-at: 2026-03-02T11:05:00
      gist: lamp
      ---
      Light the lamp
      """
    And the isaac file "episodes/cordelia/index.edn" exists with:
      """
      {:dims 3 :model "mini-embed" :scale 10000 :rows [{:episode-id "2026-03-02-1100-cd34" :scene-id "2026-03-02-1100-s2y2" :kind :gist :model "mini-embed"} {:episode-id "2026-03-02-1100-cd34" :scene-id "2026-03-02-1100-s2y2" :kind :text :model "mini-embed"}]}
      """
    And the isaac file "episodes/cordelia/vectors.json" exists with:
      """
      [[10000,0,0],[0,10000,0]]
      """
    When isaac is run with "episodes migrate-layout"
    Then the exit code is 0
    And the isaac file "sessions/cordelia/recall/index.edn" EDN contains:
      | path  | value      |
      | dims  | 3          |
      | model | mini-embed |
    And the isaac file "sessions/cordelia/recall/vectors.json" exists
    And the index for crew "cordelia" has rows:
      | session-id   | episode-id           | scene-id             | kind |
      | discord-c999 | 2026-03-02-1100-cd34 | 2026-03-02-1100-s2y2 | gist |
      | discord-c999 | 2026-03-02-1100-cd34 | 2026-03-02-1100-s2y2 | text |
    And the isaac file "episodes/cordelia/index.edn" does not exist
    And the isaac file "episodes/cordelia/vectors.json" does not exist

