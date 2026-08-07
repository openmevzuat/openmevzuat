(ns openmevzuat.normalize-test
  (:require [clojure.test :refer [deftest is testing]]
            [openmevzuat.normalize :as normalize]))

(deftest decode-html-entities-decodes-named-and-numeric-forms
  (testing "named entities"
    (is (= "a & b" (normalize/decode-html-entities "a &amp; b")))
    (is (= "\"alıntı\"" (normalize/decode-html-entities "&quot;alıntı&quot;")))
    (is (= "a b" (normalize/decode-html-entities "a&nbsp;b"))))
  (testing "numeric entities in decimal and hexadecimal"
    (is (= "A" (normalize/decode-html-entities "&#65;")))
    (is (= "A" (normalize/decode-html-entities "&#x41;")))
    (is (= "Ç" (normalize/decode-html-entities "&#199;"))))
  (testing "an unrecognised entity is left alone"
    (is (= "&sinsi;" (normalize/decode-html-entities "&sinsi;")))))

(deftest strip-obvious-html-only-touches-real-markup
  (testing "line breaks and block ends become newlines"
    (is (= "a\nb" (normalize/strip-obvious-html "a<br>b")))
    (is (= "a\nb" (normalize/strip-obvious-html "a<BR/>b"))))
  (testing "comparison operators are not mistaken for tags"
    (let [text "5 < 10 ve 20 > 3"]
      (is (= text (normalize/strip-obvious-html text))))))

(deftest normalize-whitespace-unifies-line-endings-and-spaces
  (is (= "a\nb" (normalize/normalize-whitespace "a\r\nb")))
  (is (= "a\nb" (normalize/normalize-whitespace "a\rb")))
  (is (= "a b" (normalize/normalize-whitespace "a b")))
  (is (= "a b" (normalize/normalize-whitespace "a  b"))))

(deftest trim-line-trailing-spaces-keeps-line-count
  (is (= "a\nb\n" (normalize/trim-line-trailing-spaces "a  \nb\t\n")))
  (is (= "a\n\nb" (normalize/trim-line-trailing-spaces "a\n   \nb"))))

(deftest normalize-text-produces-diff-stable-output
  (testing "nil and blank input"
    (is (= "" (normalize/normalize-text nil)))
    (is (= "" (normalize/normalize-text "   \r\n  "))))
  (testing "runs of blank lines collapse to a single blank line"
    (is (= "a\n\nb" (normalize/normalize-text "a\n\n\n\n\nb"))))
  (testing "surrounding whitespace is removed"
    (is (= "Madde 1" (normalize/normalize-text "\n\n  Madde 1  \n\n"))))
  (testing "entities decode before markup is stripped"
    (is (= "Madde 1 & 2" (normalize/normalize-text "<b>Madde 1 &amp; 2</b>")))))
