(ns ftr.utils.core
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]))

(defn dissoc-when-kv
  ([pred m k]
   (cond-> m
     (and (contains? m k)
          (pred [k (get m k)]))
     (dissoc k)))
  ([pred m k & ks]
   (reduce (partial dissoc-when-kv pred)
           (dissoc-when-kv pred m k)
           ks)))


(defn dissoc-when-key
  ([pred m k]
   (cond-> m
     (and (contains? m k)
          (pred k))
     (dissoc k)))
  ([pred m k & ks]
   (reduce (partial dissoc-when-key pred)
           (dissoc-when-key pred m k)
           ks)))


(defn dissoc-when
  ([pred m k]
   (cond-> m
     (and (contains? m k)
          (pred (get m k)))
     (dissoc k)))
  ([pred m k & ks]
   (reduce (partial dissoc-when pred)
           (dissoc-when pred m k)
           ks)))


(defn strip-when [pred m]
  (if-let [ks (seq (keys m))]
    (apply dissoc-when pred m ks)
    m))


(defn strip-nils [m]
  (strip-when nil? m))


(defn calculate-sha256 [source]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (with-open [input-stream  (io/input-stream source)
                digest-stream (java.security.DigestInputStream. input-stream digest)
                output-stream (io/output-stream "/dev/null")]
      (io/copy digest-stream output-stream))
    (format "%032x" (BigInteger. 1 (.digest digest)))))


(defn make-sha256-gzip-writer [output]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        file   (io/file output)]
    {:writer (-> file
                 (java.io.FileOutputStream. true)
                 (java.util.zip.GZIPOutputStream. true)
                 (java.security.DigestOutputStream. digest)
                 (java.io.OutputStreamWriter.)
                 (java.io.BufferedWriter.))
     :file   file
     :digest (fn [] (format "%032x" (BigInteger. 1 (.digest digest))))}))


(defn gen-uuid []
  (str (java.util.UUID/randomUUID)))
