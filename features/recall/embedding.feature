Feature: Embedding Seam
  `isaac embed` exercises the embedding capability: text in, vector out,
  via the Embedding API resolved from `:episodes :embedding`.
  Embedding is an OPTIONAL capability — absence of `:episodes :embedding`
  is a legal configuration (Base/Remembering tier), not an error.

  `:episodes :embedding` names an `:api` (multimethod dispatch) plus
  connection fields (`:model`, optional `:base-url` / `:api-key`).
  Embedding models never enter the :models collection.

  Background:
    Given an Isaac root at "isaac-state"

  # ----- Help -----

  Scenario: embed is registered and has help
    When isaac is run with "help embed"
    Then the stdout matches:
      | pattern                                                |
      | Usage: isaac embed \[options\] \[text \.\.\.\]         |
      | Embed text with the configured embedding API           |
      | Arguments:                                             |
      | text\s+Text to embed \(one vector per argument\)       |
    And the exit code is 0

  # ----- Optional capability -----

  Scenario: embedding unconfigured is a legal tier, not an error
    When isaac is run with "config validate"
    Then the exit code is 0
    When isaac is run with "embed hello"
    Then the stderr contains "no embedding configured"
    And the stderr contains ":episodes"
    And the stderr contains ":embedding"
    And the stderr does not contain "Exception"
    And the exit code is 1

  # ----- Embedding API -----

  Scenario: grover embedding api embeds hello
    Given config file "isaac.edn" containing:
      """
      {:episodes {:embedding {:api "grover" :model "mini-embed"}}}
      """
    When isaac is run with "embed hello"
    Then the stdout matches:
      | pattern           |
      | \[5 532 104 111\] |
    And the exit code is 0

  Scenario: batch embed yields one vector per input text, in order
    Given config file "isaac.edn" containing:
      """
      {:episodes {:embedding {:api "grover" :model "mini-embed"}}}
      """
    When isaac is run with "embed \"hi there\" cat \"hi there\""
    Then the stdout lines match:
      | text            |
      | [8 777 104 101] |
      | [3 312 99 116]  |
      | [8 777 104 101] |
    And the exit code is 0

  Scenario: ollama embedding api POSTs /api/embed
    Given config file "isaac.edn" containing:
      """
      {:episodes {:embedding {:api "ollama" :model "nomic-embed-text" :simulate-provider "ollama"}}}
      """
    When isaac is run with "embed hello"
    Then the last outbound HTTP request matches:
      | key        | value            |
      | url        | #".*/api/embed"  |
      | body.model | nomic-embed-text |
      | body.input | ["hello"]        |
    And the exit code is 0

  Scenario: embeddings api POSTs /embeddings with bearer
    Given config file "isaac.edn" containing:
      """
      {:episodes {:embedding {:api "embeddings"
                              :model "text-embedding-3-large"
                              :base-url "https://api.openai.com/v1"
                              :api-key "sk-harbor-test"
                              :simulate-provider "openai"}}}
      """
    When isaac is run with "embed hello"
    Then the last outbound HTTP request matches:
      | key                   | value                     |
      | url                   | #".*/embeddings"          |
      | headers.Authorization | Bearer sk-harbor-test     |
      | body.model            | text-embedding-3-large    |
      | body.input            | ["hello"]                 |
    And the exit code is 0

  # ----- Validation -----

  Scenario: config validation rejects an unknown embedding api
    Given config file "isaac.edn" containing:
      """
      {:episodes {:embedding {:api "warp-drive" :model "mini-embed"}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                              |
      | episodes\.embedding\.api                                             |
      | bad value: warp-drive                                                |
      | must be (a registered contribution to :isaac\.session\.episodes/embedding-api)?(one of)? |
    And the exit code is 1
