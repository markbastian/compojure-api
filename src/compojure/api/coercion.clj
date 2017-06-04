(ns compojure.api.coercion
  (:require [clojure.walk :as walk]
            [compojure.api.middleware :as mw]
            [compojure.api.exception :as ex]
            [compojure.api.coercion.core :as cc]
            [compojure.api.coercion.schema])
  (:import (compojure.api.coercion.core CoercionError)))

(defn find-coercion [coercion]
  (cond
    (nil? coercion) nil
    (keyword? coercion) (cc/named-coercion coercion)
    (satisfies? cc/Coercion coercion) coercion
    :else (throw (ex-info (str "invalid coercion " coercion) {:coercion coercion}))))

(defn coerce-request! [model in type keywordize? request]
  (let [transform (if keywordize? walk/keywordize-keys identity)
        value (transform (in request))]
    (if-let [coercion (-> request
                          (mw/coercion)
                          (find-coercion))]
      (let [format (some-> request :muuntaja/request :format)
            result (cc/coerce-request coercion model value type format request)]
        (if (instance? CoercionError result)
          (throw (ex-info
                   (str "Request validation failed: " (pr-str result))
                   (merge
                     (into {} result)
                     {:type ::ex/request-validation
                      :coercion (cc/get-name coercion)
                      :value value
                      :in [:request in]
                      :request request})))
          result))
      value)))

(defn coerce-response! [request {:keys [status body] :as response} responses]
  (if-let [model (or (:schema (get responses status))
                       (:schema (get responses :default)))]
    (if-let [coercion (-> request
                          (mw/coercion)
                          (find-coercion))]
      (let [format (or (-> response :muuntaja/content-type)
                       (some-> request :muuntaja/response :format))
            result (cc/coerce-response coercion model body :response format response)]
        (if (instance? CoercionError result)
          (throw (ex-info
                   (str "Response validation failed: " (pr-str result))
                   (merge
                     (into {} result)
                     {:type ::ex/response-validation
                      :coercion (cc/get-name coercion)
                      :value body
                      :in [:response :body]
                      :request request
                      :response response})))
          (assoc response
            :compojure.api.meta/serializable? true
            :body result)))
      response)
    response))

;;
;; middleware
;;

(defn wrap-coerce-response [handler responses]
  (fn
    ([request]
     (coerce-response! request (handler request) responses))
    ([request respond raise]
     (handler request
              (fn [response]
                (respond (coerce-response! request response responses)))
              raise))))
