(ns schedn.distance)

;performance sensitive namespace
(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

;not using `defmacro` because I'm not really transforming any code
(definline ^:private dist-step [pred d index m-weight]
  `(let [[i# j# :as idx#] ~index]
     (assoc! ~d idx#
             (cond (zero? (min i# j#)) (max i# j#)
                   (~pred i# j#) (get ~d [(dec i#) (dec j#)])
                   :else (min
                           (inc (get ~d [(dec i#) j#])) ;+1 cost for deletion
                           (inc (get ~d [i# (dec j#)])) ;+1 cost for insertion
                           (+ ~m-weight (get ~d [(dec i#) (dec j#)])))))))

(defn levenshtein-distance
  "Calculates the 'Levenshtein-distance' between two Strings using efficient bottom-up dynamic programming.
   If s1 & s2 are of equal length and m-weight = 1, the 'Hamming-distance' is essentially calculated."
  ([^String s1 ^String s2 m-weight] ; m-weight = modification/substitution penalty
   (let [m (.length s1)
         n (.length s2)
         pred (fn [i j]
                (=
                  (.charAt s1 (dec i))
                  (.charAt s2 (dec j))))
         step #(dist-step pred %1 %2 m-weight)
         distance-matrix  (reduce step (transient {}) (for [i (range (inc m))
                                                            j (range (inc n))]
                                                        [i j]))]
     (get distance-matrix [m n]))) ;; no need to call `persistent!` here - the fn finished!
  ([^String s1 ^String s2]
   (levenshtein-distance s1 s2 2)))  ;2 cost for substitution usually (per Juramfky's lecture-slides and book)

(defn fuzzy-match
  "Fuzzy String matching that filters according to some levenshtein-distance <tolerance>. Lazy (per `filter`)."
  ([s ss tolerance m-weight]
   (assert (every? string? (conj ss s)) "Fuzzy-matching is only supported on instances of java.lang.String!")
   (filter #(<= (levenshtein-distance s % m-weight) tolerance) ss))
  ([s ss tolerance]
   (fuzzy-match s ss tolerance 2))
  ([s ss]
   (fuzzy-match s ss 5))
  ([ss]
   (fuzzy-match (first ss) (rest ss))))
