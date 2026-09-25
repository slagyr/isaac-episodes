Feature: Recall ledger — an opt-in per-crew research log of every recall
  With recall.ledger on (isaac-ozh5), each recall for a crew appends one EDN line to
  sessions/<crew>/recall/ledger.ednl: the query text, every candidate scene
  above the floor with its score and gist, and what was actually injected.
  Off by default; the regular logs stay as they are.

  Background:
    Given an Isaac root at "target/test-state"
    And default Grover setup
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | episodes         |
    And config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:embedding {:api "grover" :model "mini-embed"}}
       :recall   {:ledger true}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                    |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |
    When isaac is run with "episodes index --crew cordelia"

  Scenario: with recall.ledger on, a turn-start recall is appended to the crew's ledger (isaac-ozh5)
    Given the following model responses are queued:
      | type | content            | model |
      | text | It was pinot noir. | echo  |
    When isaac is run with "prompt -m 'Remember that wine talk?' --session bistro-chat --crew cordelia"
    Then the exit code is 0
    And the crew "cordelia" recall ledger has entries matching:
      | kind    | session     | query                    | candidates                        | injected             |
      | :inject | bistro-chat | Remember that wine talk? | #"(?s).*2026-03-01-1000-s1x1.*"   | 2026-03-01-1000-s1x1 |

  Scenario: with recall.ledger on, a recall__search call is appended with kind :search (isaac-ozh5)
    Given the crew "cordelia" allows tools: recall/search
    And the following model responses are queued:
      | type     | tool_call      | arguments                 | content            | model |
      | toolCall | recall__search | {"query":"pheasant wine"} |                    | echo  |
      | text     |                |                           | It was pinot noir. | echo  |
    When isaac is run with "prompt -m 'Remember that wine talk?' --session bistro-chat --crew cordelia"
    Then the exit code is 0
    And the crew "cordelia" recall ledger has entries matching:
      | kind    | session     | query         | candidates                      |
      | :search | bistro-chat | pheasant wine | #"(?s).*2026-03-01-1000-s1x1.*" |
