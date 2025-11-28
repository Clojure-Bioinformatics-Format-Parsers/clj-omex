(ns omex
  (:require [clojure.data.zip :as z]
            [clojure.data.zip.xml :as x]
            [clojure.zip :as zip]
            [clojure.xml :as xml]))

(defn list-zip-entries [^String zip-path]
  (with-open [zip-file (java.util.zip.ZipFile. zip-path)]
    (let [entries (enumeration-seq (.entries zip-file))]
      (mapv (fn [^java.util.zip.ZipEntry entry]
              {:name            (.getName entry)
               :size            (.getSize entry)
               :compressed-size (.getCompressedSize entry)
               :is-directory?   (.isDirectory entry)
               :last-modified   (.getTime entry)})
           entries))))

(defn extract-entry-into-memory
  [^String zip-path ^String entry-name]
  (with-open [zip-file (java.util.zip.ZipFile. zip-path)]
    (let [entry (.getEntry zip-file entry-name)]
      (when entry
        (with-open [in-stream (.getInputStream zip-file entry)
                    baos (java.io.ByteArrayOutputStream.)]
          (clojure.java.io/copy in-stream baos)
          (.toByteArray baos))))))


(defn xml-zipper-from-string [s]
  (-> (java.io.StringReader. s)
      (org.xml.sax.InputSource.)
      (xml/parse)
      (zip/xml-zip)))

(list-zip-entries "resources/Beard, 2005.omex")

(def cellml (String. (extract-entry-into-memory "resources/Beard, 2005.omex" "beard_2005.cellml")))

(def zippy (xml-zipper-from-string cellml))

(-> zippy
    z/descendants)
