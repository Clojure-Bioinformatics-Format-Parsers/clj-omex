(ns omex.rdf-test
  "Tests for omex.rdf namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [omex.rdf :as rdf]
            [omex.io :as io]))

(def test-omex-path "test/resources/Beard, 2005.omex")

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
