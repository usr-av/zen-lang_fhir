(ns zen.fhir.tooling
  (:gen-class)
  (:require [zen.core :as zen-core]
            [zen.fhir.core :refer [load-all]]
            [zen.fhir.generator :refer [spit-zen-npm-modules
                                        generate-zen-schemas]]
            [clojure.java.io :as io]))


(defn spit-aidbox-project [node-modules-folder zrc-dir package-ver & [project-name]]
  (let [ztx (zen-core/new-context {})]
    (load-all ztx nil {:node-modules-folder node-modules-folder})
    (generate-zen-schemas ztx)
    (spit-zen-npm-modules ztx (str zrc-dir "/node_modules") package-ver)
    (spit (format "%s/%s-aidbox-project.edn" zrc-dir project-name)
          (pr-str {'ns (str project-name "-aidbox-project")
                   'import #{(symbol project-name)}}))
    (prn :done)))


(defn -main [node-modules-folder zrc-dir package-ver & [package-name]]
  (let [ztx (zen-core/new-context {})]
    (load-all ztx nil {#_#_:params {"hl7.fhir.r4.core"     {:zen.fhir/package-ns 'fhir-r4}
                                    "hl7.fhir.r3.core"     {:zen.fhir/package-ns 'fhir-r3}
                                    "hl7.fhir.us.core"     {:zen.fhir/package-ns 'us-core-v3}
                                    "hl7.fhir.us.mcode"    {:zen.fhir/package-ns 'mcode-v1}
                                    "hl7.fhir.us.carin-bb" {:zen.fhir/package-ns 'carin-bb-v1}}
                       :node-modules-folder node-modules-folder})
    (generate-zen-schemas ztx)
    (spit-zen-npm-modules ztx zrc-dir package-ver package-name)
    (prn :done)))
