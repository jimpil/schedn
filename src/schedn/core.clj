(ns schedn.core
  (:require [schema
             [core :as sch]
             [coerce :as coe]
             [utils :as sch-utils]
             [macros :as sch-macros]]
            [schedn
             [util :as ut]
             [coerce :as coercions]]
            [schedn.distance :as distance])
  (:import [schema.utils NamedError ValidationError]))


(defn- warn-on-extra-keys
  "Default, non-invasive, reaction for excess keys in configuration maps.
  <fuzzy-match> should be nil or a function capable of non-exact matching of strings (e.g. text-utils/fuzzy-match).
  This allows us to potentially provide helpful hints in the error message produced (i.e. misspellings)."
  ([component-description fuzzy-match paths]
   (let [warn-str  "[%s POTENTIAL MISCONFIGURATION] - Detected unused path %s !"]
     (doseq [path paths]
       (let [config-key (last path)
             config-key (cond-> config-key
                                (keyword? config-key) name)
             matches (when fuzzy-match
                       (->> config-key
                            fuzzy-match
                            (remove #{config-key})
                            vec))]
         (if (seq matches)
           (println (format (str warn-str "Did you mean %s ?") component-description path matches))
           (println (format warn-str component-description path)))))))
  ([component-description ks]
   (warn-on-extra-keys component-description nil ks)))

(def ^:dynamic default-coercions
  (merge coercions/integer-coercions
         coercions/double-coercions
         coercions/boolean-coercions
         coercions/string-coercions
         ;coercions/remove-extra
         ))

(defn default-coercions-matcher
  "A matcher that applies all our default coercions."
  [schema]
  (get default-coercions schema))

(defn- construct-mandatory-or-required [key-status mandatory-guard key-name]
  (case key-status
    :mandatory (sch/required-key key-name)
    :optional (if (some #(contains? (set %) key-name) mandatory-guard)
                (sch/required-key key-name)
                (sch/optional-key key-name))
    :conditional (construct-mandatory-or-required :optional mandatory-guard key-name)
    nil (sch/required-key key-name) ;;default to required
    (throw (IllegalArgumentException. (str "Unrecognised key-status [" key-status "]. Either :optional, :mandatory or :conditional are supported...")))))

(defn- make-schema-path
  "Constructs a vector of keys (a path), where the last key is wrapped in a #schema.core.[Optional/Required]Key."
  [path mandatory-guard key-status]
  (if (seq path)
    (map (partial construct-mandatory-or-required key-status mandatory-guard) path)
    path))


(defn- group-template-paths-according-to-presence
  [template]
  (group-by last (keys template)))


;===================================================<PUBLIC API>========================================================
(defn template->schema
  "Convert a schema template to an actual schema. A schema template is just like a schema (a map),
   but with `[[& key-names] :required/:mandatory]` for keys."
  [template]
  (let [{optionals :optional
         mandatory :mandatory} (group-template-paths-according-to-presence template)]
    (reduce (fn [m [path status :as template-entry]]
              (assoc-in m
                        (make-schema-path path (map first mandatory) status)
                        (let [leaf-schema (get template template-entry)]
                          (cond-> leaf-schema
                                  (symbol? leaf-schema) (-> resolve var-get)))))
            {}
            (keys template)))
  )

(defn- find-disallowed-paths
  "Given a map, return all the paths whose value is 'disallowed-key (clojure.lang.Symbol)."
  [error-map]
  (->> error-map
       ut/extract-paths
       (filter #(= 'disallowed-key (get-in error-map %)))))

(defn- unpack-error [error]
  "Helper for extracting the actual value that failed out of ValidationError/NamedError objects."
  (condp instance? error
    ValidationError {:error (sch-utils/validation-error-explain error)}
    NamedError (unpack-error (.error ^NamedError error))
    error))

(defn validate-data-against-schema
  "An alternative for schema.core/validate which integrates coercions and separates validation for extra keys (i.e. non-invasive).
  A custom <react-for-extra!> fn should accept 1 argument (the extra keys found) and should be side-effecting."
  ([data component-description react-for-extra! schema]
   (let [coerce-and-check (coe/coercer schema default-coercions-matcher)
         validation-outcome (coerce-and-check data)]
     (if-let [error-container (some-> validation-outcome
                                      sch-utils/error-val
                                      unpack-error)]
       (let [disallowed-paths (find-disallowed-paths error-container)
             true-errors (ut/dissoc-paths error-container disallowed-paths)]
         (when (and react-for-extra! ;;just a precaution
                    (seq disallowed-paths))
           (react-for-extra! disallowed-paths))  ;;expecting side-effect here
         (if (seq true-errors) ;; fail proper here, exactly as `schema.core/validate` does
           (sch-macros/error! (sch-utils/format* "[%s] :: Value does not match schema: %s" component-description (pr-str true-errors))
                              {:schema schema
                               :value data
                               :error (ut/fmap true-errors unpack-error)}) ;;don't let schema specific Objects leak out of this namespace!
           ;;re-introduce the extra keys, after having 'affected the world'
           (reduce #(assoc-in %1 %2 (get-in data %2))
                   (ut/dissoc-paths data disallowed-paths)
                   disallowed-paths)))
       validation-outcome)))
  ([data component-description schema] ;; this is the arity that should be used most of, if not all, the time
   (validate-data-against-schema data component-description
                                 (partial warn-on-extra-keys
                                          component-description
                                          (fn [^String k]
                                            (distance/fuzzy-match k
                                                                  (->> data
                                                                       ut/extract-paths
                                                                       (map (comp name last)))
                                                                  (-> k .length  (/ 3) int) ;;dynamic tolerance according to word's length
                                                                  1))) ;;in a non-academic setting substitution-cost = 1 makes more sense
                                 schema)))

(def supported-template-key-statuses
  #{:mandatory :optional :invert})

(defn- invert-template-key-status [s]
  (case s
    :optional :mandatory
    :mandatory :optional
    :conditional (throw (IllegalArgumentException. "Cannot invert a :conditional path..."))))

(defn override-template-entry-status
  [[[[& template-keys] kstatus] schema] new-status]
  (assert (contains? supported-template-key-statuses new-status) (format "Unrecognised status '%'! Only % are supported..." new-status supported-template-key-statuses))
  [[(vec template-keys) (if (= new-status :invert)
                          (invert-template-key-status kstatus)
                          new-status)]
   schema])

(defn override-template-statuses
  "Use this utility fn, to override some (or :all) of the statuses, in <initial-template>.
   Supported statuses are [:mandatory :optional :invert].
   Returns the updated template."
  [initial-template new-status & keys-to-override]
  (let [override-set (set (if (= :all (first keys-to-override))
                            (map (comp first first) initial-template)
                            keys-to-override))
        groups (group-by #(contains? override-set (-> % first first)) initial-template)
        seperate (juxt #(get % true) #(get % false))
        [entries-to-override rest-entries] (seperate groups)]
    (->> entries-to-override
         (map #(override-template-entry-status % new-status))
         (concat rest-entries)
         (into {}))))

(defn remove-template-entries
  "Helper for removing entries from validation templates without having to specify the full entry.
   So instead of writing the following (with redundant information):

     `(dissoc t [[...some-path...] :optional])`

    you can write:

     `(remove-template-entries t [...some-path...])`. "
  [template & entries]
  (let [sentries (set entries)
        real-entries (->> template
                          (filter #(->> % first first (contains? sentries)))
                          (map first))]
    (apply dissoc template real-entries)))

;============================================================================
(defn- id-conditional [s id-constraint]
  (sch/conditional id-constraint s))

(defn schedn->schema
  "Converts an schedn config into a Schema."
  ([config]
   (schedn->schema config nil))
  ([config other]
   (schedn->schema config other #{:classifier :schema-constraints}))
  ([self-config the-other refinements]
   (assert (-> self-config :templates not-empty) "No templates found! Aborting...")
   (let [full-template (->> self-config :templates vals (apply merge))
         {expected :match
          ids :fragments
          :as id-info} (:classifier self-config)
         id-constraint (when (and id-info (contains? refinements :classifier))
                         (fn classifier-match? [msg]
                           (= expected (ut/extract-identifiers ids msg))))
         constraints (when (contains? refinements :schema-constraints)
                       (ut/schema-constraints self-config the-other))
         full-schema (template->schema full-template)]
     (cond-> full-schema
             (not-empty constraints) (ut/refine-schema constraints)
             id-constraint (-> (id-conditional id-constraint)
                               (with-meta {:identify (partial ut/concat-identifiers ids)})))) ;; the ID condition should come first!
    )
  )

(defn schedn->schema-no-constraints
  "Same as `schedn->schema` but ignoring constraints (refinements)."
  [config]
  (schedn->schema config nil #{}))

(defmacro with-dependent-validation ;; think request+reponse validation where validating the response needs to inspect the request
  "Helper macro for performing validation across 2 things X & Y.
   The Y is obtained by <body-producing-Y>, and it's schema is dependent on X."
  [X x-config y-config & body-producing-Y]
  `(let [self# ~X
         self-schema# (schedn->schema ~x-config)
         _# (sch/validate self-schema# self#)
         other# (do ~@body-producing-Y)
         other-schema# (schedn->schema ~y-config self#)]
     (sch/validate other-schema# other#)
     )
  )

