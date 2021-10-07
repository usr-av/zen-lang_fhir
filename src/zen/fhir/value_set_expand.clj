(ns zen.fhir.value-set-expand
  (:require [zen.core :as zen]
            [zen.fhir.value-set-expand]
            [cheshire.core]
            [clojure.java.io :as io]
            [fipp.edn]
            [clojure.string :as str]
            [zen.fhir.utils :as utils]
            [com.rpl.specter :as sp]))


(defn vs-compose-system-fn [ztx value-set system version]
  (when (some? system)
    (fn [concept]
      (= system (:system concept)))))


(defn vs-compose-concept-fn [ztx value-set system version concepts]
  (when (seq concepts)
    (fn [concept]
      (and (= system (:system concept))
           (contains? (into #{} (map :code) concepts) ;; TODO: do this set generation in ValueSet processing
                      (:code concept))))))


(defmulti filter-op
  (fn [_ztx _value-set _system _version filter]
    (:op filter)))


(defmethod filter-op :default [_ztx _value-set _system _version _filter]
  (constantly false))


(defmethod filter-op "=" [_ztx _value-set _system _version filter]
  (fn [concept]
    (= (get (:property concept) (:property filter))
       (:value filter))))


(defmethod filter-op "in" [_ztx _value-set _system _version filter]
  (fn [concept]
    (get (into #{} (map str/trim) (str/split (or (:value filter) "") #","))
         (get (:property concept) (:property filter)))))


(defmethod filter-op "not-in" [_ztx _value-set _system _version filter]
  (fn [concept]
    (not (get (into #{} (map str/trim) (str/split (or (:value filter) "") #","))
              (get (:property concept) (:property filter))))))


(defmethod filter-op "exists" [_ztx _value-set _system _version filter]
  (if (= "false" (some-> (:value filter) str/lower-case str/trim))
    (fn [concept] (nil? (get (:property concept) (:property filter))))
    (fn [concept] (some? (get (:property concept) (:property filter))))))


(defmethod filter-op "is-a" [_ztx _value-set _system _version filter]
  (fn [concept]
    (or (= (:code concept) (:value filter))
        (contains? (set (:hierarchy concept)) (:value filter)))))


(defmethod filter-op "descendent-of" [_ztx _value-set _system _version filter]
  (fn [concept]
    (contains? (set (:hierarchy concept)) (:value filter))))


(defmethod filter-op "is-not-a" [_ztx _value-set _system _version filter]
  (fn [concept] ;; TODO: not sure this is correct impl by spec
    (and (not (contains? (set (:hierarchy concept)) (:value filter)))
         (not= (:code concept) (:value filter)))))


(defmethod filter-op "regex" [_ztx _value-set _system _version filter]
  (fn [concept]
    (when-let [prop (get (:property concept) (:property filter))]
      (re-matches (re-pattern (:value filter))
                  (str prop)))))


(defn vs-compose-filter-fn [ztx value-set system version filters]
  (when (seq filters)
    (apply every-pred
           (comp #{system} :system)
           (map (partial filter-op ztx value-set system version)
                filters))))


(declare compose)


(defn vs-compose-value-set-fn [ztx value-set value-set-urls]
  (when (seq value-set-urls)
    (or (some->> value-set-urls
                 (keep #(when-let [vs (get-in @ztx [:fhir/inter "ValueSet" %])]
                          (compose ztx vs)))
                 not-empty
                 (apply every-pred))
        (constantly false))))


(defn check-if-concept-is-in-this-compose-el-fn [ztx value-set compose-el]
  (let [code-system-pred (or (vs-compose-concept-fn ztx value-set
                                                    (:system compose-el)
                                                    (:version compose-el)
                                                    (:concept compose-el))
                             (vs-compose-filter-fn ztx value-set
                                                   (:system compose-el)
                                                   (:version compose-el)
                                                   (:filter compose-el))
                             (vs-compose-system-fn ztx value-set
                                                   (:system compose-el)
                                                   (:version compose-el)))
        value-set-pred (vs-compose-value-set-fn ztx value-set (:valueSet compose-el))]
    (some->> [code-system-pred value-set-pred]
             (remove nil?)
             not-empty
             (apply every-pred))))


(defn compose [ztx vs]
  (let [include-fn (some->> (get-in vs [:compose :include])
                            (keep (partial check-if-concept-is-in-this-compose-el-fn ztx vs))
                            not-empty
                            (apply some-fn))
        exclude-fn (some->> (get-in vs [:compose :exclude])
                            (keep (partial check-if-concept-is-in-this-compose-el-fn ztx vs))
                            not-empty
                            (apply some-fn)
                            complement)]
    (or (some->> [include-fn exclude-fn]
                 (remove nil?)
                 not-empty
                 (apply every-pred))
        #_(assert (some? include-fn) (str "ValueSet.compose.include is required. Value set url is " (:url vs)))
        (constantly false))))


(defn denormalize-into-concepts [ztx valuesets concepts-map]
  (reduce
    (fn [concepts-acc vs]
      (let [concept-in-vs? (compose ztx vs)]
        (reduce
          (fn [acc [concept-id concept]]
            (if (concept-in-vs? concept)
              (-> acc
                  (update-in [concept-id :valueset]
                             (fnil conj #{})
                             (:url vs))
                  (update-in [concept-id :zen.fhir/resource :valueset]
                             (fnil conj [])
                             (:url vs)))
              acc))
          concepts-acc
          concepts-acc)))
    concepts-map
    valuesets))


(defn denormalize-value-sets [ztx]
  (swap! ztx update-in [:fhir/inter "Concept"]
         (partial denormalize-into-concepts
                  ztx (vals (get-in @ztx [:fhir/inter "ValueSet"])))))
