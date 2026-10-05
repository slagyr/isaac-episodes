Feature: Recall — continuation seed carries an open episode forward
  An agent's last reply can be a pending offer or question. When that
  episode goes cold and the user replies later ("yes please do it"), the
  new episode opens with no idea what "it" refers to: today's recall
  either finds nothing (plain cold reopen) or a one-line gist buried
  under the "already handled, do not act on it again" memory contract
  (chained reopen) — wrong framing for something still open (isaac-mwqs).
  Every new episode on a thread that already has history is seeded with
  a continuation block: the previous episode's last exchange verbatim
  (last user message + last assistant reply), size-capped, framed as
  possibly still open, placed ahead of the recall search block. A
  session's first-ever episode has no prior history, so no such block.

  Background:
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
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
       :episodes {:gist-model :gist
                  :embedding {:api "grover" :model "mini-embed"}}}
      """

  @wip
  Scenario: an open offer survives a cold reopen, not as already-handled memory (isaac-mwqs)
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                     | model |
      | text | Want me to rotate the logs? | echo  |
      | text | 1-2: Log rotation offered   | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Given the current time is "2026-03-01T11:45:00"
    And the following model responses are queued:
      | type | content      | model |
      | text | Rotating now. | echo |
    When isaac is run with "prompt -m 'Yes please do it' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And crew "cordelia" has 2 episodes
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage
    And the last LLM request matches:
      | key      | value                                                                                                 |
      | messages | #"(?s)Where this conversation left off(?:(?!do not act on it again).)*Want me to rotate the logs\?" |
      | messages | #"(?s)Want me to rotate the logs\?.*Yes please do it"                                                |

  @wip
  Scenario: a chained reopen gets the continuation block alongside the lineage gists (isaac-mwqs)
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                     | model |
      | text | Want me to rotate the logs? | echo  |
      | text | 1-2: Log rotation offered   | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Given the current time is "2026-03-01T11:45:00"
    And the following model responses are queued:
      | type | content      | model |
      | text | Rotating now. | echo |
    When isaac is run with "prompt -m 'Yes please do it' --session reef-chat --crew cordelia"
    Then crew "cordelia" has 2 episodes
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage
    And the last LLM request matches:
      | key      | value                                   |
      | messages | #"(?s)Where this conversation left off" |
      | messages | #"(?s)Previously in this conversation"  |

  @wip
  Scenario: a very long last reply is truncated in the continuation block (isaac-mwqs)
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:gist-model :gist
                  :embedding {:api "grover" :model "mini-embed"}
                  :recall {:continuation {:max-chars 40}}}}
      """
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                                                                      | model |
      | text | Want me to rotate the logs, compact the archive, and clear the cache too?   | echo  |
      | text | 1-2: Housekeeping offered                                                    | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Given the current time is "2026-03-01T11:45:00"
    And the following model responses are queued:
      | type | content      | model |
      | text | Rotating now. | echo |
    When isaac is run with "prompt -m 'Yes please do it' --session reef-chat --crew cordelia"
    Then the last LLM request matches:
      | key      | value                                   |
      | messages | #"(?s)Where this conversation left off" |
      | messages | #"\[truncated\]"                        |
    And the last LLM request mentions "Want me to rotate the logs, compact the archive, and clear the cache too?" exactly 0 times
    And the last LLM request mentions "Want me to rotate the logs" exactly 1 time

  @wip
  Scenario: a session's first-ever episode has no continuation block (isaac-mwqs)
    Given the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And the last LLM request mentions "Where this conversation left off" exactly 0 times

  @wip
  Scenario: the continuation block comes before the recall search block (isaac-mwqs)
    Given crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                    |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |
    When isaac is run with "episodes index --crew cordelia"
    Given the current time is "2026-03-01T10:10:00"
    And the following model responses are queued:
      | type | content                     | model |
      | text | Want me to rotate the logs? | echo  |
      | text | 1-2: Log rotation offered   | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:15:00"
    Given the current time is "2026-03-01T11:55:00"
    And the following model responses are queued:
      | type | content          | model |
      | text | Pinot noir, aye. | echo  |
    When isaac is run with "prompt -m 'Remember that wine talk? Also yes, rotate the logs.' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And the last LLM request matches:
      | key      | value                                                                         |
      | messages | #"(?s)Where this conversation left off.*Recalled from earlier conversations" |
