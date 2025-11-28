(ns omex.stats-test
  "Tests for omex.stats namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [omex.stats :as stats]))

(def test-omex-path "test/resources/Beard, 2005.omex")

(deftest archive-basic-stats-test
  (testing "computes basic stats for an OMEX archive"
    (let [result (stats/archive-basic-stats test-omex-path)]
      (is (map? result))
      (is (= test-omex-path (:path result)))
      (is (pos? (:entry-count result)))
      (is (>= (:total-size result) 0))
      (is (>= (:total-compressed result) 0))
      (is (pos? (:manifest-entries result)))
      (is (>= (:metadata-entries result) 0)))))

(deftest omex-files-in-dir-test
  (testing "finds .omex files in a directory"
    (let [files (stats/omex-files-in-dir "test/resources")]
      (is (seq files))
      (is (every? #(.endsWith ^String % ".omex") files)))))

(deftest aggregate-stats-test
  (testing "aggregates stats over multiple archives"
    (let [files (stats/omex-files-in-dir "test/resources")
          result (stats/aggregate-stats files)]
      (is (map? result))
      (is (= (count files) (:archive-count result)))
      (is (pos? (:total-entries result)))
      (is (vector? (:per-archive result)))
      (is (= (count files) (count (:per-archive result)))))))
