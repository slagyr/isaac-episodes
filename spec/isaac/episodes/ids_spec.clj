(ns isaac.episodes.ids-spec
  (:require
    [isaac.episodes.ids :as sut]
    [speclj.core :refer :all]))

(describe "isaac.episodes.ids"

  (it "formats 17-digit ids from ISO timestamps"
    (should= "20260102030405000"
             (sut/timestamped-id "2026-01-02T03:04:05"))
    (should (re-matches #"\d{17}" (sut/timestamped-id "2026-01-02T03:04:05"))))

  (it "includes milliseconds when present"
    (should= "20260102030405123"
             (sut/timestamped-id "2026-01-02T03:04:05.123Z")))

  (it "accepts epoch millis"
    (should= "19700101000000000"
             (sut/timestamped-id 0)))

  (it "falls back to now when timestamp missing"
    (with-redefs [sut/now-ms (constantly 0)]
      (should= "19700101000000000"
               (sut/timestamped-id nil))))

  (it "accepts an Instant"
    (should= "20260301100000000"
             (sut/timestamped-id (java.time.Instant/parse "2026-03-01T10:00:00Z"))))

  (it "bumps by 1ms when the id is already used in the parent"
    (let [used #{"20260301100000000"}]
      (should= "20260301100000001"
               (sut/unique-timestamped-id "2026-03-01T10:00:00Z" used))))

  (it "bumps repeatedly until the id is free"
    (let [used #{"20260301100000000" "20260301100000001" "20260301100000002"}]
      (should= "20260301100000003"
               (sut/unique-timestamped-id "2026-03-01T10:00:00Z" used))))
  )
