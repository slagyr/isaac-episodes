(ns isaac.session.episodes.crew
  "The one rule for which crew an episode belongs to: the entity's own crew,
   else the operator's default crew, else nil. Never a hardcoded \"main\"."
  (:require
    [clojure.string :as str]
    [isaac.agent.config.defaults :as config-defaults]
    [isaac.foundation.config.loader :as loader]))

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

(defn- merge-maps [base override]
  (merge-with (fn [left right]
                (if (and (map? left) (map? right))
                  (merge-maps left right)
                  right)) base override))

(defn config-for
  "Resolve a crew's nested episode settings over the global episode settings."
  [cfg crew]
  (let [id (if (keyword? crew) (name crew) (str crew))
        override (or (get-in cfg [:crew id :episodes])
                     (get-in cfg [:crew (keyword id) :episodes]))]
    (assoc cfg :episodes (merge-maps (:episodes cfg) override))))
