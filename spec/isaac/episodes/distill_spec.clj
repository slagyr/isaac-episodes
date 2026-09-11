(ns isaac.episodes.distill-spec
  (:require
    [isaac.episodes.distill :as sut]
    [speclj.core :refer :all]))

(describe "isaac.episodes.distill"

  (context "message-text"
    (it "keeps plain user text"
      (should= "Hello"
               (sut/message-text {:type "message"
                                  :message {:role "user" :content "Hello"}})))

    (it "keeps text blocks from content vectors"
      (should= "A light pinot noir."
               (sut/message-text {:type "message"
                                  :message {:role "assistant"
                                            :content [{:type "text" :text "A light pinot noir."}]}})))

    (it "collapses toolCall content items to markers and drops toolResult"
      (let [entry {:type "message"
                   :message {:role "assistant"
                             :content [{"type" "toolCall"
                                        "id" "call_1"
                                        "name" "read"
                                        "arguments" {"filePath" "fridge.txt"}}]}}]
        (should= "(tool read filePath=fridge.txt)"
                 (sut/message-text entry))))

    (it "parses JSON string content carrying toolCall items"
      (let [entry {:type "message"
                   :message {:role "assistant"
                             :content "[{\"type\":\"toolCall\",\"id\":\"call_1\",\"name\":\"read\",\"arguments\":{\"filePath\":\"fridge.txt\"}}]"}}]
        (should= "(tool read filePath=fridge.txt)"
                 (sut/message-text entry))))

    (it "returns nil for toolResult roles (dropped from scene text)"
      (should-be-nil
        (sut/message-text {:type "message"
                           :message {:role "toolResult"
                                     :toolCallId "call_1"
                                     :content "secret payload"}})))
    )

  (context "distill-entry"
    (it "preserves id/timestamp/role and distilled text"
      (let [entry {:type "message"
                   :id "abc12345"
                   :timestamp "2026-01-02T03:04:05"
                   :message {:role "user" :content "Chart the reef."}}
            d (sut/distill-entry entry)]
        (should= "abc12345" (:id d))
        (should= "2026-01-02T03:04:05" (:timestamp d))
        (should= "user" (:role d))
        (should= "Chart the reef." (:text d))
        (should-not (:dropped? d))))

    (it "marks toolResult as dropped but keeps ordinal slot"
      (let [d (sut/distill-entry {:type "message"
                                  :id "t1"
                                  :timestamp "2026-01-02T03:04:05"
                                  :message {:role "toolResult" :content "payload"}})]
        (should (:dropped? d))
        (should-be-nil (:text d))))
    )

  (context "format-span-prompt"
    (it "numbers messages 1..N and includes preceding compaction summary"
      (let [msgs [{:id "a" :role "user" :text "one" :dropped? false}
                  {:id "b" :role "assistant" :text "two" :dropped? false}]
            prompt (sut/format-span-prompt msgs "They planned the voyage provisions.")]
        (should (re-find #"planned the voyage provisions" prompt))
        (should (re-find #"(?m)^1\. \[user\] one$" prompt))
        (should (re-find #"(?m)^2\. \[assistant\] two$" prompt))))

    (it "still numbers dropped toolResult slots so ordinals tile the full span"
      (let [msgs [{:id "a" :role "user" :text "q" :dropped? false}
                  {:id "b" :role "toolResult" :text nil :dropped? true}
                  {:id "c" :role "assistant" :text "a" :dropped? false}]
            prompt (sut/format-span-prompt msgs nil)]
        (should (re-find #"(?m)^2\. \[toolResult\] \(dropped\)$" prompt))
        (should (re-find #"(?m)^3\. \[assistant\] a$" prompt))))

    (it "states the message count and requires the final line to end at N"
      (let [msgs [{:id "a" :role "user" :text "one" :dropped? false}
                  {:id "b" :role "assistant" :text "two" :dropped? false}
                  {:id "c" :role "user" :text "three" :dropped? false}]
            prompt (sut/format-span-prompt msgs nil)]
        (should (re-find #"There are 3 messages" prompt))
        (should (re-find #"final line must end at 3" prompt))))

    (it "pushes against one-blob answers"
      (let [msgs [{:id "a" :role "user" :text "one" :dropped? false}]
            prompt (sut/format-span-prompt msgs nil)]
        (should (re-find #"Prefer several scenes" prompt))))

    (it "instructs routine marking and what-not-how gists"
      (let [msgs [{:id "a" :role "user" :text "one" :dropped? false}]
            prompt (sut/format-span-prompt msgs nil)]
        (should (re-find #"(?s)(?=.*routine)(?=.*~)(?=.*evidence, not the subject)(?=.*what was accomplished)" prompt))))

    (it "instructs (cont) marks for a scene that resumes an earlier topic"
      (let [msgs [{:id "a" :role "user" :text "one" :dropped? false}]
            prompt (sut/format-span-prompt msgs nil)]
        (should (re-find #"\(cont " prompt))
        (should (re-find #"resumes" prompt))))
    )
  )
