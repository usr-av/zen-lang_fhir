(ns zen.fhir.generator-test
  (:require
   [zen.fhir.generator :as sut]
   [zen.fhir.core]
   [zen.core]
   [matcho.core :as matcho]
   [clojure.test :as t]
   [clojure.java.io :as io]))


(defn delete-directory-recursive
  [^java.io.File file]
  (when (.isDirectory file)
    (doseq [file-in-dir (.listFiles file)]
      (delete-directory-recursive file-in-dir)))
  (io/delete-file file))


(t/deftest generate-project-integration
  (def ztx  (zen.core/new-context {}))

  (zen.fhir.core/load-all ztx "hl7.fhir.us.core"
                          {:params {"hl7.fhir.r4.core" {:zen.fhir/package-ns 'fhir.r4}
                                    "hl7.fhir.us.core" {:zen.fhir/package-ns 'us-core.v3}}})

  (get-in @ztx [:fhir/inter "StructureDefinition" "http://hl7.org/fhir/StructureDefinition/patient-nationality"])

  (t/is (= :done (sut/generate-zen-schemas ztx)))

  (matcho/match
    (:fhir.zen/ns @ztx)
    {'fhir.r4.Element
     {'ns     'fhir.r4.Element
      'schema {}}

     'fhir.r4.Resource
     {'ns     'fhir.r4.Resource
      'schema {}}

     'fhir.r4.DomainResource
     {'ns     'fhir.r4.DomainResource
      'import #(contains? % 'fhir.r4.Resource)
      'schema {:confirms #(contains? % 'fhir.r4.Resource/schema)}}

     'fhir.r4.Patient
     {'ns     'fhir.r4.Patient
      'import #(contains? % 'fhir.r4.DomainResource)
      'schema {:confirms #(contains? % 'fhir.r4.DomainResource/schema)}}

     'us-core.v3.us-core-patient
     {'ns     'us-core.v3.us-core-patient
      'import #(contains? % 'fhir.r4.Patient)
      'schema {:confirms #(contains? % 'fhir.r4.Patient/schema)}}})


  (delete-directory-recursive (io/file "test-temp-zrc"))

  (t/is (= :done (sut/spit-zen-schemas ztx "test-temp-zrc/")))

  (t/is (.exists (io/file "test-temp-zrc/fhir/r4/Element.edn")))
  (t/is (.exists (io/file "test-temp-zrc/fhir/r4/Resource.edn")))
  (t/is (.exists (io/file "test-temp-zrc/fhir/r4/DomainResource.edn")))
  (t/is (.exists (io/file "test-temp-zrc/fhir/r4/Patient.edn")))
  (t/is (.exists (io/file "test-temp-zrc/us-core/v3/us-core-patient.edn"))))
