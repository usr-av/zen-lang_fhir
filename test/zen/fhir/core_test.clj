(ns zen.fhir.core-test
  (:require [zen.fhir.core :as sut]
            [clojure.test :as t]
            [zen.core]
            [clojure.pprint]
            [matcho.core :as matcho]
            [hiccup.core :as hiccup]
            [clojure.java.io :as io]))

(defn inspect [file data]
  (spit file (with-out-str (clojure.pprint/pprint data))))

(defmulti render-data (fn [x & [opts]] (type x)))

(defmethod render-data :default
  [x & [opts]]
  [:span (with-out-str (clojure.pprint/pprint x))])

(defn primitive? [x]
  (or (number? x) (string? x) (keyword? x) (boolean? x) (set? x)))

(defn render-map [x & [opts]]
  (into [:div.block]
        (for [[k v] (sort-by first x)]
          (if (primitive? v)
            [:div [:b.key (str k)] (render-data v)]
            [:details (cond-> {:style "display: flex;"}
                        (and (or (= k :|) (:| v))
                             (not (:closed opts))) (assoc :open "open"))
             [:summary [:b.key (str k)]]
             (render-data v)]))))

(defmethod render-data
  clojure.lang.PersistentArrayMap
  [x & [opts]] (render-map x opts))

(defmethod render-data
  clojure.lang.PersistentHashMap
  [x & [opts]] (render-map x opts))

(defmethod render-data
  clojure.lang.PersistentVector
  [x & [opts]]
  (into [:div.block "[" (count x)]
        (->> x
             (map-indexed
               (fn [i v]
                 [:details
                  [:summary [:b i]]
                  (render-data v)])))))




;; (defn inspect [file data]
;;   (spit file (with-out-str (clojure.pprint/pprint data))))

(def css
  "
body {font-family: Geneva, Arial, Helvetica, sans-serif; background-color: #282a36; color: #bfcd70;}
.block {padding-left: 1rem;}
.key {color: #fe7ac6; padding-right: 0.5rem; cursor: pointer;}
.key:hover {color: white;}"
  )

(defn inspect [file data & [opts]]
  (spit file (hiccup/html
               [:html [:head [:style css]]
                [:body (render-data data opts)]])))

(t/deftest element-normalization
  (t/testing "cardinality"
    (matcho/match (-> {:id "a", :min 1, :max "1", :base {:max "1"}}
                      sut/normalize-element)
                  {:required true?, :vector not})

    (matcho/match (-> {:id "b", :min 1, :max "1", :base {:max "*"}}
                      sut/normalize-element)
                  {:required true?, :vector not})

    (matcho/match (-> {:id "a.c", :min 1, :max "1", :base {:max "1"}}
                      sut/normalize-element)
                  {:required true?, :vector not})

    ;; (matcho/match (-> {:id "a.d", :min 1, :max "1", :base {:max "*"}}
    ;;                   sut/normalize-element)
    ;;               {:required true?, :vector true?})

    ;; (matcho/match (-> {:id "a.d", :min 1, :max "1", :base {:max "*"}}
    ;;                   sut/normalize-element)
    ;;               {:required true?, :vector true?})

    (matcho/match (-> {:id "a.b", :min 0, :max "*"}
                      sut/normalize-element)
                  {:required not, :vector true?})

    (matcho/match (-> {:id "a.b.c", :min 0}
                      sut/normalize-element)
                  {:required not, :vector not})

    (matcho/match (-> {:id "a.b.d", :min 0, :max "1"}
                      sut/normalize-element)
                  {:required not, :vector not})

    (matcho/match (-> {:id "a.b.e", :min 1, :max "2", :base {:max "*"}}
                      sut/normalize-element)
                  {:required true?, :vector true?, :minItems 1, :maxItems 2})

    ;; (matcho/match (-> {:id "a.b.f", :max "0", :base {:max "*"}}
    ;;                   sut/normalize-element)
    ;;               {:required not, :vector true?, :maxItems 0})

    ;; (matcho/match (-> {:id "a.b.f", :max "0", :base {:max "1"}}
    ;;                   sut/normalize-element)
    ;;               {:required not, :vector not, :prohibited true?})
    ))


;; 1 use base of base for element
"Profile.meta" "Base" "DomainResource.meta"

;;2.
;; vector should be inherited from base
;; problem that we could not distinct max=1 in Profile is it vector or not
"Profile.attr min/max"  "Base.attr vector"
;; enrich with vector
;; enrich with type

;; 3.
"Profiley.attr.subattr" "Base.attr[Type]" "Type.subattr"
;; enrich with vector
;; enrich with type


;; 4.
;; Polymorics
"Profile.attrType"  "Base.attr [polymorphic]"
;; rename attrType => attr.Type
;; enrich with type
;; polymorphic could not be vector in FHIR!
;; restrict polymorphic

;; 5 first class extensions
;; Extension
;; => Complex Type (extension in extension)
;; => primitive type constraints

;; 6. mount extensions in Profile
;; * give it sliceNames
;; * inline primitive extension constraints ???


;; 7. determine all dependencies ()


(def aztx (zen.core/new-context {}))


(defn load-base [{base-name :name tp :base els :els}]
  (sut/load-definiton
    aztx {}
    {:url (some->> base-name (str "url://"))}
    {:resourceType   "StructureDefinition"
     :url            (some->> base-name (str "url://"))
     :type           tp
     :baseDefinition (some->> tp (str "url://"))
     :derivation     "specialization"
     :differential
     {:element
      (->> els
           (mapv
             (fn [x] (update x :id #(str base-name "." %))))
           (into [{:id base-name}]))}}))


(defn load-profile [{prof-name :name base :base els :els}]
  (sut/load-definiton
    aztx {}
    {:url (some->> prof-name (str "url://"))}
    {:resourceType   "StructureDefinition"
     :url            (some->> prof-name (str "url://"))
     :type           base
     :derivation     "constraint"
     :baseDefinition (some->> base (str "url://"))
     :differential
     {:element
      (->> els
           (mapv
             (fn [x] (update x :id #(str prof-name "." %))))
           (into [{:id prof-name}]))}}))


(defn load-type [{type-name :name, els :els}]
  (sut/load-definiton
    aztx {}
    {:url (str "http://hl7.org/fhir/StructureDefinition/" type-name)}
    {:resourceType "StructureDefinition"
     :url          (str "http://hl7.org/fhir/StructureDefinition/" type-name)
     :type         "Complex"
     :derivation   "specialization"
     :kind         "complex-type"
     :differential
     {:element
      (->> els
           (mapv
             (fn [x] (update x :id #(str type-name "." %))))
           (into [{:id type-name}]))}}))

(defn load-extension [{ext-name :name els :els}]
  (sut/load-definiton
    aztx {}
    {:url (str "uri://" ext-name)}
    {:resourceType "StructureDefinition"
     :type         "Extension"
     :derivation   "constraint"
     :kind         (if els "complex-type" "primitive")
     :url          (str "uri://" ext-name)
     :differential
     {:element
      (->> els
           (mapv
             (fn [x] (update x :id #(str ext-name "." %))))
           (into [{:id ext-name}]))}}))


(defn reload []
  (sut/preprocess-resources aztx)
  (sut/process-resources aztx))

;; * :vector  as or
;; * :required as or
;; * :prohibited as or
;; * no inheritance :minItems
;; * no inheritance :maxItems

;; polymorphic valueString (no other types) if String then
;; polymorphic constraint on type  value[x]:valueString only valueString
;; constraint polymorphic type: []

(t/deftest arity-test

  (load-type
    {:name "prim"})

  (load-type
    {:name "string"})


  (t/testing "inheritence"
    (load-base
      {:name "VectorBase"
       :els  [{:id "attr" :min 0 :max "*" :type [{:code "prim"}]}]})

    (reload)

    (matcho/match
      (sut/get-definition aztx "url://VectorBase")
      {:| {:attr {:vector true :type "prim"}}})

    (load-profile
      {:name "VectorProfile"
       :base "VectorBase"
       :els  [{:id "attr" :max "1"}]})

    (reload)

    (matcho/match
      (sut/get-definition aztx "url://VectorProfile")
      {:| {:attr {:vector true :type "prim"}}}))

  (t/testing "Double inheritece"

    (load-base
      {:name "InhBaseOfBase"
       :els  [{:id "attr" :min 0 :max "*" :type [{:code "prim"}]}]})

    (load-base
      {:name "InhBase"
       :base "InhBaseOfBase"
       :els  []})

    (load-profile
      {:name "InhProfile"
       :base "InhBase"
       :els  [{:id "attr" :max "1"}]})

    (reload)

    (matcho/match
      (sut/get-definition aztx  "url://InhProfile")
      {:| {:attr {:vector true :type "prim"}}}))


  (t/testing "Inherit complex type attrs properties"

    (load-type
      {:name "ComplexType"
       :els  [{:id "attr" :min 0 :max "*" :type [{:code "prim"}]}]})

    (load-base
      {:name "CBase"
       :els  [{:id "el" :min 0 :max "*" :type [{:code "ComplexType"}]}]})

    (load-profile
      {:name "CProfile"
       :base "CBase"
       :els  [{:id "el" :max "1"}
              {:id "el.attr" :max "1"}]})

    (reload)

    (matcho/match
      (sut/get-definition aztx "url://CProfile")
      {:| {:el {:vector true
                :|      {:attr {:vector true :type "prim"}}}}}))


  (t/testing "Complex type inheritance"
    (load-type
      {:name "BaseType"
       :els  [{:id "attr" :min 0 :max "*" :type [{:code "prim"}]}]})

    (load-type
      {:name "InhComplexType"
       :base "BaseType"
       :els  []})

    (load-base
      {:name "InhCBase"
       :els  [{:id "el" :min 0 :max "*" :type [{:code "ComplexType"}]}]})

    (load-profile
      {:name "InhCProfile"
       :base "InhCBase"
       :els  [{:id "el" :max "1"}
              {:id "el.attr" :max "1"}]})

    (reload)

    (matcho/match
      (sut/get-definition aztx "url://InhCProfile")
      {:| {:el {:vector true
                :|      {:attr {:vector true :type "prim"}}}}}))

  (t/testing "Polymoric shortcat"
    (load-base
      {:name "PBase"
       :els  [{:id "el[x]" :type [{:code "prim"}
                                  {:code "ComplexType"}]}]})

    (load-profile
      {:name "PProfile1"
       :base "PBase"
       :els  [{:id "elPrim"}]})

    (load-profile
      {:name "PProfile2"
       :base "PBase"
       :els  [{:id "elComplexType"}]})

    (load-profile
      {:name "PProfile3"
       :base "PBase"
       :els  [{:id "el[x]:elComplexType"}
              {:id "el[x]:elPrim"}]})

    (load-profile
      {:name "PProfile4"
       :base "PBase"
       :els  [{:id "el[x]:elComplexType.attr", :max "1"}]})


    (load-profile
      {:name "PTProfile"
       :base "PBase"
       :els  [{:id "elComplexType"}
              {:id "elComplexType.attr" :max "1"}]})

    (reload)

    (matcho/match
      (sut/get-definition aztx "url://PProfile1")
      {:| {:el     {:| {:prim        {:type "prim"}
                        :ComplexType nil}}
           :elPrim nil?}})

    (matcho/match
      (sut/get-definition aztx  "url://PProfile2")
      {:| {:el {:| {:ComplexType {:type "ComplexType"}
                    :prim        nil?}}}})

    (matcho/match
      (sut/get-definition aztx "url://PProfile3")
      {:| {:el {:| {:ComplexType {:type "ComplexType"}
                    :prim        {:type "prim"}}}}})

    (matcho/match
      (sut/get-definition aztx "url://PProfile4")
      {:| {:el {:| {:ComplexType {:type "ComplexType"
                                  :|    {:attr {:vector true
                                                :type   "prim"}}}}}}})

    (matcho/match
      (sut/get-definition aztx "url://PTProfile")
      {:| {:el {:| {:ComplexType {:type "ComplexType"
                                  :|    {:attr {:vector true :type "prim"}}}}}}}))

  (t/testing "Dependency escalation"
    (load-base
      {:name "BaseResource2"
       :base "DomainResource"
       :els  [{:id   "complexattr"
               :type [{:code "ComplexType"}]}
              {:id      "complexattr.attr"
               :type    [{:code "prim"}]
               :binding {:strength "required", :valueSet "url://valueset"}}
              {:id   "ref"
               :type [{:code          "Reference"
                       :targetProfile ["url://SomeResource"]}]}
              {:id      "ext",
               :type    [{:code "Extension"}]
               :slicing {:discriminator [{:type "value", :path "url"}]}}
              {:id        "ext:some-ext"
               :type      [{:code    "Extension"
                            :profile ["url://some-ext"]}]
               :sliceName "some-ext"}
              {:id      "polyattr[x]"
               :type    [{:code "prim"} {:code "string"}]
               :binding {:strength "required", :valueSet "url://valueset"}}]})

    (reload)

    (matcho/match
      (sut/get-definition aztx "url://BaseResource2")
      {:deps {:value-sets            {"url://valueset" [[:complexattr :attr]]}
              :types                 {"ComplexType" [[:complexattr]]
                                      "Reference"   [[:ref]]
                                      "Extension"   [[:some-ext] [:ext]]
                                      "prim"        [[:complexattr :attr] [:polyattr :prim]]
                                      "string"      [[:polyattr :string]]}
              :extensions            {"url://some-ext" [[:some-ext]]}
              :references            {"url://SomeResource"   [[:ref]]}
              :structure-definitions {"url://DomainResource" [[]]}}}))


  (load-extension
    {:name "us-race"
     :els  [{:id        "extension:ombCategory",
             :type      [{:code "Extension"}],
             :sliceName "ombCategory",
             :min       0, :max "5"}
            {:id       "extension:ombCategory.url",
             :type     [{:code "uri"}],
             :min      1, :max "1",
             :fixedUri "ombCategory"}
            {:id      "extension:ombCategory.valueCoding",
             :type    [{:code "Coding"}],
             :min     1, :max "1",
             :binding {:strength "required",
                       :valueSet "http://hl7.org/fhir/us/core/ValueSet/omb-race-category"}}
            {:id        "extension:detailed",
             :type      [{:code "Extension"}],
             :sliceName "detailed",
             :min       0, :max "*"}
            {:id       "extension:detailed.url",
             :type     [{:code "uri"}],
             :min      1, :max "1",
             :fixedUri "detailed"}
            {:id      "extension:detailed.valueCoding",
             :type    [{:code "Coding"}],
             :min     1, :max "1",
             :binding {:strength "required",
                       :valueSet "http://hl7.org/fhir/us/core/ValueSet/detailed-race"}}
            {:id        "extension:text",
             :type      [{:code "Extension"}],
             :sliceName "text",
             :min       1, :max "1"}
            {:id       "extension:text.url",
             :type     [{:code "uri"}],
             :min      1, :max "1",
             :fixedUri "text"}
            {:id   "extension:text.valueString",
             :type [{:code "string"}],
             :min  1, :max "1"}
            {:id       "url",
             :min      1, :max "1",
             :fixedUri "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race"}
            {:id "value[x]", :min 0, :max "0"}]})

  (load-extension
    {:name "pt-nation"
     :els  [{:id "Extension", :min 0, :max "*"}
            {:id "extension:code", :min 0, :max "1", :sliceName "code", :type [{:code "Extension"}]}
            {:id "extension:code.extension", :max "0"}
            {:id "extension:code.url", :fixedUri "code", :type [{:code "uri"}]}
            {:id "extension:code.value[x]", :min 1, :type [{:code "CodeableConcept"}]}
            {:id "extension:period", :min 1, :max "1", :sliceName "period", :type [{:code "Extension"}]}
            {:id "extension:period.extension", :max "0"}
            {:id "extension:period.url", :fixedUri "period", :type [{:code "uri"}]}
            {:id "extension:period.value[x]", :min 1, :type [{:code "Period"}]}
            {:id "url", :fixedUri "http://hl7.org/fhir/StructureDefinition/patient-nationality"}
            {:id "value[x]", :min 0, :max "0"}]})


  (load-extension
    {:name "due-to"
     :els
     [{:id "extension", :max "0"}
      {:id "url", :fixedUri "http://hl7.org/fhir/StructureDefinition/condition-dueTo"}
      {:id   "value[x]", :min 1,
       :type [{:code "CodeableConcept"}
              {:code "Reference",}]}]})

  (load-extension
    {:name "simple-ext"
     :els
     [{:id "url", :fixedUri "http://hl7.org/fhir/StructureDefinition/structuredefinition-xml-type"}
      {:id "valueString", :type [{:code "string"}]}]})

  (reload)

  (matcho/match
    (sut/get-definition aztx "uri://us-race")
    {:kind           "complex-type"
     :derivation     "constraint"
     :fhir/extension string?
     :type           "Extension"
     :|              {:ombCategory
                      {:type     "Coding"
                       :binding  {:strength "required"
                                  :valueSet "http://hl7.org/fhir/us/core/ValueSet/omb-race-category"}
                       :vector   true
                       :maxItems 5}
                      :detailed {:type    "Coding"
                                 :binding {:strength "required"
                                           :valueSet "http://hl7.org/fhir/us/core/ValueSet/detailed-race"}
                                 :vector  true}
                      :text     {:type     "string"
                                 :required true}}})

  (matcho/match
    (sut/get-definition aztx "uri://pt-nation")
    {:kind           "complex-type"
     :derivation     "constraint"
     :type           "Extension"
     :fhir/extension string?
     :|              {:code   {:required    nil?
                               :polymorphic nil?
                               :types       nil?
                               :type        "CodeableConcept"
                               :maxItems    1}
                      :period {:required true
                               :type     "Period"}}})

  (matcho/match
    (sut/get-definition aztx "uri://due-to")
    {:derivation     "constraint"
     :kind           "complex-type"
     :url            "uri://due-to"
     :type           "Extension"
     :fhir/extension string?
     :polymorphic    true
     :types          #{"CodeableConcept" "Reference"}
     :fhir-poly-keys nil? ;; will be determined while mount
     :|              {:CodeableConcept {:type "CodeableConcept"} :Reference {:type "Reference"}}})



  (matcho/match
    (sut/get-definition aztx "uri://simple-ext")
    {:derivation     "constraint"
     :kind           "complex-type"
     :url            "uri://simple-ext"
     :fhir/extension string?
     :type           "string"})



  )


(t/deftest ^:kaocha/pending fhir-aidbox-poly-keys-mapping)

(defn see-definition-els [ztx url]
  (let [d (sut/get-original ztx url)]
    (->
      (select-keys d [:kind :type :url :baseDefinition :derivation])
      (assoc :els
             (->> (:element (:differential d))
                  (mapv #(select-keys % [:id :min :max :sliceName :binding :fixedUri :type])))))))

#_(t/deftest fhir-aidbox-poly-keys-mapping
    (def ztx (zen.core/new-context {}))

    (sut/load-all ztx "hl7.fhir.r4.core")
    (see-definition-els  ztx  "http://hl7.org/fhir/StructureDefinition/patient-nationality")
    (see-definition-els  ztx "http://hl7.org/fhir/StructureDefinition/condition-dueTo")
    (see-definition-els  ztx "http://hl7.org/fhir/StructureDefinition/structuredefinition-xml-type")


    (->> (:element (:differential (sut/get-original ztx
                                                    )))
         (mapv #(select-keys % [:id :min :max :sliceName :binding :fixedUri :type])))



    (-> (sut/get-original ztx "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race")
        (dissoc :snapshot)
        (select-keys [:type :kind :id :differential])
        (update-in [:differential :element]
                   (fn [xs]
                     (->> xs
                          (mapv (fn [x]
                                  (select-keys x [:id :path :type :sliceName :min :max :fixedUri :binding])))))))

    (t/testing "base type poly keys"
      (def observation (sut/get-definition ztx "http://hl7.org/fhir/StructureDefinition/Observation"))

      (matcho/match
        observation
        {:baseDefinition "http://hl7.org/fhir/StructureDefinition/DomainResource"
         :kind           "resource",
         :type           "Observation"
         :derivation     "specialization",
         :fhir-poly-keys {:valueQuantity {:key :value, :type "Quantity"}
                          :valueBoolean  {:key :value, :type "boolean"}}
         :|              {:value     {:polymorphic true
                                      :|           {:boolean  {:type "boolean"}
                                                    :Quantity {:type "Quantity"}}}
                          :component {:fhir-poly-keys {:valueQuantity {:key :value, :type "Quantity"}
                                                       :valueBoolean  {:key :value, :type "boolean"}}
                                      :|              {:value {:polymorphic true
                                                               :|           {:boolean  {:type "boolean"}
                                                                             :Quantity {:type "Quantity"}}}}}}}))

    (t/testing "constraint poly keys fixing"
      (def poly-prof-res (sut/get-definition ztx "http://hl7.org/fhir/us/core/StructureDefinition/pediatric-bmi-for-age"))

      (matcho/match
        poly-prof-res

        {:baseDefinition "http://hl7.org/fhir/StructureDefinition/vitalsigns"
         :kind           "resource",
         :type           "Observation"
         :derivation     "constraint",
         :|              {:value
                          {:polymorphic true
                           :|           {:Quantity
                                         {:|
                                          {:value {:required true}}}}}}}))


    (t/testing "test-zen-transformation"

      (def pres (sut/get-definition ztx "http://hl7.org/fhir/StructureDefinition/Patient"))


      (comment
        (inspect "/tmp/pres.html" (get-in @ztx [:fhir/inter "StructureDefinition"]) {:closed true})
        )


      (matcho/match
        pres
        {:kind           "resource"
         :derivation     "specialization",
         :baseDefinition "http://hl7.org/fhir/StructureDefinition/DomainResource"

         ;; :deps {:valuesets {}
         ;;        :types {}
         ;;        :extensions {}
         ;;        :profiles {}}

         :| {:address             {:short     "An address for the individual"
                                   :type      "Address"
                                   :escalate  {:deps {:type {"Address" true}}}
                                   :vector    true
                                   :isSummary true}
             :multipleBirth
             {:|           {:boolean {:type "boolean"}
                            :integer {:type "integer"}}
              :types       #{"boolean" "integer"}
              :polymorphic true}
             :link                {:type   "BackboneElement"
                                   :vector true
                                   :|      {:other {:type      "Reference"
                                                    :required  true
                                                    :isSummary true}
                                            :type  {:binding  {:strength "required"
                                                               :valueSet "http://hl7.org/fhir/ValueSet/link-type|4.0.1"}
                                                    :required true}}}
             :generalPractitioner {:vector   true
                                   :type     "Reference"
                                   :profiles #{"http://hl7.org/fhir/StructureDefinition/Organization"
                                               "http://hl7.org/fhir/StructureDefinition/Practitioner"
                                               "http://hl7.org/fhir/StructureDefinition/PractitionerRole"}}}})


      (def ares (sut/get-definition ztx "http://hl7.org/fhir/StructureDefinition/Address"))

      ;; (spit "/tmp/ares.edn" (with-out-str (clojure.pprint/pprint (:elements ares))))

      (matcho/match ares {})

      (def qres (sut/get-definition ztx "http://hl7.org/fhir/StructureDefinition/Questionnaire"))

      ;; (spit "/tmp/qres.edn" (with-out-str (clojure.pprint/pprint qres)))

      (matcho/match
        qres
        {:|
         {:description {}
          :subjectType {}
          :item
          {:|
           {:item {:escalate {:recur [:item]}}
            :enableWhen
            {:| {:question {}
                 :operator {}
                 :answer   {}}}}}}})


      ;; (get-in @ztx [:fhir "StructureDefinition" "Questionnaire"])

      ;; (sut/process-sd quest)

      ;; min/max => vector? required minItems/maxItems
      ;; type -> polymorphic, type, profiles
      ;; references to extensions etc
      ;; group and analyze slicing
      ;; analyze valuesets


      ;; packeg (deps) => index


      ;; {
      ;;  <rt> <url> {
      ;;              :lookup-idx {}
      ;;              :fhir       {}
      ;;              :elements   {}
      ;;              :transforms {}
      ;;              }
      ;;  }

      ;; => ????

      ;; -> rich -> generate
      ;;         -> assumptins check

      ;;may be no vector as a first step
      #_(matcho/match
          (sut/group-elements ztx quest)

          ;; (sut/load-structure-definition ztx quest)

          {
           :original      {}
           :tree-elements {}
           :elements      {}
           :deps          {:base       {}
                           :extensions {}
                           :valuesets  {}}

           }

          {:elements
           {:description {:type [{:code "markdown"}]},
            :subjectType {:vector true
                          :type   [{:code "code"}]},
            :derivedFrom {:vector true
                          :type   [{:code "canonical"}]},
            :name        {:type [{:code "string"}]},
            :code        {:vector true
                          :type   [{:code "Coding"}]},
            :item        {:vector true
                          :elements
                          {:enableBehavior {:type [{:code "code"}]},
                           :definition     {:type [{:code "uri"}]},
                           :item           {:vector true
                                            :recur  [:item]},
                           :enableWhen
                           {:vector true
                            :elements
                            {:question {:type [{:code "string"}]},
                             :operator {:type [{:code "code"}]},
                             :answer   {:polymorphic true
                                        :elements    {:boolean   {}
                                                      :decimal   {}
                                                      :integer   {}
                                                      :date      {}
                                                      :dateTime  {}
                                                      :time      {}
                                                      :string    {:type [{:code "string"}]}
                                                      :Coding    {}
                                                      :Quantity  {}
                                                      :Reference {:type [{:code "Reference", :targetProfile ["http://hl7.org/fhir/StructureDefinition/Resource"]}]}}}}}}}}})





      ))
