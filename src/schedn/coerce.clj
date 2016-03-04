(ns schedn.coerce
  (:require [schema
             [coerce :as coe]
             [core :as sch]]
            [schedn
             [util :as ut]
             [built-ins :as ready]])
  (:import [java.util Map]))

(defn- safe-num-cast
  "Returns a safe coersing fn using the specified <cast-fn>.
   Adopted from `schema.coerce/safe-long-cast`"
  [cast-fn]
  (coe/safe
    (fn [x]
      (assert (number? x) (str x " is NOT a number!"))
      (let [l (cast-fn x)]
        (if (== l x)
          l
          x)))))

(def integer-coercions "safe integer coercions"
  (zipmap [sch/Int ready/PosInt ready/PosIntOrZero]
          (repeat (safe-num-cast long))))

(def double-coercions "safe double coercions"
  (zipmap [Double ready/PosDouble ready/PosDoubleOrZero]
          (repeat (safe-num-cast double))))

(def coerce-boolean
  "Coerces booleans from either strings or keywords."
  (coe/safe
    (fn [b]
      (cond
        (string? b) (if (or (= b "true")
                            (= b "false")) ;;`parseBoolean` only checks for "true"/"TRUE" !
                      (Boolean/parseBoolean b)
                      b)
        (keyword? b) (recur (name b))
        (symbol? b) (recur (str b))
        :else b))))

(def boolean-coercions
  {sch/Bool coerce-boolean})

(def coerce-string
  (coe/safe
    (fn [x]
      (cond
        (symbol? x) (str x)
        (keyword? x) (name x)
        :else x))))

(def string-coercions
  {sch/Str coerce-string})

(def remove-extra
  {Map ut/map-filter-matcher})


