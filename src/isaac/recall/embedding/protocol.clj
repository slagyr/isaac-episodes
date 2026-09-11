(ns isaac.recall.embedding.protocol
  "Embedder protocol — batch-shaped text → vectors.")

(defprotocol Embedder
  "Batch-shaped embedding capability. `texts` is a sequential of strings;
   returns a vector of embedding vectors (one per input, order preserved)."
  (embed [this texts]))
