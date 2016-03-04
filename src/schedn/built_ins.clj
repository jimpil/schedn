(ns schedn.built-ins
  (:require [schema.core :as sch]))

;; some ready-made schemas follow:

(def Nil
  "A schema predicate for a nil value"
  (sch/pred nil? 'nil?))

(def PosInt
  "A schema predicate for  positive integers"
  (sch/conditional integer? (sch/pred pos? 'positive-integer?)))

(def PosDouble
  "A schema predicate for positive doubles"
  (sch/conditional #(instance? Double %) (sch/pred pos? 'positive-double?)))

(def PosNum
  "A schema predicate for positive numbers"
  (sch/conditional number? (sch/pred pos? 'positive-number?)))

(def Zero
  "A schema predicate for zeros"
  (sch/pred zero? 'zero?))

(defn wrap-zeroable [s]
  (sch/if zero? Zero s))

(def PosIntOrZero
  "A schema predicate for positive integers OR 0"
  (wrap-zeroable PosInt))

(def PosDoubleOrZero
  "A schema predicate for positive doubles OR 0"
  (wrap-zeroable PosDouble))

(def PosNumOrZero
  "A schema predicate for positive integers OR 0"
  (wrap-zeroable PosNum))

(def ListLikeStructure
  "A schema for list-like data-structures. This includes anything `sequential?` + persistent sets."
  (sch/pred #(or (sequential? %)
                 (set? %)) 'list-like-structure?))

(def PersistenMap
  "a schema predicate for clojure maps"
  (sch/pred map? 'peristent-map?))

(defn member-of
  "A function for creating schema predicate to test membership of some item against some set <s>."
  [s]
  (sch/pred (partial contains? s) 'is-contained?))