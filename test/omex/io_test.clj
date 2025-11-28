(ns omex.io-test
  "Tests for omex.io namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [omex.io :as io]
            [clojure.java.io :as cio]))

(def test-omex-path "resources/Beard, 2005.omex")

(deftest list-zip-entries-test
  (testing "lists ZIP entries from an OMEX archive"
    (let [entries (io/list-zip-entries test-omex-path)]
      (is (vector? entries))
      (is (pos? (count entries)))
      (is (every? #(contains? % :name) entries))
      (is (every? #(contains? % :size) entries))
      (is (every? #(contains? % :compressed-size) entries))
      (is (every? #(contains? % :is-directory?) entries))
      (is (every? #(contains? % :last-modified) entries)))))

(deftest extract-entry-test
  (testing "extracts manifest.xml from an OMEX archive"
    (let [manifest-bytes (io/extract-entry test-omex-path "manifest.xml")]
      (is (some? manifest-bytes))
      (is (instance? (Class/forName "[B") manifest-bytes))
      (is (pos? (count manifest-bytes)))))
  
  (testing "returns nil for non-existent entry"
    (let [result (io/extract-entry test-omex-path "nonexistent.file")]
      (is (nil? result)))))

(deftest parse-manifest-test
  (testing "parses manifest.xml bytes into content entries"
    (let [manifest-bytes (io/extract-entry test-omex-path "manifest.xml")
          entries (io/parse-manifest manifest-bytes)]
      (is (vector? entries))
      (is (pos? (count entries)))
      (is (every? #(contains? % :location) entries))
      (is (every? #(contains? % :format) entries)))))

(deftest read-manifest-test
  (testing "reads and parses manifest.xml from an OMEX archive"
    (let [manifest (io/read-manifest test-omex-path)]
      (is (vector? manifest))
      (is (pos? (count manifest))))))

(deftest metadata-entries-test
  (testing "filters manifest entries for RDF metadata"
    (let [manifest (io/read-manifest test-omex-path)
          meta-entries (io/metadata-entries manifest)]
      (is (seq? meta-entries))
      ;; Check that any returned entries have an RDF-related format
      (doseq [entry meta-entries]
        (is (or (= "http://identifiers.org/combine.specifications/omex-metadata"
                   (:format entry))
                (= "application/rdf+xml"
                   (:format entry))))))))
