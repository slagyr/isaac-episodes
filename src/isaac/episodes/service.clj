(ns isaac.episodes.service
  (:require
    [isaac.episodes.worker :as worker]
    [isaac.service.factory :as factory]
    [isaac.service.protocol :as service]))

(deftype EpisodesService [handle*]
  service/Service
  (start [_]
    (reset! handle* (worker/start! {})))
  (stop [_]
    (when-let [handle @handle*]
      (worker/stop! handle)
      (reset! handle* nil))))

(defmethod factory/create :episodes [_ _ctx]
  (->EpisodesService (atom nil)))
