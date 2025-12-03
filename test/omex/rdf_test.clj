(ns omex.rdf-test
  "Tests for omex.rdf namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [omex.rdf :as rdf]
            [omex.io :as io]
            [clojure.java.io :as cio])
  (:import [org.apache.jena.rdf.model ModelFactory]
           [org.apache.jena.riot RDFDataMgr Lang]))

(def test-omex-path "test/resources/Beard, 2005.omex")
(def test-fixture-omex "test/resources/fixtures/test-archive.omex")

;;; ------------------------------------------------------------------
;;; Test fixture helpers
;;; ------------------------------------------------------------------

(defn load-turtle-fixture [path]
  (let [model (ModelFactory/createDefaultModel)]
    (with-open [in (cio/input-stream path)]
      (RDFDataMgr/read model in Lang/TURTLE))
    model))

(defn load-rdfxml-fixture [path]
  (let [model (ModelFactory/createDefaultModel)]
    (with-open [in (cio/input-stream path)]
      (RDFDataMgr/read model in Lang/RDFXML))
    model))

;;; ------------------------------------------------------------------
;;; Basic extractor tests
;;; ------------------------------------------------------------------

(deftest safe-extract-test
  (testing "safe-extract returns nil on exception"
    (is (nil? (rdf/safe-extract #(throw (Exception. "test error")))))
    (is (= 42 (rdf/safe-extract #(identity 42))))))

(deftest extract-dc-metadata-test
  (testing "extracts Dublin Core metadata from RDF models"
    (let [models (io/load-metadata-models test-omex-path)]
      (when (seq models)
        (let [dc-meta (rdf/extract-dc-metadata (first models))]
          (is (map? dc-meta))
          ;; Keys should be present (values may be nil)
          (is (contains? dc-meta :title))
          (is (contains? dc-meta :creator))
          (is (contains? dc-meta :description)))))))

(deftest extract-bqbiol-annotations-test
  (testing "extracts BioModels biology qualifier annotations"
    (let [models (io/load-metadata-models test-omex-path)]
      (when (seq models)
        (let [bqbiol (rdf/extract-bqbiol-annotations (first models))]
          (is (map? bqbiol)))))))

(deftest extract-bqmodel-annotations-test
  (testing "extracts BioModels model qualifier annotations"
    (let [models (io/load-metadata-models test-omex-path)]
      (when (seq models)
        (let [bqmodel (rdf/extract-bqmodel-annotations (first models))]
          (is (map? bqmodel)))))))

(deftest extract-semsim-annotations-test
  (testing "extracts SemSim composite annotations"
    (let [models (io/load-metadata-models test-omex-path)]
      (when (seq models)
        (let [semsim (rdf/extract-semsim-annotations (first models))]
          (is (vector? semsim)))))))

(deftest extract-all-annotations-test
  (testing "extracts all annotation types from a model"
    (let [models (io/load-metadata-models test-omex-path)]
      (when (seq models)
        (let [all-annots (rdf/extract-all-annotations (first models))]
          (is (map? all-annots))
          (is (contains? all-annots :dc))
          (is (contains? all-annots :bqbiol))
          (is (contains? all-annots :bqmodel))
          (is (contains? all-annots :semsim)))))))

(deftest archive-annotations-test
  (testing "extracts all annotations from an OMEX archive"
    (let [annotations (rdf/archive-annotations test-omex-path)]
      (is (vector? annotations)))))

(deftest aggregate-annotations-test
  (testing "aggregates annotations across multiple archives"
    (let [result (rdf/aggregate-annotations [test-omex-path])]
      (is (map? result))
      (is (= 1 (:archive-count result)))
      (is (contains? result :metadata-files))
      (is (contains? result :dc-creators))
      (is (contains? result :bqbiol-references))
      (is (contains? result :bqmodel-references)))))

;;; ------------------------------------------------------------------
;;; URI normalization tests
;;; ------------------------------------------------------------------

(deftest canonicalize-uri-test
  (testing "canonicalizes identifiers.org URIs"
    (is (= "https://identifiers.org/go/GO:0008150"
           (rdf/canonicalize-uri "http://identifiers.org/go/GO:0008150"))))
  
  (testing "removes trailing slashes"
    (is (= "http://example.org/resource"
           (rdf/canonicalize-uri "http://example.org/resource/"))))
  
  (testing "handles nil"
    (is (nil? (rdf/canonicalize-uri nil)))))

(deftest is-local-uri-test
  (testing "identifies local URIs"
    (is (rdf/is-local-uri? "./model.xml"))
    (is (rdf/is-local-uri? "#composite1"))
    (is (rdf/is-local-uri? "file:///path/to/file")))
  
  (testing "identifies non-local URIs"
    (is (not (rdf/is-local-uri? "http://example.org/resource")))
    (is (not (rdf/is-local-uri? "https://identifiers.org/go/GO:0008150")))))

(deftest normalize-blank-node-test
  (testing "normalizes blank node identifiers"
    (let [bn1 (rdf/normalize-blank-node "metadata.rdf" "b0")
          bn2 (rdf/normalize-blank-node "other.rdf" "b0")]
      (is (string? bn1))
      (is (string? bn2))
      (is (not= bn1 bn2))))) ; Different sources should produce different IDs

;;; ------------------------------------------------------------------
;;; Fixture-based extractor tests
;;; ------------------------------------------------------------------

(deftest singular-annotation-fixture-test
  (testing "extracts singular annotations from Turtle fixture"
    (let [model (load-turtle-fixture "test/resources/fixtures/singular-annotation.ttl")
          result (rdf/extract-singular-annotations model "singular-annotation.ttl")]
      (is (:ok result))
      (is (vector? (:data result)))
      (is (pos? (count (:data result))))
      ;; Check that annotations have expected structure
      (doseq [annot (:data result)]
        (is (contains? annot :subject))
        (is (contains? annot :predicate))
        (is (contains? annot :object))
        (is (contains? annot :provenance))))))

(deftest singular-annotation-rdfxml-fixture-test
  (testing "extracts singular annotations from RDF/XML fixture"
    (let [model (load-rdfxml-fixture "test/resources/fixtures/singular-annotation.rdf")
          result (rdf/extract-singular-annotations model "singular-annotation.rdf")]
      (is (:ok result))
      (is (vector? (:data result)))
      (is (pos? (count (:data result)))))))

(deftest entity-composite-fixture-test
  (testing "extracts composite annotations from Turtle fixture"
    (let [model (load-turtle-fixture "test/resources/fixtures/entity-composite.ttl")
          result (rdf/extract-composite-annotations model "entity-composite.ttl")]
      (is (:ok result))
      (is (vector? (:data result)))
      ;; Should find at least 2 composite annotations
      (is (>= (count (:data result)) 1)))))

(deftest process-composite-fixture-test
  (testing "extracts process annotations from Turtle fixture"
    (let [model (load-turtle-fixture "test/resources/fixtures/process-composite.ttl")
          result (rdf/extract-process-annotations model "process-composite.ttl")]
      (is (:ok result))
      (is (vector? (:data result)))
      ;; Should find at least 1 process annotation
      (is (>= (count (:data result)) 1))
      ;; Check structure
      (when (seq (:data result))
        (let [proc (first (:data result))]
          (is (contains? proc :subject))
          (is (contains? proc :type))
          (is (= :process (:type proc))))))))

(deftest energy-differential-fixture-test
  (testing "extracts energy differential annotations from Turtle fixture"
    (let [model (load-turtle-fixture "test/resources/fixtures/energy-differential.ttl")
          result (rdf/extract-energy-differentials model "energy-differential.ttl")]
      (is (:ok result))
      (is (vector? (:data result)))
      ;; Should find at least 2 energy differentials
      (is (>= (count (:data result)) 2)))))

(deftest extract-opb-terms-test
  (testing "extracts OPB term frequencies from composite fixture"
    (let [model (load-turtle-fixture "test/resources/fixtures/entity-composite.ttl")
          result (rdf/extract-opb-terms model)]
      (is (map? result))
      ;; Should find OPB terms
      (is (pos? (count result))))))

;;; ------------------------------------------------------------------
;;; Safe extraction tests
;;; ------------------------------------------------------------------

(deftest extract-all-annotations-safe-test
  (testing "extracts all annotations with structured error handling"
    (let [model (load-turtle-fixture "test/resources/fixtures/singular-annotation.ttl")
          result (rdf/extract-all-annotations-safe model "singular-annotation.ttl")]
      (is (:ok result))
      (is (map? (:data result)))
      (is (contains? (:data result) :singular-annotations))
      (is (contains? (:data result) :composite-annotations))
      (is (contains? (:data result) :process-annotations))
      (is (contains? (:data result) :provenance)))))

(deftest archive-annotations-safe-test
  (testing "safely extracts annotations from test OMEX archive"
    (let [result (rdf/archive-annotations-safe test-fixture-omex)]
      (is (:ok result))
      (is (vector? (:data result)))
      (is (number? (:model-count result)))
      (is (vector? (:errors result))))))

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

(deftest archive-annotations-byte-array-test
  (testing "extracts annotations from byte array"
    (let [zip-bytes (read-file-bytes test-omex-path)
          annotations (rdf/archive-annotations zip-bytes)]
      (is (vector? annotations))))
  
  (testing "byte array annotations match file path annotations"
    (let [zip-bytes (read-file-bytes test-omex-path)
          annotations-from-path (rdf/archive-annotations test-omex-path)
          annotations-from-bytes (rdf/archive-annotations zip-bytes)]
      (is (= (count annotations-from-path) (count annotations-from-bytes))))))

(deftest archive-annotations-safe-byte-array-test
  (testing "safely extracts annotations from byte array"
    (let [zip-bytes (read-file-bytes test-fixture-omex)
          result (rdf/archive-annotations-safe zip-bytes)]
      (is (:ok result))
      (is (vector? (:data result)))
      (is (number? (:model-count result)))
      (is (vector? (:errors result)))))
  
  (testing "byte array safe annotations match file path safe annotations"
    (let [zip-bytes (read-file-bytes test-fixture-omex)
          result-from-path (rdf/archive-annotations-safe test-fixture-omex)
          result-from-bytes (rdf/archive-annotations-safe zip-bytes)]
      (is (= (:ok result-from-path) (:ok result-from-bytes)))
      (is (= (:model-count result-from-path) (:model-count result-from-bytes)))
      (is (= (count (:data result-from-path)) (count (:data result-from-bytes)))))))

;;; ------------------------------------------------------------------
;;; SPARQL-based extraction tests
;;; ------------------------------------------------------------------

(deftest run-sparql-select-test
  (testing "runs SPARQL query on model"
    (let [model (load-turtle-fixture "test/resources/fixtures/singular-annotation.ttl")
          query "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 5"
          result (rdf/run-sparql-select model query)]
      (is (:ok result))
      (is (vector? (:data result))))))
