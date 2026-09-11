Feature: Recall — live tools
  Episode crews can reach memory mid-episode: recall__search returns
  ranked gists with scene ids (same channels and floor as the recall
  CLI); recall__scene fetches one scene's distilled text by id. Tool
  results freeze into the transcript as ordinary tool messages (no
  separate event entry — the toolResult IS the frozen record); recalled
  refs accumulate on the episode record. recall__scene needs no
  embedding or index — a file fetch, available at Base tier.

  Background:
    Given default Grover setup

    Scenario: recall__search surfaces memory mid-episode
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | session-policy | episodes         |
    And the crew "cordelia" allows tools: recall/search
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                    |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |
    When isaac is run with "episodes index --crew cordelia"
    Given the following model responses are queued:
      | type     | tool_call      | arguments                 | content            | model |
      | toolCall | recall__search | {"query":"pheasant wine"} |                    | echo  |
      | text     |                |                           | It was pinot noir. | echo  |
    When isaac is run with "prompt -m 'Remember that wine talk?' --session bistro-chat --crew cordelia"
    Then the stdout contains "It was pinot noir."
    And the exit code is 0
    And that episode's backing session has transcript matching:
      | type    | message.role | message.content                                                        |
      | message | user         | Remember that wine talk?                                               |
      | message | assistant    | #"(?s)recall__search"                                                  |
      | message | toolResult   | #"(?s)\[2026-03-01-1000-s1x1 · 2026-03-01\] Wine pairing for pheasant" |
      | message | assistant    | It was pinot noir.                                                     |
    And that episode has recalled scenes:
      | scene-id             | origin-episode       |
      | 2026-03-01-1000-s1x1 | 2026-03-01-1000-ab12 |

    Scenario: recall__scene fetches distilled text by id; unknown ids fail helpfully
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | session-policy | episodes         |
    And the crew "cordelia" allows tools: recall/scene
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                    |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |
    And the following model responses are queued:
      | type     | tool_call     | arguments                           | content        | model |
      | toolCall | recall__scene | {"scene-id":"2026-03-01-1000-s1x1"} |                | echo  |
      | text     |               |                                     | Fetched it.    | echo  |
      | toolCall | recall__scene | {"scene-id":"2026-01-01-0000-none"} |                | echo  |
      | text     |               |                                     | Nothing there. | echo  |
    When isaac is run with "prompt -m 'Pull up that wine scene' --session bistro-chat --crew cordelia"
    Then the stdout contains "Fetched it."
    And that episode's backing session has transcript matching:
      | type    | message.role | message.content                        |
      | message | user         | Pull up that wine scene                |
      | message | assistant    | #"(?s)recall__scene"                   |
      | message | toolResult   | #"(?s)pinot noir suits roast pheasant" |
      | message | assistant    | Fetched it.                            |
    When isaac is run with "prompt -m 'And the ghost scene' --session bistro-chat --crew cordelia"
    Then the stdout contains "Nothing there."
    And the exit code is 0
    And that episode's backing session has transcript matching:
      | type    | message.role | message.content                        |
      | message | user         | Pull up that wine scene                |
      | message | assistant    | #"(?s)recall__scene"                   |
      | message | toolResult   | #"(?s)pinot noir suits roast pheasant" |
      | message | assistant    | Fetched it.                            |
      | message | user         | And the ghost scene                    |
      | message | assistant    | #"(?s)recall__scene"                   |
      | message | toolResult   | #"(?s)unknown scene"                   |
      | message | assistant    | Nothing there.                         |
