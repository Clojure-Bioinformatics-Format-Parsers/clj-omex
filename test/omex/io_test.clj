(ns omex.io-test
  "Tests for omex.io namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [omex.io :as io]
            [clojure.java.io :as cio]))

(def test-omex-path "test/resources/Beard, 2005.omex")
(def test-fixture-omex "test/resources/fixtures/test-archive.omex")

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
      (is (bytes? manifest-bytes))
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

;;; ------------------------------------------------------------------
;;; Safe parsing tests
;;; ------------------------------------------------------------------

(deftest safe-read-manifest-test
  (testing "safe-read-manifest returns structured success"
    (let [result (io/safe-read-manifest test-omex-path)]
      (is (:ok result))
      (is (vector? (:manifest result)))
      (is (pos? (count (:manifest result))))))
  
  (testing "safe-read-manifest returns structured error for invalid path"
    (let [result (io/safe-read-manifest "nonexistent.omex")]
      (is (not (:ok result)))
      (is (some? (get-in result [:error :message]))))))

(deftest safe-load-metadata-models-test
  (testing "safe-load-metadata-models returns structured result"
    (let [result (io/safe-load-metadata-models test-fixture-omex)]
      (is (:ok result))
      (is (vector? (:models result)))
      (is (vector? (:errors result))))))

;;; ------------------------------------------------------------------
;;; Namespace registry and URI helpers
;;; ------------------------------------------------------------------

(deftest normalize-uri-test
  (testing "normalizes identifiers.org URIs"
    (is (= "https://identifiers.org/go/GO:0008150"
           (io/normalize-uri "http://identifiers.org/go/GO:0008150")))
    (is (= "https://identifiers.org/chebi/CHEBI:17234"
           (io/normalize-uri "http://identifiers.org/chebi/CHEBI:17234"))))
  
  (testing "removes trailing slashes"
    (is (= "http://example.org/resource"
           (io/normalize-uri "http://example.org/resource/"))))
  
  (testing "handles nil input"
    (is (nil? (io/normalize-uri nil)))))

(deftest expand-curie-test
  (testing "expands CURIEs using namespace registry"
    (is (= "http://identifiers.org/opb/OPB_00340"
           (io/expand-curie "opb:OPB_00340")))
    (is (= "http://biomodels.net/biology-qualifiers/is"
           (io/expand-curie "bqbiol:is"))))
  
  (testing "returns original for unknown prefix"
    (is (= "unknown:value"
           (io/expand-curie "unknown:value"))))
  
  (testing "handles non-CURIE strings"
    (is (= "http://example.org/resource"
           (io/expand-curie "http://example.org/resource")))))

(deftest compact-uri-test
  (testing "compacts URIs to CURIEs"
    (is (= "opb:OPB_00340"
           (io/compact-uri "http://identifiers.org/opb/OPB_00340")))
    (is (= "bqbiol:is"
           (io/compact-uri "http://biomodels.net/biology-qualifiers/is"))))
  
  (testing "returns original for unknown namespace"
    (is (= "http://unknown.org/value"
           (io/compact-uri "http://unknown.org/value")))))
