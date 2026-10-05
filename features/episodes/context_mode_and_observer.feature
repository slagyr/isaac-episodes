Feature: Episodes runs as a context mode plus a session observer (isaac-ka10)
  Episodes contributes two things through agent's berths. The `episodes`
  observer keeps the record: it opens and seals episodes, feeds scenes and
  keeps the index. The `episodes` context mode builds the turn's context
  from that record: a cold open at an episode boundary with lineage,
  continuation and recall. The context mode requires the observer, so a
  crew that wants episodic context sets both:
    :context-mode :episodes
    :observers    [:episodes]
  The observer runs fine without the context mode: the crew keeps its
  normal context and still produces episodes and scenes. The context mode
  waits for the observer to catch up on the session before a cold open.
  No automatic migration: crews on the retired :session-policy are moved
  by hand at deploy.
  Decision (2026-10-04, Micah).

  Background:
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | context-mode | episodes         |
      | observers    | [:episodes]      |
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
  Scenario: a crew on the episodes context mode and observer gets a cold open
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                     | model |
      | text | Want me to rotate the logs? | echo  |
      | text | 1-2: Log rotation offered   | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Given the current time is "2026-03-01T11:45:00"
    And the following model responses are queued:
      | type | content       | model |
      | text | Rotating now. | echo  |
    When isaac is run with "prompt -m 'Yes please do it' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And crew "cordelia" has 2 episodes
    And the last LLM request matches:
      | key      | value                                   |
      | messages | #"(?s)Where this conversation left off" |

  @wip
  Scenario: the observer alone records episodes while the crew keeps full context
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value |
      | context-mode | full  |
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                     | model |
      | text | Want me to rotate the logs? | echo  |
      | text | 1-2: Log rotation offered   | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Given the current time is "2026-03-01T11:45:00"
    And the following model responses are queued:
      | type | content       | model |
      | text | Rotating now. | echo  |
    When isaac is run with "prompt -m 'Yes please do it' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And crew "cordelia" has 2 episodes
    And an episode exists for crew "cordelia" matching:
      | key        | value     |
      | session-id | reef-chat |
      | status     | closed    |
    And that episode has scenes matching:
      | gist                 |
      | Log rotation offered |
    And the last LLM request matches:
      | key                 | value                   |
      | messages[1].content | The disk is getting full |
      | messages[3].content | Yes please do it        |
    And the last LLM request does not contain "Where this conversation left off"

  @wip
  Scenario: a reset crew with the observer still records scenes
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value |
      | context-mode | reset |
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                     | model |
      | text | Want me to rotate the logs? | echo  |
      | text | 1-2: Log rotation offered   | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Then an episode exists for crew "cordelia" matching:
      | key        | value     |
      | session-id | reef-chat |
      | status     | closed    |
    And that episode has scenes matching:
      | gist                 |
      | Log rotation offered |

  @wip
  Scenario: a one-turn --with-context-mode full skips the cold open and the observer keeps recording
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                     | model |
      | text | Want me to rotate the logs? | echo  |
      | text | 1-2: Log rotation offered   | gist  |
    When isaac is run with "prompt -m 'The disk is getting full' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Given the current time is "2026-03-01T11:45:00"
    And the following model responses are queued:
      | type | content       | model |
      | text | Rotating now. | echo  |
    When isaac is run with "prompt -m 'Yes please do it' --session reef-chat --crew cordelia --with-context-mode full"
    Then the exit code is 0
    And crew "cordelia" has 2 episodes
    And the last LLM request does not contain "Where this conversation left off"
    And the last LLM request matches:
      | key                 | value                    |
      | messages[1].content | The disk is getting full |

  @wip
  Scenario: the episodes context mode without its observer fails validation
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path      | value   |
      | observers | #delete |
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                         |
      | crew\.cordelia\.context-mode                                    |
      | context mode :episodes requires observer :episodes; crew has none |
    And the exit code is 1
