(ns isaac.episodes.module
  (:require
    [isaac.module.protocol :as module]
    [isaac.session.policy.episodes]))

(defn create-module []
  (module/module))
