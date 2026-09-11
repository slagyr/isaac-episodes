Feature: Recall is visible in the logs
  Episode recall happens silently today: recall-at-open records the chosen
  scenes only inside the episode record, and the recall__scene tool logs
  nothing. Operators reading server.log (or cli.log for an ACP process)
  cannot tell whether a turn remembered anything. Every recall decision
  logs an event with enough to judge it: what was searched, what was
  injected, the best score, and the floor (isaac-80vq).

  Background:
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | session-policy | episodes         |
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                    |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |
    When isaac is run with "episodes index --crew cordelia"

  Scenario: recall-at-open logs what it injected
    Given the following model responses are queued:
      | type | content              | model |
      | text | Pinot noir, as ever. | echo  |
    When isaac is run with "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia"
    Then the exit code is 0
    And the log has entries matching:
      | level | event              | crew     | thread      | search | lineage | top            | floor |
      | :info | :episodes/recalled | cordelia | supper-chat | 1      | 0       | #"0\.[0-9]+"   | 0.47  |

  Scenario: a query that clears nothing logs the best score it saw
    Given the following model responses are queued:
      | type | content        | model |
      | text | Nothing there. | echo  |
    When isaac is run with "prompt -m 'How do I rotate the ship logs?' --session logs-chat --crew cordelia"
    Then the exit code is 0
    And the log has entries matching:
      | level | event                  | crew     | thread    | best         | floor |
      | :info | :episodes/recall-empty | cordelia | logs-chat | #"0\.[0-9]+" | 0.47  |

  Scenario: the recall tool logs the scene it fetched
    Given the built-in tools are registered
    And the crew "cordelia" allows tools: "recall/*"
    And the following model responses are queued:
      | type      | tool_call     | arguments                            | content              | model |
      | tool_call | recall__scene | {"scene-id": "2026-03-01-1000-s1x1"} |                      | echo  |
      | text      |               |                                      | Pinot noir, as ever. | echo  |
    When isaac is run with "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia"
    Then the exit code is 0
    And the log has entries matching:
      | level | event         | crew     | scene                | episode              |
      | :info | :recall/scene | cordelia | 2026-03-01-1000-s1x1 | 2026-03-01-1000-ab12 |
