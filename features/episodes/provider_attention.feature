Feature: Episode provider attention
  Episode failures use the agent attention seam.

  Scenario: an episode seal failing on the gist provider posts attention through the same seam
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
      {:defaults {:frequencies {:crew "cordelia"}}
       :episodes {:gist-model :gist
                  :embedding {:api "grover" :model "mini-embed"}}
       :attention {:notify {:comm "discord" :target "boiler-room"}}}
      """
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | model | type       | status | content            | message                                           |
      | echo  | text       |        | Charted, keep west |                                                   |
      | gist  | http-error | 400    |                    | The 'gist' model is not supported on this account |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    And the episodes worker ticks at "2026-03-01T10:05:00"
    Then the log has entries matching:
      | level | event                 | reason          |
      | :warn | :episodes/seal-failed | :provider-error |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                            |
      | comm    | :discord                                         |
      | target  | boiler-room                                      |
      | content | contains "grover" and "gist" and "not supported" |
