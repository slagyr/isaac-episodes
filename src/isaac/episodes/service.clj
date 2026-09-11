(ns isaac.episodes.service
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.episodes.worker :as worker]))

(deftype EpisodesComponent [handle*]
  component/Component
  (start [this]
    (reset! handle* (worker/start! {}))
    this)
  (stop [this]
    (when-let [handle @handle*]
      (worker/stop! handle)
      (reset! handle* nil))
    this))

(defmethod component-factory/create :episodes [_ _ctx]
  (->EpisodesComponent (atom nil)))
