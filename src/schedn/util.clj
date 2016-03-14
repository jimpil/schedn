(ns schedn.util
  (:require [clojure.core.incubator :refer [dissoc-in]]
            [schema
             [core :as s]
             [utils :as sut]])
  (:import (java.text SimpleDateFormat)))

;; KITCHEN SINK
;; ============

(defn extract-paths
  "Given a map <m>, returns all the possible paths to the leaves.
   A map entry nests, whenever the value is itself a map."
  [m]
  (when (map? m)
    (vec (mapcat (fn [[k v]]
                   (let [nested (->> v
                                     (extract-paths)
                                     (filter seq)
                                     (map (partial apply conj [k])))]
                     (if (seq nested)
                       nested
                       [[k]])))
                 m))
    )
  )


(defn dissoc-paths [m paths]
  (reduce dissoc-in m paths))

(defn fmap [m f & args]
  (persistent!
    (reduce-kv #(assoc! %1 %2 (apply f %3 args))
               (transient {}) m)))

(defn- filter-schema-keys
  [m schema-keys extra-keys-walker]
  (reduce-kv (fn [m k v]
               (if (or (contains? schema-keys k)
                       (and extra-keys-walker
                            (not (sut/error? (extra-keys-walker k)))))
                 m
                 (dissoc m k)))
             m
             m))

(defn map-filter-matcher
  "A coercing matcher for removing extra stuff (not defined in the schema) from maps."
  [s]
  (when (map? s)
    (let [extra-keys-schema (#'s/find-extra-keys-schema s)
          extra-keys-walker (when extra-keys-schema
                              (s/checker extra-keys-schema))
          explicit-keys (some->> (dissoc s extra-keys-schema)
                                 keys
                                 (map s/explicit-schema-key)
                                 (into #{}))]
      (when (or extra-keys-walker (seq explicit-keys))
        (fn [x]
          (if (map? x)
            (filter-schema-keys x explicit-keys extra-keys-walker)
            x))))))


(defn extract-identifiers [ids msg]
  (let [prefix (first ids)
        prefix (when (string? prefix)
                 prefix)
        ids* (cond-> ids prefix rest)]
    (->> ids*
         (map (partial get-in msg))
         (cons (when prefix prefix))
         vec)))

(defn concat-identifiers [ids msg]
  (clojure.string/join \- (extract-identifiers ids msg)))

(defn refine-schema [s refinements]
  (reduce s/constrained s refinements))

(def string->fn
  (comp var-get resolve))

(defn schema-constraints [config other]
  (some->> config
           :schema-constraints
           (mapcat (fn [[where fns]]
                     (case where
                       :on-self (map string->fn fns)
                       :on-other (map (fn [f]
                                        (fn [self]
                                          (f self other)))
                                      (map string->fn fns)))))))


(def ^SimpleDateFormat rfc-3339-formatter
  ;; http://docs.oracle.com/javase/8/docs/api/java/text/SimpleDateFormat.html#iso8601timezone
  (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ssXXX"))

(defn parse-rfc-3339-date-str [^String s]
  (when (.parse rfc-3339-formatter s)
    true))


