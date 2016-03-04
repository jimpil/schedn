(ns schedn.core-test
  (:require [clojure.test :refer :all]
            [schedn.core :refer :all]
            [schedn.built-ins :refer :all]
            [schema
             [core :as s]]
            [clojure.core.incubator :refer [dissoc-in]])
  (:import (clojure.lang ExceptionInfo)))


(deftest template->schema-tests
  (testing "conversion of schema-templates -> schemas"
    (let [template {[[:a] :optional] :...p1...
                    [[:b :c] :optional] :...p2...
                    [[:b :d] :mandatory] :...p3...
                    [[:e :f :g] :optional] :...p4...
                    [[:e :f :k :z] :optional] :...p5...}]

      (is (= {(s/optional-key :a) :...p1...
              :b {(s/optional-key :c) :...p2...
                  :d :...p3...}
              (s/optional-key :e) {(s/optional-key :f) {(s/optional-key :g) :...p4...
                                                        (s/optional-key :k) {(s/optional-key :z) :...p5...}}}}
             (template->schema template))))))


(deftest validate-data-against-schema-tests
  (let [template {[[:a] :optional] s/Str
                  [[:b :c] :optional] s/Bool
                  [[:b :d] :optional] s/Str
                  [[:e :f :g] :mandatory] s/Int
                  [[:e :f :j] :mandatory] Double
                  [[:e :f :h :k] :optional] s/Str}
        schema (template->schema template)]
    (testing "data validation supports automatic coersions"
      (is (= {(s/optional-key :a) s/Str
              (s/optional-key :b) {(s/optional-key :c) s/Bool
                                   (s/optional-key :d) s/Str}
              (s/required-key :e) {(s/required-key :f) {(s/required-key :g) s/Int
                                                        (s/required-key :j) Double
                                                        (s/optional-key :h) {(s/optional-key :k) s/Str}}}}
             (template->schema template)))

      (is (= {:b {:c true
                  :d "hello"
                  :z 'w}
              :e {:f {:g 88
                      :j 13.5
                      :h {:k "whatever"}}}}
             ))

      (validate-data-against-schema {:b {:c "true"
                                         :d "hello"
                                         :z 'w}   ;; [COMP1 POTENTIAL MISCONFIGURATION] - Detected unused path [:b :z] !

                                     :e {:f {:g (BigInteger/valueOf 88) ;;coersion will fire for this value
                                             :j 13.5M}}} ;; and this one
                                    "COMP1"
                                    schema))
    ))

(defn d-AND-x [self]
  (if (get-in self [:a :b :d])
    (some? (get-in self [:a :c :x]))
    true))

(defn approval-AND-x [self other]
  (if (= "approval" (get-in self [:z :y]))
    (some? (get-in other [:a :c :x]))
    true))


(deftest shedn->schema-tests
  (let [good-request {:a {:b {:c "C"
                              :d "D"}
                          :c {:y "Y"
                              :x "T"}}}
        bad-request (dissoc-in good-request [:a :c :x])
        request-config {:classifier {:match ["request" "C" "Y"]
                                     :fragments ["request" [:a :b :c] [:a :c :y]]}
                        :schema-constraints {:on-self ['schedn.core-test/d-AND-x]}
                        :templates {:XXX {[[:a :b :c] :mandatory] s/Str
                                          [[:a :b :d] :optional] s/Str
                                          [[:a :c :x] :conditional] s/Str
                                          [[:a :c :y] :mandatory] s/Str
                                          }}}
        request-schema (schedn->schema request-config)
        identify-request (-> request-schema meta :identify)
        ;-----------------------------------
        response-config {:classifier {:match ["response" "C" "Y"]
                                      :fragments ["response" [:a :b :c] [:a :c :y]]}
                         :schema-constraints {:on-other ['schedn.core-test/approval-AND-x]}
                         :templates {:YYY {[[:a :b :c] :mandatory] s/Str
                                           [[:a :c :y] :mandatory] s/Str
                                           [[:z :y] :mandatory] (s/enum "approval" "decline" "referral" "error")
                                           }}}
        good-response {:a {:b {:c "C"}
                           :c {:y "Y"}}
                       :z {:y "approval"}}
        response-schema-using-good-request (schedn->schema response-config good-request)
        response-schema-using-bad-request  (schedn->schema response-config bad-request)
        identify-response (-> response-schema-using-bad-request meta :identify)]
    (testing "simple conditional-presence-on-self -- success"
      (is (s/validate request-schema good-request)))

    (testing "simple conditional-presence-on-self -- fail"
      (is (thrown? ExceptionInfo (s/validate request-schema bad-request))))

    (testing "simple conditional-presence-on-other -- success"
      (is (s/validate response-schema-using-good-request good-response)))

    (testing "simple conditional-presence-on-other -- fail"
      (is (thrown? ExceptionInfo (s/validate response-schema-using-bad-request good-response))))

    (is (= "request-C-Y" (identify-request good-request)))    ;; correctly identified request
    (is (= "response-C-Y" (identify-response good-response))) ;; correctly identified response
    ))


(deftest default-coercions-tests
  (let [template {[[:a] :optional] s/Bool
                  [[:b :c] :optional] s/Bool
                  [[:b :d] :optional] s/Str
                  [[:e :f :g] :mandatory] PosIntOrZero
                  [[:e :f :j] :mandatory] Double
                  [[:e :f :h] :optional] s/Str}
        schema (template->schema template)]

    (testing "default coercions"
      (is (= {:a true
              :b {:c false
                  :d "hello"}
              :e {:f {:g 88
                      :j 13.5
                      :h "dummy"}}}

             (validate-data-against-schema {:a 'true
                                            :b {:c "false"
                                                :d 'hello}
                                            :e {:f {:g (BigInteger/valueOf 88)
                                                    :j 13.5M
                                                    :h :dummy}}}
                                           "COMP1"
                                           schema))))
    ))

(deftest override-template-statuses-tests
  (let [template {[[:a] :optional] s/Bool
                  [[:b :c] :optional] s/Bool
                  [[:b :d] :optional] s/Str
                  [[:e :f :g] :mandatory] PosIntOrZero
                  [[:e :f :j] :mandatory] Double
                  [[:e :f :h] :optional] s/Str}]
    (testing "we can invert :all statuses"
      (is (= {[[:a] :mandatory] s/Bool
              [[:b :c] :mandatory] s/Bool
              [[:b :d] :mandatory] s/Str
              [[:e :f :g] :optional] PosIntOrZero
              [[:e :f :j] :optional] Double
              [[:e :f :h] :mandatory] s/Str}
             (override-template-statuses template :invert :all)))
      ))
  (let [template {[[:a] :optional] s/Bool
                  [[:b :c] :optional] s/Bool
                  [[:b :d] :optional] s/Str
                  [[:e :f :g] :mandatory] PosIntOrZero
                  [[:e :f :j] :mandatory] Double
                  [[:e :f :h] :optional] s/Str}]
    (testing "we can override certain statuses"
      (is (= {[[:a] :optional] s/Bool
              [[:b :c] :optional] s/Bool
              [[:b :d] :optional] s/Str
              [[:e :f :g] :optional] PosIntOrZero
              [[:e :f :j] :optional] Double
              [[:e :f :h] :optional] s/Str}
             (override-template-statuses template :optional [:e :f :g] [:e :f :j])))
      ))
  )




