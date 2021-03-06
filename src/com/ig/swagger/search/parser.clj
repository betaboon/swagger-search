(ns com.ig.swagger.search.parser
  (:use [clojure.tools.logging :only [error info]])
  (:require [clojure.set]
            clojure.walk
            medley.core
            [ring.util.codec :as codec]
            [clojure.string :as string]
            [clojure.string :as str]))

(defn encode-ui-path
  [path operation-id]
  (str "/"
       (codec/url-encode (if (string/starts-with? path "/")
                           (.substring path 1)
                           path))
       "/"
       (-> operation-id
           (string/replace #"[/-]" "_")
           (string/replace #"[\{\}]" ""))))

(defn base-path-to-service-name [path]
  (when path
    (str/capitalize (str/replace path "/" ""))))

;;;
;;; V2 parsing
;;;

(defn stringify [key]
  (if key (name key)))

(defn- ref->keyword-path [ref]
  (when (string? ref)
    (map keyword (rest (string/split ref #"/")))))

(defn fields [schema]
  (cond
    (= "object" (:type schema)) (vec (concat (keys (:properties schema))
                                             [(fields (:additionalProperties schema))]
                                             (mapcat fields (vals (:properties schema)))))
    (= "array" (:type schema)) (fields (:items schema))
    :default nil))

(defn- param-data [param]
  (let [schema (:schema param)]
    (assoc
      (select-keys param [:name :description])
      :field-names
      (fields schema))))

(defn find-types [param]
  (let [schema (:schema param param)
        param-type (:type schema)
        type (if (and (:$ref schema)
                      (= "object" param-type))
               (stringify (last (ref->keyword-path (:$ref schema)))))]
    (cons type
          (cond
            (= "object" param-type) (vec (mapcat find-types (cons
                                                              (:additionalProperties schema)
                                                              (vals (:properties schema)))))
            (= "array" param-type) (find-types (:items schema))
            :default nil))))

(defn get-controller-data
  [global-params path [method operation]]
  (let [api-path (encode-ui-path (or (first (:tags operation)) "default")
                                 (or (:operationId operation)
                                     (str (name method) "_" (name path))))]
    {:method                  (name method)
     :summary-and-description (str (:summary operation) (:description operation))
     :parameters              (mapv param-data (concat global-params (:parameters operation)))
     :responses               (mapv param-data (vals (:responses operation)))
     :types                   (vec (distinct (filter some? (mapcat find-types (concat global-params (:parameters operation) (vals (:responses operation)))))))
     :ui-api-path             api-path}))

(defn get-controller-methods [[path path-item]]
  (let [path-methods (dissoc path-item :parameters)
        global-params (:parameters path-item)]
    (mapv (comp
            (fn [index-data] (assoc index-data :path (str "/" (stringify path))))
            (partial get-controller-data global-params path)) path-methods)))

(defn get-controller-paths [swagger-paths]
  (vec (mapcat get-controller-methods swagger-paths)))

(defn- find-ref [swagger-doc ref]
  (let [path (ref->keyword-path ref)]
    (get-in swagger-doc path)))

(defn resolve-refs
  ([swagger-doc] (resolve-refs swagger-doc #{} swagger-doc))
  ([swagger-doc visited-refs form]
   (if (and (map? form)
            (:$ref form)
            (not (visited-refs (:$ref form))))
     (let [next-level (partial resolve-refs swagger-doc (conj visited-refs (:$ref form)))
           resolved-ref (merge form
                               (find-ref swagger-doc (:$ref form)))]
       (medley.core/map-vals next-level resolved-ref))
     (let [next-level (partial resolve-refs swagger-doc visited-refs)]
       (cond
         (list? form) (apply list (map next-level form))
         (instance? clojure.lang.IMapEntry form) (vec (map next-level form))
         (seq? form) (doall (map next-level form))
         (coll? form) (into (empty form) (map next-level form))
         :else form)))))


(defn index-data-for-v2 [{:keys [swagger-doc]}]
  (let [swagger-doc (resolve-refs swagger-doc)
        {:keys [paths swagger]} swagger-doc
        more-index-data (fn [controller-path]
                          (assoc controller-path
                            :servlet-context (:basePath swagger-doc)
                            :service-name (or (-> swagger-doc :info :title)
                                              (base-path-to-service-name (:basePath swagger-doc)))
                            :service-version (-> swagger-doc :info :version)
                            :swagger-version swagger))]
    {:index-data (mapv more-index-data (get-controller-paths paths))}))

;;;
;;; V1
;;;

(defn parse-swagger-controller [{:keys [basePath apis swaggerVersion resourcePath]}]
  (let [build-controller-fn (fn [controller-api operation]
                              (assoc (select-keys operation [:method :summary])
                                :path (:path controller-api)
                                :servlet-context basePath
                                :service-name (base-path-to-service-name basePath) ;; TODO: find if v1 has a service name
                                :swagger-version swaggerVersion
                                :ui-api-path (encode-ui-path resourcePath ;; this does not really work. we need a better solution (something in the UI?) to get the operation expanded
                                                             (:nickname operation (:description controller-api)))))]
    (mapcat (fn [controller-api]
              (map (partial build-controller-fn controller-api) (:operations controller-api)))
            apis)))

(defn index-data-for-v1 [{:keys [v1-api-docs]}]
  (let [controllers (vals v1-api-docs)]
    {:index-data
     (mapcat parse-swagger-controller controllers)}))