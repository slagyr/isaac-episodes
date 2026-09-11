Feature: Idle sealing — a quiet thread becomes recallable within minutes
  Recall only sees sealed, indexed scenes. Today a scene seals on size cap,
  on topic drift, or when the episode closes — and an episode closes only
  when the NEXT message arrives cold, on compaction, or by hand. A short
  conversation that goes quiet therefore stays invisible to every other
  thread indefinitely (isaac-q34y). The episodes worker ticks every 30s:
  an open episode whose last message is older than :episodes :seal
  :idle-minutes (default 3), with an unsealed tail and no turn in flight,
  seals its WHOLE tail (leave-open 0) and indexes it; the episode stays
  open and warm. A resumed conversation appends a continuation scene.
  Episodes cold past :episodes :ttl-minutes (default 60) close on the same
  tick. Closing is housekeeping; sealing is what makes memory visible.

  Background:
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | session-policy | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes  {:gist-model :gist}
       :embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """

  Scenario: an idle thread seals its tail on the tick and stays open
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                    | model |
      | text | Charted, keep west         | echo  |
      | text | Buoys marked with the tool | echo  |
      | text | 1-4: Reef passage charted  | gist  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T10:01:00"
    When isaac is run with "prompt -m 'Mark the buoys' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    Then an episode exists for crew "cordelia" matching:
      | key    | value |
      | status | open  |
    And that episode has scenes matching:
      | gist                 | text             | seal-reason |
      | Reef passage charted | #"(?s)keep west" | idle        |
    And the index for crew "cordelia" has a row for gist "Reef passage charted"

  Scenario: a warm thread is left alone
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:02:00"
    Then an episode exists for crew "cordelia" matching:
      | key    | value |
      | status | open  |
    And that episode has 0 scenes

  Scenario: resuming after an idle seal continues the same episode
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                            | model |
      | text | Charted, keep west                 | echo  |
      | text | 1-2: Reef passage charted          | gist  |
      | text | Buoys marked with the tool         | echo  |
      | text | 1-2: (cont 1-2) Buoy tooling       | gist  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    Given the current time is "2026-03-01T10:06:00"
    When isaac is run with "prompt -m 'Mark the buoys' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:10:00"
    Then crew "cordelia" has 1 episode
    And an episode exists for crew "cordelia" matching:
      | key    | value |
      | status | open  |
    And that episode has scenes matching:
      | gist                 | text                | continues |
      | Reef passage charted | #"(?s)keep west"    |           |
      | Buoy tooling         | #"(?s)Buoys marked" | #".+"     |

  Scenario: another thread can recall the sealed scene within minutes
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                   | model |
      | text | Charted, keep west        | echo  |
      | text | 1-2: Reef passage charted | gist  |
      | text | West of the buoys, aye.   | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    Given the current time is "2026-03-01T10:06:00"
    When isaac is run with "prompt -m 'Which way through the reef passage?' --session harbor-log --crew cordelia"
    Then the exit code is 0
    And crew "cordelia" has 2 episodes
    And the last LLM request matches:
      | key      | value                                                  |
      | messages | #"(?s)Recalled from earlier conversations.*recall__scene" |
      | messages | #"(?s)Reef passage charted"                            |

  Scenario: a cold episode closes on the tick and the next message chains a successor
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                   | model |
      | text | Charted, keep west        | echo  |
      | text | 1-2: Reef passage charted | gist  |
      | text | Watches dogged            | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Then an episode exists for crew "cordelia" matching:
      | key    | value  |
      | status | closed |
    Given the current time is "2026-03-01T11:10:00"
    When isaac is run with "prompt -m 'Set the watch rotation' --session reef-chat --crew cordelia"
    Then crew "cordelia" has 2 episodes
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage

  Scenario: an open episode with nothing to seal is deleted by the TTL sweep, once, and the log says so (isaac-9tjo)
    Field 2026-09-09/10: six successor episodes whose backing transcripts held
    only the compaction summary could never be sealed (nothing to segment →
    no episode written), yet the sweep logged :episodes/closed :reason
    :ttl-sweep for each of them every 30 s — 456 false lines and six
    episodes stuck :open. Micah: an episode with no content has no value —
    delete it (record + backing session); log :closing before the attempt,
    :closed / :deleted after, and :close-failed with the error when it fails.
    Given the isaac EDN file "episodes/cordelia/20260301100000000/episode.edn" exists with:
      | path       | value             |
      | id         | 20260301100000000 |
      | crew       | cordelia          |
      | status     | open              |
      | thread     | reef-chat         |
      | started-at | 2026-03-01T10:00:00 |
    And the isaac EDN file "sessions/20260301100000000/session.edn" exists with:
      | path | value             |
      | id   | 20260301100000000 |
      | name | Reef Chat         |
      | crew | cordelia          |
    And the isaac file "sessions/20260301100000000/current.ednl" exists with:
      """
      {:type "session" :id "s0" :timestamp "2026-03-01T10:00:00" :crew "cordelia"}
      {:type "compaction" :id "c1" :timestamp "2026-03-01T10:00:01" :summary "Charted the reef passage."}
      """
    When the episodes worker ticks at "2026-03-01T11:30:00"
    Then the isaac file "episodes/cordelia/20260301100000000/episode.edn" does not exist
    And the isaac file "sessions/20260301100000000/session.edn" does not exist
    And crew "cordelia" has 0 episodes
    And the log has entries matching:
      | level | event             | episode           | reason |
      | :info | :episodes/closing | 20260301100000000 |        |
      | :info | :episodes/deleted | 20260301100000000 | :empty |
    When the episodes worker ticks at "2026-03-01T11:31:00"
    Then the log does not have entries matching:
      | event             | episode           |
      | :episodes/closing | 20260301100000000 |
