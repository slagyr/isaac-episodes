(ns isaac.episodes.crew
  "The one rule for which crew an episode belongs to: the entity's own crew,
   else the operator's default crew, else nil. Never a hardcoded \"main\"."
  (:require
    [clojure.string :as str]
    [isaac.config.defaults :as config-defaults]
    [isaac.config.loader :as loader]))

(defn- named [crew]
  (when-not (and (string? crew) (str/blank? crew))
    crew))

(defn resolve-id
  "`crew` when it names one, else the default crew from `cfg` (the live
   config snapshot when `cfg` is nil). nil when neither names a crew."
  ([crew] (resolve-id crew nil))
  ([crew cfg]
   (or (named crew)
       (config-defaults/crew-id (or cfg (loader/snapshot "episodes: default crew"))))))
