Feature: Episodes storage layout — one directory per session under sessions/<crew>/, episodes nested inside (isaac-b6w0)
  The disk session store's representation of the store primitives:
    sessions/index.edn                       id → crew, updated-at (derived, rebuildable)
    sessions/<crew>/recall/                  crew documents: recall index + vectors (not granted to the crew)
    sessions/<crew>/<sid>/session.edn        identity + overrides once
    sessions/<crew>/<sid>/current.ednl …     chronicle: the transcript and its rotated segments
    sessions/<crew>/<sid>/episodes/<cid>/    one directory per episode: episode.edn (status, timestamps,
                                             parent-episode, scene-ids, transcript-level counters),
                                             scenes/ (ranges into the session transcript)
  Session ids are unique fleet-wide; the crew is immutable on the session. Episode and
  scene ids are yyyyMMddHHmmssSSS minted from the clock at creation (bump 1 ms on a
  collision in the same parent).
  Decisions (2026-09-09, Micah): crew-nested layout; sessions index; recall index under
  the crew's sessions dir; counter split (session.edn = across episodes, episode.edn =
  one transcript).
  Decision (2026-10-05, Micah): sessions carry no policy stamp (isaac-c52a/ka10);
  `sessions list` shows each session's context mode; migrate-layout is deleted
  (legacy awareness, its one-time migration is done).

  Background:
    Given default Grover setup

  @wip
  Scenario: a cold open on an episodes crew creates the session directory once and the first episode beneath it
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | context-mode   | episodes         |
      | observers      | [:episodes]      |
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
    And the directory "sessions/cordelia/lantern-room/episodes" has exactly 1 file
    And an episode exists for crew "cordelia" matching:
      | key        | value        |
      | id         | #"\d{17}"    |
      | status     | open         |
      | session-id | lantern-room |
    And the isaac file "sessions/index.edn" EDN contains:
      | path                        | value    |
      | lantern-room.crew           | cordelia |
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Light the lamp     |
      | message | assistant    | Charted, keep west |

  @wip
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
      | context-mode   | episodes         |
      | observers      | [:episodes]      |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:gist-model :gist}}
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
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | new input       |
      | message | assistant    | New response    |

  @wip
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
      | context-mode   | episodes         |
      | observers      | [:episodes]      |
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

  @wip
  Scenario: the listing shows each session's context mode
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | context-mode | episodes         |
      | observers    | [:episodes]      |
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
      | text | Lit     | echo  |
    When the user sends "Status?" on session "harbor-log" as crew "main"
    And the user sends "Light the lamp" on session "lantern-room" as crew "cordelia"
    Then the isaac file "sessions/main/harbor-log/session.edn" EDN contains:
      | path | value      |
      | id   | harbor-log |
      | crew | main       |
    And the isaac file "sessions/main/harbor-log/current.ednl" exists
    When isaac is run with "sessions list"
    Then the stdout matches:
      | pattern                                                               |
      | SESSION .* AGE .* SIZE .* USED .* WINDOW .* PCT .* CREW .* CONTEXT    |
      | harbor-log\s+\S+\s+\S+\s+[\d,]+\s+[\d,]+\s+\d+%\s+main\s+full           |
      | lantern-room\s+\S+\s+\S+\s+[\d,]+\s+[\d,]+\s+\d+%\s+cordelia\s+episodes |
    And the exit code is 0

  @wip
  Scenario: a session is found by id alone through the sessions index, and an id that exists under another crew is refused
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | context-mode   | episodes         |
      | observers      | [:episodes]      |
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

  @wip
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
    And the isaac EDN file "sessions/index.edn" exists with:
      | path            | value |
      | harbor-log.crew | main  |
    When isaac is run with "sessions show lantern-room"
    Then the stdout contains "cordelia"
    And the exit code is 0
    And the isaac file "sessions/index.edn" EDN contains:
      | path              | value    |
      | harbor-log.crew   | main     |
      | lantern-room.crew | cordelia |

  @wip
  Scenario: the recall index locates a scene by session id, episode id, and scene id
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:embedding {:api "grover" :model "mini-embed"}}}
      """
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | context-mode   | episodes         |
      | observers      | [:episodes]      |
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
