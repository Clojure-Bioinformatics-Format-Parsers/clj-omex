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

;;; ------------------------------------------------------------------
;;; Byte array support tests
;;; ------------------------------------------------------------------

(defn- read-file-bytes
  "Read a file into a byte array."
  [path]
  (let [file (cio/file path)
        bytes (byte-array (.length file))]
    (with-open [in (java.io.FileInputStream. file)]
      (.read in bytes))
    bytes))

(deftest list-zip-entries-byte-array-test
  (testing "lists ZIP entries from byte array"
    (let [zip-bytes (read-file-bytes test-omex-path)
          entries (io/list-zip-entries zip-bytes)]
      (is (vector? entries))
      (is (pos? (count entries)))
      (is (every? #(contains? % :name) entries))
      (is (every? #(contains? % :size) entries))))
  
  (testing "byte array results match file path results"
    (let [zip-bytes (read-file-bytes test-omex-path)
          entries-from-path (io/list-zip-entries test-omex-path)
          entries-from-bytes (io/list-zip-entries zip-bytes)]
      (is (= (count entries-from-path) (count entries-from-bytes)))
      (is (= (set (map :name entries-from-path))
             (set (map :name entries-from-bytes)))))))

(deftest extract-entry-byte-array-test
  (testing "extracts entry from byte array"
    (let [zip-bytes (read-file-bytes test-omex-path)
          manifest-bytes (io/extract-entry zip-bytes "manifest.xml")]
      (is (some? manifest-bytes))
      (is (bytes? manifest-bytes))
      (is (pos? (count manifest-bytes)))))
  
  (testing "byte array extraction matches file path extraction"
    (let [zip-bytes (read-file-bytes test-omex-path)
          manifest-from-path (io/extract-entry test-omex-path "manifest.xml")
          manifest-from-bytes (io/extract-entry zip-bytes "manifest.xml")]
      (is (java.util.Arrays/equals manifest-from-path manifest-from-bytes))))
  
  (testing "returns nil for non-existent entry"
    (let [zip-bytes (read-file-bytes test-omex-path)
          result (io/extract-entry zip-bytes "nonexistent.file")]
      (is (nil? result)))))

(deftest read-manifest-byte-array-test
  (testing "reads manifest from byte array"
    (let [zip-bytes (read-file-bytes test-omex-path)
          manifest (io/read-manifest zip-bytes)]
      (is (vector? manifest))
      (is (pos? (count manifest)))))
  
  (testing "byte array manifest matches file path manifest"
    (let [zip-bytes (read-file-bytes test-omex-path)
          manifest-from-path (io/read-manifest test-omex-path)
          manifest-from-bytes (io/read-manifest zip-bytes)]
      (is (= manifest-from-path manifest-from-bytes)))))

(deftest safe-read-manifest-byte-array-test
  (testing "safe-read-manifest works with byte array"
    (let [zip-bytes (read-file-bytes test-omex-path)
          result (io/safe-read-manifest zip-bytes)]
      (is (:ok result))
      (is (vector? (:manifest result)))
      (is (pos? (count (:manifest result)))))))

(deftest read-zip-to-memory-test
  (testing "reads ZIP file to memory map"
    (let [result (io/read-zip-to-memory test-omex-path)]
      (is (map? result))
      (is (pos? (count result)))
      (is (contains? result "manifest.xml"))
      (is (bytes? (get result "manifest.xml")))))
  
  (testing "reads byte array to memory map"
    (let [zip-bytes (read-file-bytes test-omex-path)
          result (io/read-zip-to-memory zip-bytes)]
      (is (map? result))
      (is (pos? (count result)))
      (is (contains? result "manifest.xml"))))
  
  (testing "file path and byte array produce same result"
    (let [zip-bytes (read-file-bytes test-omex-path)
          result-from-path (io/read-zip-to-memory test-omex-path)
          result-from-bytes (io/read-zip-to-memory zip-bytes)]
      (is (= (set (keys result-from-path))
             (set (keys result-from-bytes))))
      (doseq [key (keys result-from-path)]
        (is (java.util.Arrays/equals (get result-from-path key)
                                     (get result-from-bytes key)))))))

(deftest write-zip-from-memory-test
  (testing "writes memory map to ZIP bytes"
    (let [entries {"test.txt" (.getBytes "Hello World")
                   "data.xml" (.getBytes "<root><item/></root>")}
          zip-bytes (io/write-zip-from-memory entries)]
      (is (bytes? zip-bytes))
      (is (pos? (count zip-bytes)))))
  
  (testing "written ZIP can be read back"
    (let [entries {"test.txt" (.getBytes "Hello World")
                   "data.xml" (.getBytes "<root><item/></root>")}
          zip-bytes (io/write-zip-from-memory entries)
          read-back (io/read-zip-to-memory zip-bytes)]
      (is (= (set (keys entries)) (set (keys read-back))))
      (is (= "Hello World" (String. (get read-back "test.txt"))))
      (is (= "<root><item/></root>" (String. (get read-back "data.xml")))))))

(deftest roundtrip-test
  (testing "can roundtrip a ZIP file through memory"
    (let [;; Read original file to memory
          original-map (io/read-zip-to-memory test-omex-path)
          ;; Write to new ZIP bytes
          zip-bytes (io/write-zip-from-memory original-map)
          ;; Read back
          roundtrip-map (io/read-zip-to-memory zip-bytes)]
      (is (= (set (keys original-map)) (set (keys roundtrip-map))))
      ;; Verify each entry matches
      (doseq [key (keys original-map)]
        (is (java.util.Arrays/equals (get original-map key)
                                     (get roundtrip-map key)))))))
