(ns omex.rdf
  "RDF extractors for common annotation patterns in OMEX archives.
   Includes extractors for Dublin Core, BioModels qualifiers (bqbiol/bqmodel),
   and SemSim composite annotations."
  (:require [omex.io :as io])
  (:import [org.apache.jena.rdf.model Model Resource Property RDFNode]
           [org.apache.jena.vocabulary DC DCTerms]))

;;; ------------------------------------------------------------------
;;; Common RDF namespaces
;;; ------------------------------------------------------------------

(def ^:const bqbiol-ns "http://biomodels.net/biology-qualifiers/")
(def ^:const bqmodel-ns "http://biomodels.net/model-qualifiers/")
(def ^:const semsim-ns "http://www.bhi.washington.edu/semsim#")
(def ^:const ro-ns "http://www.obofoundry.org/ro/ro.owl#")

;;; ------------------------------------------------------------------
;;; Error handling helpers
;;; ------------------------------------------------------------------

(defn safe-extract
  "Wrap extraction in try-catch, returning nil on failure.
   Logs errors if a logger is provided."
  ([f] (safe-extract f nil))
  ([f error-handler]
   (try
     (f)
     (catch Exception e
       (when error-handler
         (error-handler e))
       nil))))

(defmacro with-error-handling
  "Execute body with error handling, returning default-value on exception."
  [default-value & body]
  `(try
     ~@body
     (catch Exception _#
       ~default-value)))

;;; ------------------------------------------------------------------
;;; Model query helpers
;;; ------------------------------------------------------------------

(defn- property
  "Create a Jena Property from a namespace URI and local name."
  [^Model model ^String ns-uri ^String local-name]
  (.createProperty model ns-uri local-name))

(defn- get-literal-values
  "Get all literal values for a given property from a resource."
  [^Resource resource ^Property prop]
  (with-error-handling []
    (->> (iterator-seq (.listProperties resource prop))
         (map #(.getObject ^org.apache.jena.rdf.model.Statement %))
         (filter #(.isLiteral ^RDFNode %))
         (mapv #(.getString (.asLiteral ^RDFNode %))))))

(defn- get-resource-values
  "Get all resource URIs for a given property from a resource."
  [^Resource resource ^Property prop]
  (with-error-handling []
    (->> (iterator-seq (.listProperties resource prop))
         (map #(.getObject ^org.apache.jena.rdf.model.Statement %))
         (filter #(.isResource ^RDFNode %))
         (mapv #(.getURI (.asResource ^RDFNode %))))))

;;; ------------------------------------------------------------------
;;; Dublin Core extractors
;;; ------------------------------------------------------------------

(defn extract-dc-metadata
  "Extract Dublin Core metadata from a Jena Model.
   Returns a map with :title, :creator, :description, :date, :source."
  [^Model model]
  (with-error-handling {}
    (let [subjects (iterator-seq (.listSubjects model))]
      (reduce
       (fn [acc ^Resource subj]
         (merge acc
                {:title       (first (get-literal-values subj DC/title))
                 :creator     (get-literal-values subj DC/creator)
                 :description (first (get-literal-values subj DC/description))
                 :date        (first (get-literal-values subj DCTerms/created))
                 :source      (first (get-literal-values subj DC/source))}))
       {}
       subjects))))

;;; ------------------------------------------------------------------
;;; BioModels qualifiers extractors (bqbiol, bqmodel)
;;; ------------------------------------------------------------------

(def bqbiol-qualifiers
  "Common BioModels biology qualifiers."
  ["is" "hasPart" "isPartOf" "isVersionOf" "hasVersion"
   "isHomologTo" "isDescribedBy" "isEncodedBy" "encodes"
   "occursIn" "hasProperty" "isPropertyOf" "hasTaxon"])

(def bqmodel-qualifiers
  "Common BioModels model qualifiers."
  ["is" "isDerivedFrom" "isDescribedBy" "isInstanceOf" "hasInstance"])

(defn extract-bqbiol-annotations
  "Extract BioModels biology qualifier annotations from a Jena Model.
   Returns a map keyed by qualifier name, with vectors of resource URIs."
  [^Model model]
  (with-error-handling {}
    (let [subjects (iterator-seq (.listSubjects model))]
      (reduce
       (fn [acc ^Resource subj]
         (reduce
          (fn [inner-acc qualifier]
            (let [prop (property model bqbiol-ns qualifier)
                  values (get-resource-values subj prop)]
              (if (seq values)
                (update inner-acc (keyword qualifier) (fnil into []) values)
                inner-acc)))
          acc
          bqbiol-qualifiers))
       {}
       subjects))))

(defn extract-bqmodel-annotations
  "Extract BioModels model qualifier annotations from a Jena Model.
   Returns a map keyed by qualifier name, with vectors of resource URIs."
  [^Model model]
  (with-error-handling {}
    (let [subjects (iterator-seq (.listSubjects model))]
      (reduce
       (fn [acc ^Resource subj]
         (reduce
          (fn [inner-acc qualifier]
            (let [prop (property model bqmodel-ns qualifier)
                  values (get-resource-values subj prop)]
              (if (seq values)
                (update inner-acc (keyword qualifier) (fnil into []) values)
                inner-acc)))
          acc
          bqmodel-qualifiers))
       {}
       subjects))))

;;; ------------------------------------------------------------------
;;; SemSim composite annotation extractors
;;; ------------------------------------------------------------------

(defn extract-semsim-annotations
  "Extract SemSim composite annotations from a Jena Model.
   Returns a vector of maps with :entity, :property, and :process keys
   representing composite annotations."
  [^Model model]
  (with-error-handling []
    (let [has-physical-entity (property model semsim-ns "hasPhysicalEntity")
          has-physical-property (property model semsim-ns "hasPhysicalProperty")
          part-of (property model ro-ns "part_of")
          subjects (iterator-seq (.listSubjects model))]
      (->> subjects
           (mapcat
            (fn [^Resource subj]
              (let [entities (get-resource-values subj has-physical-entity)
                    properties (get-resource-values subj has-physical-property)
                    part-of-rels (get-resource-values subj part-of)]
                (when (or (seq entities) (seq properties))
                  [{:subject    (.getURI subj)
                    :entities   entities
                    :properties properties
                    :part-of    part-of-rels}]))))
           (filterv some?)))))

;;; ------------------------------------------------------------------
;;; Convenience functions for OMEX archives
;;; ------------------------------------------------------------------

(defn extract-all-annotations
  "Extract all annotation types from a single Jena Model.
   Returns a map with :dc, :bqbiol, :bqmodel, and :semsim keys."
  [^Model model]
  {:dc      (extract-dc-metadata model)
   :bqbiol  (extract-bqbiol-annotations model)
   :bqmodel (extract-bqmodel-annotations model)
   :semsim  (extract-semsim-annotations model)})

(defn archive-annotations
  "Extract all annotations from all metadata files in an OMEX archive.
   Returns a vector of annotation maps, one per metadata file."
  [^String omex-path]
  (with-error-handling []
    (->> (io/load-metadata-models omex-path)
         (mapv extract-all-annotations))))

(defn aggregate-annotations
  "Aggregate annotations across multiple OMEX archives.
   Returns a map with counts and collected annotations."
  [omex-paths]
  (with-error-handling {:error "Failed to aggregate annotations"}
    (let [all-annotations (mapcat archive-annotations omex-paths)]
      {:archive-count (count omex-paths)
       :metadata-files (count all-annotations)
       :dc-creators (->> all-annotations
                         (mapcat #(get-in % [:dc :creator]))
                         (filterv some?)
                         distinct
                         vec)
       :bqbiol-references (->> all-annotations
                               (map :bqbiol)
                               (mapcat vals)
                               flatten
                               distinct
                               vec)
       :bqmodel-references (->> all-annotations
                                (map :bqmodel)
                                (mapcat vals)
                                flatten
                                distinct
                                vec)})))
