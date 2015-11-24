(ns digest-store.core
  (:use [digest :only [Digestible]]
        [clojure.set :only [intersection]]
        [digest-store.utils]
        [clojure.java.io])
  (:require [pantomime.mime :as pm])
  (:import [java.security MessageDigest]
           [java.util Arrays Date] [java.io InputStream])
)

(def ^:const valid-digests
  "A set of supported digest algorithms, normalized to keywords."
  (let [x (intersection
           ;; currently there are a bunch of synonyms as strings
           (set (map #(keyword (.toLowerCase %)) (digest/algorithms)))
           ;; here are our normalized algorithm names
           #{:md5 :sha-1 :sha-256 :sha-384 :sha-512})]
        x))

(defn- sort-digest-size-then-lexical
  "Does what it says on the tin."
  [a b]
  (let [f1 (first a) f2 (first b) s1 (second a) s2 (second b)]
    (or (< s1 s2) (and (= s1 s2) (< (compare f1 f2) 0)))))

(def ^:const digest-sequence
  "A sorted map of digest algorithms and their byte lengths."
  (into (array-map)
        (sort sort-digest-size-then-lexical
              (map #(vec [% (.getDigestLength
                             (MessageDigest/getInstance (name %)))])
                   valid-digests))))

(defprotocol DigestStore
  (store-add    [store item] "Add an item to the digest store.")
  (store-get    [store item] "Retrieve an item from the digest store.")
  (store-remove [store item]
    "Remove (but keep metadata for) an item in the digest store.")
  (store-forget [store item]
    "Remove an item from the digest store and erase its metadata.")
  (store-stats  [store] "Get statistics for the digest store.")
  (store-close  [store] "Close and release the digest store.")
)

(defmulti digest-store
  "Initialize a digest store."
  (fn [& args] (let [x (first args)] (if x x :default)))
  :default :fs-bdb)

;; this is an exact copy of signature in clj-digest, which is private :(
(defn- signature
  "Get signature (string) of digest."
  [^MessageDigest algorithm]
  (let [size (* 2 (.getDigestLength algorithm))
        sig (.toString (BigInteger. 1 (.digest algorithm)) 16)
        padding (apply str (repeat (- size (count sig)) "0"))]
    (str padding sig)))
;; XXX on second thought we don't use this anymore so can probably nuke it.

;; this little puppy generates all the digests at once and puts them in a map.
(extend-protocol Digestible
  java.util.Collection
  (-digest [message algorithm]
    (let [algos (into {} (map #(vec [(keyword %) ^MessageDigest
                                     (MessageDigest/getInstance (name %))])
                              (if (coll? algorithm) algorithm [algorithm])))]
      ;; make sure the digest contexts are fresh, though cargo-culty,
      ;; not sure why they wouldn't be (unless they're singletons?)
      (map #(.reset (get algos %)) (keys algos))
      ;; do the work
      (doseq [^bytes b message]
        (doseq [a (map #(get algos %) (keys algos))] (.update a b)))
      ;; return the result
      (if (and (not (coll? algorithm)) (= 1 (count algos)))
        ;; mimic original behaviour
        (as-uri (get algos (first (keys algos))))
        ;; new behaviour: hash map of algo:digest pairs
        (into {} (map #(vec [% (as-uri (get algos %))]) (keys algos))))))
)

(defprotocol StoreObjectProtocol
  (stream  [obj] "Retrieve the stream from the store object, if it exists.")
  (digests [obj] "Retrieve a map of the digests for the store object")
  (digest  [obj algorithm]
    "Get the digest URI for the store object given the algorithm.")
  (byte-size        [obj] "Get the store object's size in bytes.")
  (mime-type        [obj] "Get the store object's MIME type.")
  (charset          [obj]
    "Get the store object's character set (if applicable, e.g. utf-8).")
  (encoding         [obj]
    "Get the object's lossless encoding (if applicable, e.g. gzip, base64).")
  (first-seen       [obj] "Get the first-seen time for the store object.")
  (last-inserted    [obj] "Get the last-inserted time for the store object.")
  (props-modified   [obj]
    "Get the property modification time for the store object.")
  (removed          [obj]
    "Get the time the object was removed from the store (if applicable).")
  (type-checked     [obj] "Get the type-checked flag from the store object.")
  (type-valid       [obj] "Get the type-valid flag from the store object.")
  (charset-checked  [obj] "Get the charset-checked flag from the store object.")
  (charset-valid    [obj] "Get the charset-valid flag from the store object.")
  (encoding-checked [obj]
    "Get the encoding-checked flag from the store object.")
  (encoding-valid   [obj] "Get the encoding-valid flag from the store object.")
  (syntax-checked   [obj] "Get the syntax-checked flag from the store object.")
  (syntax-valid     [obj] "Get the syntax-valid flag from the store object.")
)

(defrecord StoreObject
;    [^InputStream stream ^hash-map digests ^hash-map times ^hash-map flags]
    [stream digests attrs times flags]
  StoreObjectProtocol
  ;; the thing
  (stream           [obj]
    (let [f (:stream obj)
          s (if (fn? f) (f) f)]
      (assert (instance? InputStream s) "Stream member not an InputStream") s))
  ;; cryptographic digests of the thing
  (digests          [obj] digests)
  (digest           [obj algorithm] (get digests algorithm))
  ;; metadata about the thing
  (byte-size        [obj] (:size     attrs))
  (mime-type        [obj] (:type     attrs))
  (charset          [obj] (:charset  attrs))
  (encoding         [obj] (:encoding attrs))
  ;; metadata about the thing pertaining to the digest store
  (first-seen       [obj] (:ctime times))
  (last-inserted    [obj] (:mtime times))
  (props-modified   [obj] (:ptime times))
  (removed          [obj] (:dtime times))
  ;; metadata pertaining to the heretofore-nonexistent checker process
  (type-checked     [obj] (:type-checked     flags))
  (type-valid       [obj] (:type-valid       flags))
  (charset-checked  [obj] (:charset-checked  flags))
  (charset-valid    [obj] (:charset-valid    flags))
  (encoding-checked [obj] (:encoding-checked flags))
  (encoding-valid   [obj] (:encoding-valid   flags))
  (syntax-checked   [obj] (:syntax-checked   flags))
  (syntax-valid     [obj] (:syntax-valid     flags))
)

(defn store-object [stream digests times flags]
  ;; check that the stream is an fn, InputStream or nil
  ;; check that the digests contain the right key-value pairs
  ;; check that the times are java.util.Date
  ;; check that the flags are all there or supplant with defaults
)
