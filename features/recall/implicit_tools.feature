@wip
Feature: Episode crews are granted the recall tools
  An episodes crew receives recall__search and recall__scene whether or not
  its allow list names them. A crew that denies :recall/* does not receive
  them. A chronicle crew receives them only when its allow list says so.
  Decision (2026-09-27, Micah): the grant is part of choosing the episodes
  policy. Deny still wins.

  Background:
    Given default Grover setup

  Scenario: an episodes crew whose allow list omits recall still gets the recall tools
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | episodes         |
      | tools.allow    | fs/read          |
    And config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:embedding {:api "grover" :model "mini-embed"}}}
      """
    And the following model responses are queued:
      | type | content | model |
      | text | Heard.  | echo  |
    When isaac is run with "prompt hi --crew cordelia"
    Then the exit code is 0
    And the prompt has tools:
      | name           |
      | fs__read       |
      | recall__search |
      | recall__scene  |

  Scenario: an episodes crew with no tools section gets only the recall tools
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | episodes         |
    And config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:embedding {:api "grover" :model "mini-embed"}}}
      """
    And the following model responses are queued:
      | type | content | model |
      | text | Heard.  | echo  |
    When isaac is run with "prompt hi --crew cordelia"
    Then the exit code is 0
    And the prompt has tools:
      | name           |
      | recall__search |
      | recall__scene  |

  Scenario: an episodes crew that denies recall does not receive the recall tools
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | episodes         |
      | tools.allow    | fs/read          |
      | tools.deny     | [:recall/*]      |
    And config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:embedding {:api "grover" :model "mini-embed"}}}
      """
    And the following model responses are queued:
      | type | content | model |
      | text | Heard.  | echo  |
    When isaac is run with "prompt hi --crew cordelia"
    Then the exit code is 0
    And the prompt has tools:
      | name     |
      | fs__read |

  Scenario: a chronicle crew is not granted the recall tools
    Given the crew "main" allows tools: "fs/read"
    And the following model responses are queued:
      | type | content | model |
      | text | Heard.  | echo  |
    When isaac is run with "prompt hi"
    Then the exit code is 0
    And the prompt has tools:
      | name     |
      | fs__read |
