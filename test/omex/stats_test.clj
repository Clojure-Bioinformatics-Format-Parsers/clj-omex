(ns omex.stats-test
  "Tests for omex.stats namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [omex.stats :as stats]
            [omex.test-util :refer [read-file-bytes]]
            [clojure.java.io :as cio]))

(def test-omex-path "test/resources/Beard, 2005.omex")
(def test-fixture-omex "test/resources/fixtures/test-archive.omex")

(deftest archive-basic-stats-test
  (testing "computes basic stats for an OMEX archive"
    (let [result (stats/archive-basic-stats test-omex-path)]
      (is (map? result))
      (is (= test-omex-path (:source result)))
      (is (pos? (:entry-count result)))
      (is (>= (:total-size result) 0))
      (is (>= (:total-compressed result) 0))
      (is (pos? (:manifest-entries result)))
      (is (>= (:metadata-entries result) 0))))
  
  (testing "includes annotation statistics"
    (let [result (stats/archive-basic-stats test-fixture-omex)]
      (is (number? (:num-singular-annotations result)))
      (is (number? (:num-composite-annotations result)))
      (is (number? (:num-process-annotations result)))
      (is (map? (:top-opb-terms result)))
      (is (vector? (:annotation-extraction-errors result))))))

(deftest archive-basic-stats-byte-array-test
  (testing "computes basic stats from byte array"
    (let [zip-bytes (read-file-bytes test-omex-path)
          result (stats/archive-basic-stats zip-bytes)]
      (is (map? result))
      (is (= :byte-array (:source result)))
      (is (pos? (:entry-count result)))
      (is (>= (:total-size result) 0))
      (is (>= (:total-compressed result) 0))
      (is (pos? (:manifest-entries result)))
      (is (>= (:metadata-entries result) 0))))
  
  (testing "byte array stats match file path stats (except source)"
    (let [zip-bytes (read-file-bytes test-omex-path)
          result-from-path (stats/archive-basic-stats test-omex-path)
          result-from-bytes (stats/archive-basic-stats zip-bytes)]
      ;; All fields should match except :source
      (is (= (:entry-count result-from-path) (:entry-count result-from-bytes)))
      (is (= (:total-size result-from-path) (:total-size result-from-bytes)))
      (is (= (:manifest-entries result-from-path) (:manifest-entries result-from-bytes)))
      (is (= (:metadata-entries result-from-path) (:metadata-entries result-from-bytes))))))

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
      (is (= (count files) (count (:per-archive result))))))
  
  (testing "includes aggregate annotation statistics"
    (let [result (stats/aggregate-stats [test-fixture-omex])]
      (is (number? (:total-singular-annotations result)))
      (is (number? (:total-composite-annotations result)))
      (is (number? (:total-process-annotations result)))
      (is (map? (:aggregate-opb-terms result))))))
