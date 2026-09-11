(ns isaac.episodes.service-spec
  (:require
    [isaac.episodes.service]
    [isaac.episodes.worker :as worker]
    [isaac.service.factory :as factory]
    [isaac.service.protocol :as service]
    [speclj.core :refer :all]))

(describe "episodes service"
  (it "starts and stops the idle-seal worker"
    (let [started (atom 0)
          stopped (atom nil)
          instance (factory/create :episodes {})]
      (with-redefs [worker/start! (fn [_]
                                    (swap! started inc)
                                    {:task-id :episodes/tick})
                    worker/stop!  #(reset! stopped %)]
        (service/start instance)
        (service/stop instance)
        (should= 1 @started)
        (should= {:task-id :episodes/tick} @stopped))))
  )
