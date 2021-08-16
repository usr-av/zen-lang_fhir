(ns zen.fhir.generator
  (:require [clojure.string :as str]
            [clojure.walk]
            [zen.fhir.utils :as utils]
            [com.rpl.specter :as sp]))


(defmulti ed->zen
  "Converts part of ElementDefinition to part of zen schema
  Used to dispatch all of its defmethods
  by their dispatch value as select-keys arg"
  identity)


(remove-all-methods ed->zen)  ;; To remove defmethods erased from code when re-eval a whole buffer in REPL


(def poly-id-terminator "[x]")


(defn drop-poly-terminator [id]
  (subs id 0 (- (count id) (count poly-id-terminator))))


(defn drop-poly-name [id poly-name]
  (subs id (count poly-name)))


(defn rich-parse-path [id]
  (if (str/blank? id)
    []
    (->> (str/split id #"\.")
         (mapcat
           (fn [id-part]
             (let [[key-part slice-part] (str/split id-part #":" 2)]
               (cond
                 (str/ends-with? key-part poly-id-terminator)
                 (let [poly-name (drop-poly-terminator key-part)]
                   (cond-> [{:poly poly-name}]
                     (some? slice-part) (conj {:key (drop-poly-name slice-part poly-name)
                                               :poly-name poly-name})))

                 (some? slice-part) [{:key key-part} {:slice slice-part}]
                 :else              [{:key key-part}]))))
         vec)))


(defn format-rich-path [rich-path]
  (reduce
    (fn [path-acc rich-part]
      (str path-acc
           (cond
             (:slice rich-part) (str ":" (:slice rich-part))
             (:poly rich-part)  (str "." (:poly rich-part))
             :else              (str "." (:key rich-part)))))
    (or (:key (first rich-path))
        (:slice (first rich-path)))
    (rest rich-path)))


(defn format-rich-id [rich-path]
  (reduce
    (fn [path-acc part]
      (str path-acc
           (cond
             (:slice part) (str ":" (str/replace (:slice part) #"@" "_"))
             (:poly part)  (str "." (:poly part))
             :else         (str "." (:key part)))))
    (:key (first rich-path))
    (rest rich-path)))


(defn slice-root? [id]
  (contains? (last (rich-parse-path id)) :slice))


(defn poly-root? [id]
  (contains? (last (rich-parse-path id)) :poly))


;; StructureDefinition snapshot generator has a bug
;; instead of id type it uses exension type
;; https://chat.fhir.org/#narrow/stream/179283-Da-Vinci/topic/Type.20of.20id/near/232607087
(def sd-type-ext-url "http://hl7.org/fhir/StructureDefinition/structuredefinition-fhir-type")


(defn type-coding->type [{:keys [extension code]}]
  (or (some-> (utils/code-search :url [sd-type-ext-url] extension)
              (utils/poly-get :value))
      code))


(defmethod ed->zen #{::id-processing :id :type} [{el-id :id, el-type :type}]
  (when-not (str/blank? el-id)
    (let [rich-path   (rich-parse-path el-id)
          poly-value? (some? (:poly-name (last rich-path)))
          rich-path   (if-let [poly-key (and poly-value? (some-> el-type first type-coding->type))]
                        (concat (butlast rich-path)
                                [(assoc (last rich-path) :key poly-key)])
                        rich-path)

          attr-key    (cond
                        (poly-root? el-id)
                        (some-> rich-path last :poly keyword)

                        (not (slice-root? el-id))
                        (some-> (take-last 1 rich-path)
                                format-rich-path
                                keyword))
          parent (some-> (butlast rich-path)
                            format-rich-path
                            symbol)]
      (utils/strip-nils
        {::id      (symbol (format-rich-id rich-path))
         ::key     attr-key
         ::slicing (when (:slice (last rich-path))
                     parent)
         ::parent  (when-not (:slice (last rich-path))
                     parent)}))))


(defn root-element?
  "The first ElementDefinition (root element) usually has max=* which may be treated as a collection
  but we are treating StructureDefinition as a tool to validate a single resource"
  [el-path]
  (not (str/includes? (str el-path) ".")))


(defmethod ed->zen #{:min} [{el-min :min}]
  (when el-min
    {::required? (pos? (or el-min 0))}))


(defmethod ed->zen #{:id :min :max :base}
  [{id :id, el-min :min, el-max :max, {base-max :max} :base}]
  (utils/strip-nils
    (when (and (or (some? base-max)
                   (some? el-max))
               (not (root-element? id))
               (not= "1" base-max)
               (or (some? base-max)
                   (not= "1" el-max)))
      {::collection? true
       :minItems     (when-not (= 0 el-min) el-min)
       :maxItems     (when-not (= "*" el-max) (utils/parse-int el-max))})))


(defmethod ed->zen #{} [_]
  {:zen/tags #{'zen/schema}})


(defmethod ed->zen #{:definition :short} [{:keys [short definition]}]
  (let [short      (when-not (str/blank? short) short)
        definition (when-not (str/blank? definition) definition)

        desc       (or short definition)
        #_#_full-desc  (when short definition)]
    (utils/strip-nils
      {:zen/desc  desc
       #_#_:definition full-desc})))


(def fhir-primitive->zen-primitive
  '{boolean zen/boolean

    decimal     zen/number
    integer     zen/integer
    unsignedInt zen/integer
    positiveInt zen/integer

    string       zen/string
    markdown     zen/string
    id           zen/string
    uuid         zen/string
    oid          zen/string
    uri          zen/string
    url          zen/string
    canonical    zen/string
    code         zen/string
    base64Binary zen/string
    xhtml        zen/string

    instant  zen/string
    dateTime zen/datetime
    date     zen/date
    time     zen/string})


(defn ed-type->zen-type [coding & [{::keys [fhir-lib]}]]
  (let [nm          (when (utils/nameable? fhir-lib) (name fhir-lib))
        tp          (type-coding->type coding)
        type-symbol (if (str/blank? nm)
                      (symbol tp)
                      (symbol nm tp))]
    (utils/strip-nils
      {:confirms #{type-symbol}
       :type     (when-let [zen-primitive (fhir-primitive->zen-primitive (symbol tp))]
                   zen-primitive)})))


(defn ed-types->zen-type [id ed-types {::keys [poly? fhir-lib]}]
  (if poly?
    (let [poly-key (-> id rich-parse-path last (:poly "value"))]
      {::poly {:key  (keyword poly-key)
               :keys (into {}
                           (map (fn [{code :code :as poly-type}]
                                  (assert code "Code should be defined for polymorphic type")
                                  {(keyword code) (ed-type->zen-type poly-type {::fhir-lib fhir-lib})}))
                           ed-types)}})
    (ed-type->zen-type (first ed-types) {::fhir-lib fhir-lib})))


(defmethod ed->zen #{:type :id ::fhir-lib} [{el-types :type, id :id, fhir-lib ::fhir-lib}]
  (when (seq el-types)
    (ed-types->zen-type id
                        el-types
                        #::{:poly?    (or (< 1 (count el-types))
                                       (poly-root? id))
                            :fhir-lib fhir-lib})))


(defmethod ed->zen #{:slicing :path :id} [{path :path, slicing :slicing, id :id}]
  (when slicing
    {::slicing? true}))


;; NOTE: we can't convert sliceName to keyword because
;; by fhir it is string without any constraints
;; this can create invalid edn keywords
(defmethod ed->zen #{:sliceName :type :id} [{slice-name :sliceName, el-type :type, id :id}]
  (let [rich-path (rich-parse-path id)]
    (when (:slice (last rich-path))
      (merge {::slice-name (if (= "@default" slice-name)
                             :slicing/rest
                             slice-name)}
             (when-let [tp (first el-type)]
               {:type              'zen/map
                ::confirms-profile {:urls   (:profile tp)
                                    :symbol (symbol (:code tp))}})))))

(defn pattern->zen [pattern]
  (cond
    (map? pattern)
    {:type 'zen/map
     :keys (into {}
                 (map (fn [[k v]] {k (pattern->zen v)}))
                 pattern)}

    (sequential? pattern)
    {:type 'zen/vector
     :slicing {:slices (into {}
                             (map-indexed (fn [idx pat-el]
                                            {(str "RESERVED-aidbox-array-pattern-slicing-" idx)
                                             {:filter {:engine :zen
                                                       :zen (pattern->zen pat-el)}
                                              :schema {:type 'zen/vector
                                                       :minItems 1}}}))
                             pattern)}}

    :else {:const {:value pattern}}))


#_(defmethod ed->zen #{:binding :id} [{:keys [binding id]}]
  (when (and binding (= "required" (:strength binding)))
    (let [rich-path (rich-parse-path id)
          name      (some-> (format-rich-id rich-path)
                            (str ".valueset")
                            (symbol))]
      {'aidbox-fx/valueset name
       ::binding {:url (first (str/split (:valueSet binding) #"\|" 2))
                  :name name}})))


(defmethod ed->zen #{:poly/pattern} [{:keys [pattern]}]
  (when (some? pattern)
    (pattern->zen pattern)))


(defmethod ed->zen #{:poly/fixed} [{:keys [fixed]}]
  (when (some? fixed)
    {:const {:value fixed}}))


(defn element->zen
  "Converts ElementDefinition to zen schema
  Result is without :keys field for :type zen/map,
  they will be added later after linking"
  [element & [{::keys [fhir-lib]}]]
  (into {}
        (mapcat (fn [[ks f]]
                  (let [{poly-ks "poly", rest-ks nil} (group-by (comp #{"poly"} namespace) ks)]
                    (f (into (-> element (assoc ::fhir-lib fhir-lib) (select-keys rest-ks))
                             (map (fn [poly-key]
                                    (let [pk (keyword (name poly-key))]
                                      {pk (utils/poly-get element pk)})))
                             poly-ks)))))
        (methods ed->zen)))


(defn link-schemas
  "Links schemas, results in a such map {schema-id {:links #{schema-ids}, schema ...} ...}"
  [schemas]
  (let [links (group-by ::parent schemas)]
    (into {}
          (map (fn [{:as schema ::keys [id]}]
                 {id (assoc schema ::links (->> (get links id) (map ::id) set))}))
          schemas)))


;; TODO: for better solution filter.zen.confirms
;; should be separate simple schema without validation only for data distincion
(defn build-slice [schema]
  (let [schema-id      (::id schema)
        element-schema (symbol (str (name schema-id) ".*"))]
    {(::slice-name schema)
     {:filter {:engine :proto.zen.core/zen
               :zen    {:confirms #{element-schema}}}
      :schema {:confirms #{schema-id}}}}))


(defn build-slices [schemas schema]
  (if (::slicing? schema)
    (let [slices  (into {}
                       (comp (filter (comp #{(::id schema)} ::slicing))
                             (map build-slice))
                       (vals schemas))
          slicing {:slices (dissoc slices :slicing/rest)
                   :rest   (:schema (:slicing/rest slices))}]
      (assoc schema :slicing (utils/strip-nils slicing)))
    schema))


(defn build-require [linked-schema]
  (when (::required? linked-schema)
    (::key linked-schema)))


(defn build-key [linked-schema]
  (when-not (::slice-name linked-schema)
    {(::key linked-schema)
     {:confirms #{(::id linked-schema)}}}))


(defn build-poly [linked-schema]
  (when-let [poly (::poly linked-schema)]
    {(:key poly) {:type 'zen/map
                  :exclusive-keys #{(set (keys (:keys poly)))}
                  :keys (:keys poly)}}))


(def merge-attrs (partial merge-with (partial merge-with into)))


(defn build-schema [schemas [schema-id schema]]
  (cond
    (::collection? schema)
    (let [collection-keys [::collection? ::slicing? :minItems :maxItems #_:zen/desc]
          schema-id*      (symbol (str (name (::id schema)) ".*"))
          coll            {schema-id
                           (->> (merge (select-keys schema collection-keys)
                                       (select-keys schema [::id])
                                       {:zen/tags #{'zen/schema}
                                        :type     'zen/vector
                                        :every    {:confirms #{schema-id*}}})
                                (build-slices schemas))}
          coll-el         (build-schema schemas [schema-id* (apply dissoc schema collection-keys)])]
      (merge coll coll-el))

    (seq (::links schema))
    (let [linked-schemas (map schemas (::links schema))
          requires       (into #{} (keep build-require) linked-schemas)
          linked-attrs   (into {} (keep build-key) linked-schemas)
          poly-attrs     (into {} (keep build-poly) linked-schemas)
          schema-attrs   (get-in schemas [(::id schema) :keys])
          attrs          (merge-attrs linked-attrs poly-attrs schema-attrs)
          built-schema   (utils/assoc-some
                           schema
                           :type 'zen/map
                           :require        (not-empty requires)
                           :keys           (not-empty attrs))]
      {schema-id built-schema})

    :else
    {schema-id schema}))


#_(defn build-valueset [[schema-id schema]]
  (cond-> {schema-id schema}
    (::binding schema)
    (assoc (get-in schema [::binding :name])
           {:zen/tags #{'aidbox-fx/valueset-definition 'aidbox-fx/legacy-fhir-valueset-definition}
            :engine   :legacy-fhir-valueset
            :valueset-url (get-in schema [::binding :url])})))


(defn build-schemas
  "Builds :keys for each :type zen/map schema & schema.path.* for each :type zen/vector"
  [schemas]
  (into {}
        (comp
          #_(mapcat build-valueset)
          (mapcat (partial build-schema schemas)))
        schemas))


(defn fold-schemas [schemas]
  (->> schemas
       keys
       (sort (comp - compare))
       (reduce
         (fn [acc schema-id]
           (clojure.walk/postwalk
             (fn [x]
               (if (and (map? x) (contains? (:confirms x) schema-id))
                 (utils/safe-merge-with-into
                   (utils/disj-key x :confirms schema-id)
                   (-> (get acc schema-id) (utils/disj-key :zen/tags 'zen/schema)))
                 x))
             acc))
         schemas)))


(defn sd->profile-schema
  "Creates zen schema for root resource from StructureDefinition"
  [{:keys [description type url kind baseDefinition]} {::keys [fhir-lib elements-mode]}]
  (let [base (some-> (when-not (str/blank? baseDefinition) baseDefinition)
                     (str/split #"/")
                     last)
        fhir-base? (str/starts-with? (str baseDefinition) "http://hl7.org/fhir/StructureDefinition")]
    (utils/strip-nils
      (merge-with into
                  {::collection?       false ;; in some profiles there is * cardinality for the root resource
                   :zen/tags           #{'zen/schema 'fhir/profile}
                   :zen/desc           description
                   :type               'zen/map
                   :format             :aidbox
                   :profile-definition url}
                  (when (and (= :differential elements-mode)
                             (not (str/blank? base))
                             fhir-base?)
                    {:confirms #{(if (some? fhir-lib)
                                   (symbol (name fhir-lib) base)
                                   (symbol base))}})
                  (when (= :snapshot elements-mode)
                    {:validation-type :open})
                  (when (= "complex-type" kind)
                    {:zen/tags #{'fhir/complex-type}})
                  (when (= "resource" kind)
                    {:zen/tags #{'fhir/resource}
                     :severity "supported"
                     :resourceType type
                     :keys         {:resourceType {:type 'zen/string, :const {:value type}}}})))))


(defn order-zen-ns [zen-ns-map]
  (let [zen-ns             (get zen-ns-map 'ns)
        zen-import         (get zen-ns-map 'import)
        rest-zen-ns-map    (dissoc zen-ns-map 'ns 'import)
        ordered-zen-ns-map (cond->> (sort-by key rest-zen-ns-map)
                             (some? zen-import) (cons ['import zen-import])
                             (some? zen-ns)     (cons ['ns zen-ns])
                             :always            flatten
                             :always            (apply array-map))]
    ordered-zen-ns-map))


(defn structure-definition->zen-ns
  "Converts StructureDefinition to zen namespace
  :fold-schemas? true -- to inline attributes into single schema
  :elements-mode :snapshot|:differential to choose where elements are located"
  [zen-lib resource & [{:keys [fold-schemas?
                               elements-mode
                               fhir-lib]
                        :or {elements-mode :snapshot
                             fold-schemas? false}}]]
  (let [resource-type   (symbol (:type resource))
        zen-ns          (symbol (str (name zen-lib) "." (:id resource)))
        elements-key    elements-mode
        element-schemas (->> (get-in resource [elements-key :element])
                             (map #(element->zen % {::fhir-lib fhir-lib}))
                             link-schemas)
        resource-schema (sd->profile-schema resource #::{:elements-mode elements-mode
                                                         :fhir-lib fhir-lib})
        schemas         (update element-schemas resource-type utils/safe-merge-with-into resource-schema)]
    (-> schemas
        build-schemas
        (cond-> fold-schemas? (-> fold-schemas (select-keys [resource-type])))
        (assoc 'ns     zen-ns
               'import #{'fhir})
        (cond-> (some? fhir-lib)
          (update 'import conj fhir-lib))
        order-zen-ns)))


(defn get-deps-urls [core-ns]
  (->> core-ns
       :snapshot
       :element
       (mapcat :type)
       (mapcat :profile)
       (remove nil?)
       set))


(defn resolve-confirms-profile [zen-lib deps-resources]
  (fn [{::keys [confirms-profile] :as sch}]
    (if-let [dep (and confirms-profile (some deps-resources (:urls confirms-profile)))]
      (assoc sch :confirms #{(symbol (str (name zen-lib) \. (:id dep) \/ (:symbol confirms-profile)))})
      sch)))


(defn collect-imports [zen-lib core-ns deps-resources]
  (->> core-ns
       (sp/select [sp/MAP-VALS map? ::confirms-profile])
       (map (fn [confirms-profile]
              {:dep-resource (some deps-resources (:urls confirms-profile))
               :symbol       (:symbol confirms-profile)}))
       (filter :dep-resource)
       (map #(symbol (str (name zen-lib) \. (get-in % [:dep-resource :id]))))))


(defn resolve-deps [zen-lib core-ns deps-resources]
  (let [deps-imports (collect-imports zen-lib core-ns deps-resources)
        resolved-ns  (sp/transform [sp/MAP-VALS map? #(contains? % ::confirms-profile)]
                                   (resolve-confirms-profile zen-lib deps-resources)
                                   core-ns)]
    (update resolved-ns 'import into deps-imports)))


(defn remove-gen-keys [zen-projects]
  (let [this-file-ns (namespace ::_)]
    (clojure.walk/postwalk
      (fn [x]
        (when-not (and (instance? clojure.lang.MapEntry x)
                       (instance? clojure.lang.Named (key x))
                       (= this-file-ns (namespace (key x))))
          x))
      zen-projects)))


(defn structure-definitions->zen-project*
  [zen-lib core-url deps-resources-map
   & [{:as params, :keys [remove-gen-keys? strict-deps]}]]
  (when strict-deps
    (assert (contains? deps-resources-map core-url)
            (str "Couldn't find dependency: " core-url)))
  (let [core-resource    (get deps-resources-map core-url)
        core-ns          (structure-definition->zen-ns zen-lib core-resource params)
        deps-sds-urls    (get-deps-urls core-resource)
        deps-projects    (mapcat (fn [url]
                                   (when (some? (get deps-resources-map url))
                                     (structure-definitions->zen-project* zen-lib url deps-resources-map params)))
                                 deps-sds-urls)
        resolved-core-ns (resolve-deps zen-lib core-ns deps-resources-map)
        projects         (cons resolved-core-ns deps-projects)]
    (cond->> projects
      remove-gen-keys? (map remove-gen-keys))))


(defn structure-definitions->zen-project
  [zen-lib core-url deps-resources
   & [{:as   params
       :keys [remove-gen-keys? strict-deps
              fold-schemas? elements-mode]}]]
  (let [params (merge params
                      {:remove-gen-keys? (or remove-gen-keys? true)
                       :strict-deps      (or strict-deps true)
                       :elements-mode    (or elements-mode :snapshot)
                       :fold-schemas?    (or fold-schemas? false)})
        deps-resources-map (utils/index-by :url deps-resources)]
    (structure-definitions->zen-project* zen-lib core-url deps-resources-map params)))


(defn structure-definitions->uni-zen-project
  [zen-lib core-urls deps-resources
   & [{:as   params
       :keys [remove-gen-keys? strict-deps
              fold-schemas? elements-mode]}]]
  (let [params (merge params
                      {:remove-gen-keys? (or remove-gen-keys? true)
                       :strict-deps      (or strict-deps true)
                       :elements-mode    (or elements-mode :differential)
                       :fold-schemas?    (or fold-schemas? true)})
        deps-resources-map (utils/index-by :url deps-resources)
        project-nses (mapv (fn [url]
                             (structure-definition->zen-ns zen-lib (get deps-resources-map url) params))
                           core-urls)
        project-ns (-> (apply merge project-nses)
                       (assoc 'ns zen-lib)
                       (utils/disj-key 'import zen-lib))]
    [project-ns]))
