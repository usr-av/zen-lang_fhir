(ns zen.fhir.generator-test
  (:require
   [zen.fhir.generator :as sut]
   [matcho.core :as matcho]
   [clojure.java.io :as io]
   [clojure.test :as t]))

(def qsd   {:name "Quantity",
            :abstract false,
            :type "Quantity",
            :resourceType "StructureDefinition",
            :status "active",
            :id "Quantity",
            :kind "complex-type",
            :url "http://hl7.org/fhir/StructureDefinition/Quantity",
            :differential
            {:element
             [{:constraint
               [{:key "qty-3",
                 :severity "error",
                 :human
                 "If a code for the unit is present, the system SHALL also be present",
                 :expression "code.empty() or system.exists()",
                 :xpath "not(exists(f:code)) or exists(f:system)"}],
               :path "Quantity",
               :min 0,
               :definition
               "A measured amount (or an amount that can potentially be measured). Note that measured amounts include amounts that are not precisely quantified, including amounts involving arbitrary units and floating currencies.",
               :short "A measured or measurable amount",
               :mapping
               [{:identity "v2", :map "SN (see also Range) or CQ"}
                {:identity "rim", :map "PQ, IVL<PQ>, MO, CO, depending on the values"}],
               :extension
               [{:url
                 "http://hl7.org/fhir/StructureDefinition/structuredefinition-standards-status",
                 :valueCode "normative"}
                {:url
                 "http://hl7.org/fhir/StructureDefinition/structuredefinition-normative-version",
                 :valueCode "4.0.0"}],
               :max "*",
               :id "Quantity",
               :comment
               "The context of use may frequently define what kind of quantity this is and therefore what kind of units can be used. The context of use may also restrict the values for the comparator."}
              {:path "Quantity.value",
               :requirements
               "Precision is handled implicitly in almost all cases of measurement.",
               :min 0,
               :definition
               "The value of the measured amount. The value includes an implicit precision in the presentation of the value.",
               :short "Numerical value (with implicit precision)",
               :mapping
               [{:identity "v2", :map "SN.2  / CQ - N/A"}
                {:identity "rim",
                 :map
                 "PQ.value, CO.value, MO.value, IVL.high or IVL.low depending on the value"}],
               :type [{:code "decimal"}],
               :max "1",
               :id "Quantity.value",
               :comment
               "The implicit precision in the value should always be honored. Monetary values have their own rules for handling precision (refer to standard accounting text books).",
               :isSummary true}
              {:path "Quantity.comparator",
               :requirements
               "Need a framework for handling measures where the value is <5ug/L or >400mg/L due to the limitations of measuring methodology.",
               :min 0,
               :definition
               "How the value should be understood and represented - whether the actual value is greater or less than the stated value due to measurement issues; e.g. if the comparator is \"<\" , then the real value is < stated value.",
               :isModifier true,
               :short "< | <= | >= | > - how to understand the value",
               :mapping
               [{:identity "v2", :map "SN.1  / CQ.1"}
                {:identity "rim", :map "IVL properties"}],
               :type [{:code "code"}],
               :meaningWhenMissing
               "If there is no comparator, then there is no modification of the value",
               :binding
               {:extension
                [{:url
                  "http://hl7.org/fhir/StructureDefinition/elementdefinition-bindingName",
                  :valueString "QuantityComparator"}],
                :strength "required",
                :description "How the Quantity should be understood and represented.",
                :valueSet "http://hl7.org/fhir/ValueSet/quantity-comparator|4.0.1"},
               :max "1",
               :id "Quantity.comparator",
               :isModifierReason
               "This is labeled as \"Is Modifier\" because the comparator modifies the interpretation of the value significantly. If there is no comparator, then there is no modification of the value",
               :isSummary true}
              {:path "Quantity.unit",
               :requirements
               "There are many representations for units of measure and in many contexts, particular representations are fixed and required. I.e. mcg for micrograms.",
               :min 0,
               :definition "A human-readable form of the unit.",
               :short "Unit representation",
               :mapping
               [{:identity "v2", :map "(see OBX.6 etc.) / CQ.2"}
                {:identity "rim", :map "PQ.unit"}],
               :type [{:code "string"}],
               :extension
               [{:url
                 "http://hl7.org/fhir/StructureDefinition/elementdefinition-translatable",
                 :valueBoolean true}],
               :max "1",
               :id "Quantity.unit",
               :isSummary true}
              {:path "Quantity.system",
               :requirements
               "Need to know the system that defines the coded form of the unit.",
               :min 0,
               :definition
               "The identification of the system that provides the coded form of the unit.",
               :short "System that defines coded unit form",
               :mapping
               [{:identity "v2", :map "(see OBX.6 etc.) / CQ.2"}
                {:identity "rim", :map "CO.codeSystem, PQ.translation.codeSystem"}],
               :type [{:code "uri"}],
               :max "1",
               :id "Quantity.system",
               :condition ["qty-3"],
               :isSummary true}
              {:path "Quantity.code",
               :requirements
               "Need a computable form of the unit that is fixed across all forms. UCUM provides this for quantities, but SNOMED CT provides many units of interest.",
               :min 0,
               :definition
               "A computer processable form of the unit in some unit representation system.",
               :short "Coded form of the unit",
               :mapping
               [{:identity "v2", :map "(see OBX.6 etc.) / CQ.2"}
                {:identity "rim", :map "PQ.code, MO.currency, PQ.translation.code"}],
               :type [{:code "code"}],
               :max "1",
               :id "Quantity.code",
               :comment
               "The preferred system is UCUM, but SNOMED CT can also be used (for customary units) or ISO 4217 for currency.  The context of use may additionally require a code from a particular system.",
               :isSummary true}]},
            :contact [{:telecom [{:system "url", :value "http://hl7.org/fhir"}]}],
            :baseDefinition "http://hl7.org/fhir/StructureDefinition/Element"})

(def qzs {'Quantity
          {:zen/tags #{'zen/schema 'complex-type}
           :zen/desc "A measured or measurable amount",
           :confirms #{'Element} ;; [:baseDefinition]
           #_:effects #_{'fhir/binding {:strength "extensible",
                                        :description "Appropriate units for Duration.",
                                        :valueSet "http://hl7.org/fhir/ValueSet/duration-units"}

                         'fhir/constraint {"qty-3"
                                           {:severity "error",
                                            :human "If a code for the unit is present, the system SHALL also be present",
                                            :expression "code.empty() or system.exists()",}}}
           :type 'zen/map
           :keys {:value {:confirms #{'decimal}
                          :zen/desc "Numerical value (with implicit precision)"}
                  :comparator {:type 'zen/string
                               ;; :fhir/isSummary true ;;TODO
                               ;; :fhir/isModifier true
                               }
                  :unit {:type 'zen/string
                         :zen/desc "Unit representation"}
                  :system {:confirms #{'uri}
                           :zen/desc "System that defines coded unit form"}
                  :code {:confirms #{'code}}}}})


(def patient-sd (read-string (slurp (io/resource "zen/fhir/pt-sd.edn"))))
(def patient-zs (read-string (slurp (io/resource "zen/fhir/pt-zs.edn"))))


(t/deftest differential-schema
  (t/testing "complex-type"
    (matcho/match
      (sut/structure-definitions->zen-project
        'fhir.R4-test
        "http://hl7.org/fhir/StructureDefinition/Quantity"
        [qsd]
        :fold-schemas? true
        :elements-mode :differential)
      [qzs]))

  (t/testing "resource"
    (matcho/match
      (sut/structure-definitions->zen-project
        'fhir.R4-test
        "http://hl7.org/fhir/StructureDefinition/Patient"
        [patient-sd]
        :remove-gen-keys? true
        :fold-schemas? true
        :elements-mode :differential)
      [{'Patient patient-zs}])))
