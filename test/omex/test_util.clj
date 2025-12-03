(ns omex.test-util
  "Shared utilities for tests."
  (:require [clojure.java.io :as cio]))

(defn read-file-bytes
  "Read a file into a byte array."
  [path]
  (let [file (cio/file path)
        bytes (byte-array (.length file))]
    (with-open [in (java.io.FileInputStream. file)]
      (.read in bytes))
    bytes))
