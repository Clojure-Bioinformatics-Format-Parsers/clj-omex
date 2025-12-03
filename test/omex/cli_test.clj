(ns omex.cli-test
  "Tests for omex.cli namespace."
  (:require [clojure.test :refer [deftest testing is]]
            [omex.cli :as cli]
            [clojure.string :as str]))

(def test-omex-path "test/resources/Beard, 2005.omex")
(def test-fixture-omex "test/resources/fixtures/test-archive.omex")

(deftest to-json-str-test
  (testing "converts primitives to JSON"
    (is (= "null" (#'cli/to-json-str nil)))
    (is (= "42" (#'cli/to-json-str 42)))
    (is (= "true" (#'cli/to-json-str true)))
    (is (= "\"hello\"" (#'cli/to-json-str "hello"))))
  
  (testing "converts maps to JSON"
    (let [json (#'cli/to-json-str {:a 1 :b "test"})]
      (is (str/includes? json "\"a\": 1"))
      (is (str/includes? json "\"b\": \"test\""))))
  
  (testing "converts vectors to JSON"
    (is (= "[1, 2, 3]" (#'cli/to-json-str [1 2 3])))))

(deftest stats-cmd-output-test
  (testing "stats command produces output for valid archive"
    (let [output (with-out-str (cli/stats-cmd [test-omex-path]))]
      (is (str/includes? output "source"))
      (is (str/includes? output "entry-count")))))

(deftest extract-cmd-output-test
  (testing "extract command produces output for valid archive"
    (let [output (with-out-str (cli/extract-cmd [test-fixture-omex]))]
      (is (str/includes? output "ok"))
      (is (str/includes? output "source-path")))))

(deftest manifest-cmd-output-test
  (testing "manifest command produces output for valid archive"
    (let [output (with-out-str (cli/manifest-cmd [test-omex-path]))]
      (is (str/includes? output "ok"))
      (is (str/includes? output "manifest")))))

(deftest help-cmd-test
  (testing "help command outputs usage information"
    (let [output (with-out-str (cli/help-cmd nil))]
      (is (str/includes? output "clj-omex"))
      (is (str/includes? output "Commands"))
      (is (str/includes? output "stats"))
      (is (str/includes? output "extract")))))

(deftest run-test
  (testing "run dispatches to help for no args"
    (let [output (with-out-str (cli/run nil))]
      (is (str/includes? output "Commands"))))
  
  (testing "run dispatches to help for 'help' arg"
    (let [output (with-out-str (cli/run ["help"]))]
      (is (str/includes? output "Commands"))))
  
  (testing "run handles unknown commands"
    (let [output (with-out-str (cli/run ["unknown-cmd"]))]
      (is (str/includes? output "Unknown command")))))
