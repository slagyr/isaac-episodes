Feature: Embedding Seam
  `isaac embed` exercises the embedding capability: text in, vector out,
  via the embedder resolved from the root-level :embedding config.
  Embedding is an OPTIONAL capability — absence of :embedding is a legal
  configuration (Base/Remembering tier), not an error.

  :embedding is a discriminated union on :source. The only source in this
  bean is :provider, with separate :provider and :model keys (no
  "provider:model" ref strings — ollama model names contain colons).
  Embedding models never enter the :models collection; they are a
  different category from chat models.

  Background:
    Given an Isaac root at "isaac-state"

  # ----- Help -----

  Scenario: embed is registered and has help
    When isaac is run with "help embed"
    Then the stdout matches:
      | pattern                                                |
      | Usage: isaac embed \[options\] \[text \.\.\.\]         |
      | Embed text with the configured embedding provider      |
      | Arguments:                                             |
      | text\s+Text to embed \(one vector per argument\)       |
    And the exit code is 0

  # ----- Optional capability -----

  Scenario: embedding unconfigured is a legal tier, not an error
    When isaac is run with "config validate"
    Then the exit code is 0
    When isaac is run with "embed hello"
    Then the stderr contains "no embedding configured"
    And the stderr contains ":embedding"
    And the stderr does not contain "Exception"
    And the exit code is 1

  # ----- Provider-backed embedding -----

  Scenario: embed through a provider-backed embedder
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    When isaac is run with "embed hello"
    Then the stdout matches:
      | pattern           |
      | \[5 532 104 111\] |
    And the exit code is 0

  Scenario: batch embed yields one vector per input text, in order
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    When isaac is run with "embed \"hi there\" cat \"hi there\""
    Then the stdout lines match:
      | text            |
      | [8 777 104 101] |
      | [3 312 99 116]  |
      | [8 777 104 101] |
    And the exit code is 0

  # ----- Provider config resolution -----

  Scenario: embedding resolves provider config and hits the embed endpoint
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover:ollama" :model "nomic-embed-text"}}
      """
    When isaac is run with "embed hello"
    Then the last outbound HTTP request matches:
      | key        | value            |
      | url        | #".*/api/embed"  |
      | body.model | nomic-embed-text |
      | body.input | ["hello"]        |
    And the exit code is 0

  # ----- Validation -----

  Scenario: config validation rejects an embedding config with an unknown provider
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "nonesuch" :model "nomic-embed-text"}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                             |
      | embedding\.provider.*references undefined provider |
      | bad value: nonesuch                                 |
    And the exit code is 1

  Scenario: config validation rejects an unknown embedding source
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :warp-drive :provider "grover" :model "mini-embed"}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                    |
      | embedding\.source          |
      | bad value: warp-drive      |
      | must be one of.*provider   |
    And the exit code is 1
