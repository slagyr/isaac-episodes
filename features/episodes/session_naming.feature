Feature: Episode sessions are named like every other session (isaac-7rce)
  Episode ids stay timestamps; the SESSION behind them is named the way any
  chronicle session is. A caller that names a session (a comm's canonical
  per-space id, --session foo) keeps that id. A caller that names none gets a
  name from the agent's naming strategy — adjective-noun by default,
  sequential when configured — because the AGENT owns session naming and the
  episodes policy takes the identifier it is handed.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | :episodes        |

  Scenario: an explicit --session keeps its name as the session id
    Given the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And the isaac file "sessions/cordelia/reef-chat/session.edn" EDN contains:
      | path           | value     |
      | id             | reef-chat |
      | crew           | cordelia  |
      | session-policy | :episodes |
    And an episode exists for crew "cordelia" matching:
      | key        | value     |
      | id         | #"\d{17}" |
      | status     | open      |
      | session-id | reef-chat |

  Scenario: a start with no session id is named by the naming strategy, not by the episode clock
    Given config:
      | sessions.naming-strategy | sequential |
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --crew cordelia"
    Then the exit code is 0
    And the isaac file "sessions/cordelia/session-1/session.edn" EDN contains:
      | path           | value     |
      | id             | session-1 |
      | crew           | cordelia  |
      | session-policy | :episodes |
    And an episode exists for crew "cordelia" matching:
      | key        | value     |
      | id         | #"\d{17}" |
      | status     | open      |
      | session-id | session-1 |
